/**
 * Staff memberships and invitations.
 *
 * Membership writes are backend-only (spec §6): an owner invites a colleague by
 * email, the invited user accepts through the app, and the role is applied
 * server-side. No client can write a membership document, and nobody can change
 * their own role.
 */

import { z } from "zod";
import { ROLES, effectivePermissions, isRole } from "../domain/permissions";
import { writeAuditLog } from "../shared/audit";
import { loadMembership, membershipId, parsePermissionList, requireAuth, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { stableHash } from "../shared/ids";
import { emailSchema, nameSchema, parseWith } from "../shared/validation";
import { ok, type Handler } from "./deps";

const roleSchema = z.enum(ROLES);

export const inviteCompanyMemberSchema = z.object({
  tenantId: z.string(),
  idempotencyKey: z.string(),
  email: emailSchema,
  displayName: nameSchema.optional(),
  role: roleSchema,
  grantedPermissions: z.array(z.string().max(60)).max(40).optional(),
  revokedPermissions: z.array(z.string().max(60)).max(40).optional(),
});

export type InviteCompanyMemberInput = z.infer<typeof inviteCompanyMemberSchema>;

export interface InviteCompanyMemberResult {
  invitationId: string;
  email: string;
  role: string;
  expiresAt: string;
}

const INVITATION_TTL_DAYS = 14;

export const inviteCompanyMember: Handler<InviteCompanyMemberInput, ReturnType<typeof ok<InviteCompanyMemberResult>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(inviteCompanyMemberSchema, input, {
    detail: "membership.invalid_invite_input",
    userMessage: "Some staff details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "staff.manage");
  const granted = parsePermissionList(parsed.grantedPermissions, "grantedPermissions");
  const revoked = parsePermissionList(parsed.revokedPermissions, "revokedPermissions");

  if (parsed.role === "owner") {
    throw errors.permissionDenied(
      "membership.cannot_invite_owner",
      "Ownership cannot be handed over by invitation. Use the ownership transfer action instead.",
    );
  }

  const outcome = await withIdempotency<InviteCompanyMemberResult & Record<string, unknown>>(
    deps.store,
    { scope: "inviteCompanyMember", tenantId: parsed.tenantId, actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const invitationId = stableHash(parsed.tenantId, parsed.email);
      const expiresAt = new Date(now.getTime() + INVITATION_TTL_DAYS * 24 * 60 * 60 * 1000).toISOString();

      await deps.store.set(
        `invitations/${invitationId}`,
        {
          tenantId: parsed.tenantId,
          email: parsed.email,
          displayName: parsed.displayName ?? null,
          role: parsed.role,
          grantedPermissions: granted,
          revokedPermissions: revoked,
          status: "pending",
          invitedBy: uid,
          createdAt: now.toISOString(),
          expiresAt,
        },
        { merge: false },
      );

      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: uid,
        action: "membership.invited",
        resourceType: "invitation",
        resourceId: invitationId,
        metadata: { role: parsed.role, email: parsed.email },
      });

      return { invitationId, email: parsed.email, role: parsed.role, expiresAt } as InviteCompanyMemberResult &
        Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

export const acceptInvitationSchema = z.object({
  invitationId: z.string().min(8).max(64),
  displayName: nameSchema.optional(),
});

export type AcceptInvitationInput = z.infer<typeof acceptInvitationSchema>;

export interface AcceptInvitationResult {
  tenantId: string;
  role: string;
  membershipId: string;
}

/**
 * Accepts a pending invitation for the signed-in user.
 *
 * The invitation is matched on the signed-in user's verified email: possession of
 * the invitation id is not by itself enough to join a workspace.
 */
export const acceptInvitation: Handler<AcceptInvitationInput, ReturnType<typeof ok<AcceptInvitationResult>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(acceptInvitationSchema, input, {
    detail: "membership.invalid_accept_input",
    userMessage: "That invitation could not be read.",
  });
  const claimEmail = typeof context.claims["email"] === "string" ? (context.claims["email"] as string).toLowerCase() : null;
  const emailVerified = context.claims["email_verified"] === true;
  if (!claimEmail) {
    throw errors.failedPrecondition(
      "membership.email_required",
      "Add an email address to your account before accepting an invitation.",
    );
  }

  const invitationDoc = await deps.store.get(`invitations/${parsed.invitationId}`);
  if (!invitationDoc) throw errors.notFound("invitation_missing", "That invitation was not found or was already used.");
  const invitation = invitationDoc.data;
  const now = deps.store.now();

  if (invitation["status"] !== "pending") {
    throw errors.failedPrecondition("invitation_not_pending", "That invitation has already been used or withdrawn.");
  }
  const expiresAt = typeof invitation["expiresAt"] === "string" ? Date.parse(invitation["expiresAt"] as string) : Number.NaN;
  if (Number.isFinite(expiresAt) && expiresAt < now.getTime()) {
    throw errors.failedPrecondition("invitation_expired", "That invitation has expired. Ask an owner to send a new one.");
  }
  const invitedEmail = typeof invitation["email"] === "string" ? (invitation["email"] as string).toLowerCase() : null;
  if (invitedEmail && invitedEmail !== claimEmail) {
    throw errors.permissionDenied(
      "invitation_email_mismatch",
      "This invitation was sent to a different email address. Sign in with that address to accept it.",
    );
  }
  if (!emailVerified) {
    throw errors.failedPrecondition(
      "email_not_verified",
      "Verify your email address first, then accept the invitation.",
    );
  }
  if (!isRole(invitation["role"])) {
    throw errors.internal("invitation_role_corrupt");
  }

  const tenantId = String(invitation["tenantId"]);
  const existing = await loadMembership(deps.store, tenantId, uid);
  const targetId = membershipId(tenantId, uid);

  await deps.store.runTransaction(async (tx) => {
    const current = await tx.get(`memberships/${targetId}`);
    if (current && current.data["status"] === "active") {
      throw errors.alreadyExists("membership_exists", "You are already a member of this workspace.");
    }
    const payload = {
      tenantId,
      userId: uid,
      role: invitation["role"],
      status: "active",
      displayName: parsed.displayName ?? (typeof invitation["displayName"] === "string" ? invitation["displayName"] : null),
      email: claimEmail,
      grantedPermissions: Array.isArray(invitation["grantedPermissions"]) ? invitation["grantedPermissions"] : [],
      revokedPermissions: Array.isArray(invitation["revokedPermissions"]) ? invitation["revokedPermissions"] : [],
      createdAt: current ? current.data["createdAt"] : now.toISOString(),
      updatedAt: now.toISOString(),
      invitedBy: invitation["invitedBy"] ?? null,
      acceptedAt: now.toISOString(),
    };
    tx.set(`memberships/${targetId}`, payload, { merge: false });
    tx.update(`invitations/${parsed.invitationId}`, {
      status: "accepted",
      acceptedAt: now.toISOString(),
      acceptedBy: uid,
    });
  });

  await writeAuditLog(deps.store, {
    tenantId,
    actorUserId: uid,
    action: "membership.accepted",
    resourceType: "membership",
    resourceId: targetId,
    metadata: { role: invitation["role"], previouslyInvited: existing !== null },
  });

  return ok({ tenantId, role: String(invitation["role"]), membershipId: targetId });
};

export const updateMemberRoleSchema = z.object({
  tenantId: z.string(),
  userId: z.string().min(1).max(64),
  role: roleSchema,
  grantedPermissions: z.array(z.string().max(60)).max(40).optional(),
  revokedPermissions: z.array(z.string().max(60)).max(40).optional(),
  reason: z.string().trim().min(3).max(240).optional(),
});

export type UpdateMemberRoleInput = z.infer<typeof updateMemberRoleSchema>;

export interface UpdateMemberRoleResult {
  userId: string;
  role: string;
  permissions: string[];
}

/**
 * Changes a colleague's role and permission overrides.
 *
 * Guard rails: only an owner may change roles, nobody may change their own role
 * (which also prevents an owner from accidentally demoting themselves), and the
 * last remaining owner cannot be demoted.
 */
export const updateMemberRole: Handler<UpdateMemberRoleInput, ReturnType<typeof ok<UpdateMemberRoleResult>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(updateMemberRoleSchema, input, {
    detail: "membership.invalid_role_input",
    userMessage: "That role change is not valid.",
  });
  const actor = await requireMembership(deps.store, context, parsed.tenantId, "staff.manage");
  if (actor.role !== "owner") {
    throw errors.permissionDenied("membership.owner_required", "Only the company owner can change roles.");
  }
  if (parsed.userId === uid) {
    throw errors.permissionDenied(
      "membership.cannot_change_own_role",
      "You cannot change your own role. Ask another owner to do it.",
    );
  }

  const targetId = membershipId(parsed.tenantId, parsed.userId);
  const now = deps.store.now();
  const granted = parsePermissionList(parsed.grantedPermissions, "grantedPermissions");
  const revoked = parsePermissionList(parsed.revokedPermissions, "revokedPermissions");

  const result = await deps.store.runTransaction(async (tx) => {
    const target = await tx.get(`memberships/${targetId}`);
    if (!target) throw errors.notFound("membership_missing", "That staff member is not part of this workspace.");

    if (target.data["role"] === "owner" && parsed.role !== "owner") {
      const owners = await tx.getQuery("memberships", {
        where: [
          { field: "tenantId", op: "==", value: parsed.tenantId },
          { field: "role", op: "==", value: "owner" },
          { field: "status", op: "==", value: "active" },
        ],
        limit: 5,
      });
      const otherOwners = owners.filter((doc) => doc.id !== targetId);
      if (otherOwners.length === 0) {
        throw errors.failedPrecondition(
          "membership.last_owner",
          "A workspace must always have at least one owner. Promote another member first.",
        );
      }
    }

    const previousRole = typeof target.data["role"] === "string" ? (target.data["role"] as string) : null;
    const next = { role: parsed.role, grantedPermissions: granted, revokedPermissions: revoked, status: "active" as const };
    tx.set(
      `memberships/${targetId}`,
      { ...next, updatedAt: now.toISOString(), roleUpdatedBy: uid },
      { merge: true },
    );
    return {
      userId: parsed.userId,
      role: parsed.role,
      permissions: [...effectivePermissions(next)],
      previousRole,
    };
  });

  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "membership.role_changed",
    resourceType: "membership",
    resourceId: targetId,
    metadata: {
      role: parsed.role,
      previousRole: result.previousRole,
      granted,
      revoked,
      reason: parsed.reason,
    },
  });

  return ok(result);
};

