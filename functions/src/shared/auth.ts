/**
 * Authorization.
 *
 * Every trusted operation passes through this module. Three rules that the whole
 * security model rests on:
 *
 *  1. A tenant id supplied by the app is NEVER proof of membership. It is only
 *     meaningful after `requireMembership` has loaded the caller's membership
 *     document for that tenant.
 *  2. Platform admin is a Firebase custom claim written only by trusted code. A
 *     request body containing `role: "admin"` is ignored.
 *  3. Admin SDK writes bypass Firestore rules, so every handler re-checks — the
 *     rules are a second line of defence, not the only one.
 */

import type { DataStore } from "../data/types";
import { computeEntitlement, DEFAULT_ENTITLEMENT_POLICY, type Entitlement, type SubscriptionRecord } from "../domain/entitlement";
import { effectivePermissions, isPermission, isPlatformAdmin, isRole, type MembershipPermissions, type Permission, type Role } from "../domain/permissions";
import { errors } from "./errors";

export interface CallContext {
  uid: string | null;
  claims: Readonly<Record<string, unknown>>;
  /** App Check app id when the request carried a valid token, else null. */
  appCheckAppId?: string | null;
  ip?: string | null;
  userAgent?: string | null;
}

export interface Membership extends MembershipPermissions {
  id: string;
  tenantId: string;
  userId: string;
  status: "active" | "invited" | "disabled";
  displayName?: string;
  email?: string;
  createdAt: string;
  updatedAt: string;
}

export function membershipPath(tenantId: string, userId: string): string {
  return `memberships/${tenantId}_${userId}`;
}

/** Deterministic membership id: one membership per (tenant, user) pair. */
export function membershipId(tenantId: string, userId: string): string {
  return `${tenantId}_${userId}`;
}

export function requireAuth(context: CallContext): string {
  if (!context.uid) throw errors.unauthenticated();
  return context.uid;
}

export function requirePlatformAdmin(context: CallContext): string {
  const uid = requireAuth(context);
  if (!isPlatformAdmin(context.claims as Record<string, unknown>)) {
    // Deliberately the same error as any other permission failure: an attacker
    // learns nothing about whether the admin surface exists.
    throw errors.permissionDenied("platform_admin_required");
  }
  return uid;
}

export async function loadMembership(store: DataStore, tenantId: string, userId: string): Promise<Membership | null> {
  const doc = await store.get(membershipPath(tenantId, userId));
  if (!doc) return null;
  const data = doc.data;
  const role = data["role"];
  if (!isRole(role)) return null;
  const status = data["status"];
  return {
    id: doc.id,
    tenantId: typeof data["tenantId"] === "string" ? data["tenantId"] : tenantId,
    userId: typeof data["userId"] === "string" ? data["userId"] : userId,
    role,
    status: status === "active" || status === "invited" || status === "disabled" ? status : "disabled",
    grantedPermissions: Array.isArray(data["grantedPermissions"]) ? (data["grantedPermissions"] as string[]) : [],
    revokedPermissions: Array.isArray(data["revokedPermissions"]) ? (data["revokedPermissions"] as string[]) : [],
    displayName: typeof data["displayName"] === "string" ? data["displayName"] : undefined,
    email: typeof data["email"] === "string" ? data["email"] : undefined,
    createdAt: typeof data["createdAt"] === "string" ? data["createdAt"] : "",
    updatedAt: typeof data["updatedAt"] === "string" ? data["updatedAt"] : "",
  };
}

/**
 * Loads and verifies the caller's membership for a tenant, optionally requiring a
 * specific permission.
 */
export async function requireMembership(
  store: DataStore,
  context: CallContext,
  tenantId: string,
  permission?: Permission,
): Promise<Membership> {
  const uid = requireAuth(context);
  if (typeof tenantId !== "string" || tenantId.length === 0) {
    throw errors.invalidArgument("tenant_required", "A company is required for this action.");
  }
  const membership = await loadMembership(store, tenantId, uid);
  if (!membership) throw errors.notAMember();
  if (membership.status !== "active") throw errors.membershipInactive(membership.status);
  if (permission && !effectivePermissions(membership).has(permission)) {
    throw errors.permissionDenied(`missing_permission:${permission}`);
  }
  return membership;
}