export const setMemberStatusSchema = z.object({
  tenantId: z.string(),
  userId: z.string().min(1).max(64),
  status: z.enum(["active", "disabled"]),
  reason: z.string().trim().min(3).max(240).optional(),
});

export type SetMemberStatusInput = z.infer<typeof setMemberStatusSchema>;

/** Disables or re-enables a staff member's access without deleting their history. */
export const setMemberStatus: Handler<SetMemberStatusInput, ReturnType<typeof ok<{ userId: string; status: string }>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(setMemberStatusSchema, input, {
    detail: "membership.invalid_status_input",
    userMessage: "That change is not valid.",
  });
  const actor = await requireMembership(deps.store, context, parsed.tenantId, "staff.manage");
  if (parsed.userId === uid) {
    throw errors.permissionDenied("membership.cannot_disable_self", "You cannot change your own access.");
  }
  const targetId = membershipId(parsed.tenantId, parsed.userId);
  const target = await deps.store.get(`memberships/${targetId}`);
  if (!target) throw errors.notFound("membership_missing", "That staff member is not part of this workspace.");
  if (target.data["role"] === "owner" && actor.role !== "owner") {
    throw errors.permissionDenied("membership.owner_required", "Only an owner can change another owner's access.");
  }

  await deps.store.set(`memberships/${targetId}`, { status: parsed.status, updatedAt: deps.store.now().toISOString() }, { merge: true });
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "membership.status_changed",
    resourceType: "membership",
    resourceId: targetId,
    metadata: { status: parsed.status, reason: parsed.reason },
  });
  return ok({ userId: parsed.userId, status: parsed.status });
};