export function permissionsOf(membership: Membership): Set<Permission> {
  return effectivePermissions(membership);
}

export function assertPermission(membership: Membership, permission: Permission): void {
  if (!effectivePermissions(membership).has(permission)) {
    throw errors.permissionDenied(`missing_permission:${permission}`);
  }
}

/**
 * Validates a permission list supplied by an owner when creating or editing a
 * membership: only known permissions may be referenced.
 */
export function parsePermissionList(value: unknown, field: string): Permission[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) {
    throw errors.invalidArgument(`membership.${field}`, "Permissions must be a list.");
  }
  const result: Permission[] = [];
  for (const entry of value) {
    if (!isPermission(entry)) {
      throw errors.invalidArgument(`membership.${field}`, `"${String(entry)}" is not a permission this platform knows.`);
    }
    if (!result.includes(entry)) result.push(entry);
  }
  return result;
}

export const DEFAULT_COMPANY_REGION = "Africa/Lagos";

/**
 * Entitlement gate for operational writes.
 *
 * Read/export paths stay open for a `limited` company so that no customer ever
 * loses access to their own data because of billing (spec §19).
 */
export async function loadEntitlement(store: DataStore, tenantId: string): Promise<Entitlement> {
  const doc = await store.get(`subscriptions/${tenantId}`);
  const now = store.now();
  if (!doc) {
    // A tenant without a subscription record has no active period: treat it as
    // limited rather than granting free access (createCompany always writes one).
    return computeEntitlement(
      {
        tenantId,
        planId: "none",
        status: "expired",
        billingMode: "trial",
        trialEndsAt: null,
        currentPeriodStart: null,
        currentPeriodEnd: null,
        bypass: null,
        lastOfflinePayment: null,
      },
      now,
      DEFAULT_ENTITLEMENT_POLICY,
    );
  }
  const data = doc.data;
  const record: SubscriptionRecord = {
    tenantId,
    planId: typeof data["planId"] === "string" ? (data["planId"] as string) : "none",
    status: data["status"] === "suspended" ? "suspended" : data["status"] === "trialing" ? "trialing" : data["status"] === "active" ? "active" : "expired",
    billingMode:
      data["billingMode"] === "automated" || data["billingMode"] === "offline" || data["billingMode"] === "bypass" ? data["billingMode"] : "trial",
    trialEndsAt: typeof data["trialEndsAt"] === "string" ? (data["trialEndsAt"] as string) : null,
    currentPeriodStart: typeof data["currentPeriodStart"] === "string" ? (data["currentPeriodStart"] as string) : null,
    currentPeriodEnd: typeof data["currentPeriodEnd"] === "string" ? (data["currentPeriodEnd"] as string) : null,
    bypass: (data["bypass"] ?? null) as SubscriptionRecord["bypass"],
    lastOfflinePayment: (data["lastOfflinePayment"] ?? null) as SubscriptionRecord["lastOfflinePayment"],
  };
  return computeEntitlement(record, now, DEFAULT_ENTITLEMENT_POLICY);
}

export async function requireEntitlementForWrite(store: DataStore, tenantId: string): Promise<Entitlement> {
  const entitlement = await loadEntitlement(store, tenantId);
  if (!entitlement.writesAllowed) {
    throw errors.entitlementLimited(entitlement.state);
  }
  return entitlement;
}

/**
 * Company document lookup with an existence check. Kept here so handlers do not
 * each invent their own "tenant missing" handling.
 */
export async function requireCompany(store: DataStore, tenantId: string): Promise<Record<string, unknown>> {
  const doc = await store.get(`companies/${tenantId}`);
  if (!doc) throw errors.notFound("company_missing", "That company workspace was not found.");
  return doc.data;
}
