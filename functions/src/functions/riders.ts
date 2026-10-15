/**
 * Riders.
 *
 * A rider profile is deliberately separate from a Firebase Authentication account
 * (spec §10): creating a profile grants nobody login access, and a login alone
 * grants nobody rider access. Access is granted only when an invited rider accepts
 * the invitation, which links `riders/{id}.userId` to their account and creates a
 * `rider` membership.
 *
 * Riders never see company-wide data: their reads are constrained by Firestore
 * rules to orders assigned to them and by backend checks on every write.
 */

import { z } from "zod";
import { writeAuditLog } from "../shared/audit";
import { membershipId, requireAuth, requireEntitlementForWrite, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { stableHash } from "../shared/ids";
import { minorSchema, nameSchema, notesSchema, parseWith, phoneSchema, emailSchema } from "../shared/validation";
import { ok, type Handler } from "./deps";

export const riderCostConfigSchema = z.object({
  method: z.enum(["distance_rate", "distance_bands", "zone_based", "fixed", "historical_average", "manual"]),
  perKmRateMinor: minorSchema.optional(),
  defaultFeeMinor: minorSchema.optional(),
});

export const createRiderSchema = z.object({
  tenantId: z.string(),
  idempotencyKey: z.string(),
  name: nameSchema,
  phone: phoneSchema,
  email: emailSchema.optional(),
  vehicleType: z.enum(["motorcycle", "bicycle", "car", "van", "truck", "other"]).optional(),
  defaultCostConfig: riderCostConfigSchema.optional(),
  internalNotes: notesSchema.optional(),
});

export type CreateRiderInput = z.infer<typeof createRiderSchema>;

export interface RiderRecord {
  id: string;
  tenantId: string;
  userId: string | null;
  name: string;
  phone: string;
  email: string | null;
  vehicleType: string | null;
  status: "active" | "inactive" | "suspended";
  defaultCostConfig: Record<string, unknown> | null;
  internalNotes: string | null;
  deliveredCount: number;
  failedCount: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
}

export const createRider: Handler<CreateRiderInput, ReturnType<typeof ok<RiderRecord>>> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(createRiderSchema, input, {
    detail: "rider.invalid_create_input",
    userMessage: "Some rider details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);
  await requireMembership(deps.store, context, parsed.tenantId, "riders.manage");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const outcome = await withIdempotency<RiderRecord & Record<string, unknown>>(
    deps.store,
    { scope: "createRider", tenantId: parsed.tenantId, actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const riderId = deps.store.newId("riders");
      const record = {
        tenantId: parsed.tenantId,
        userId: null,
        name: parsed.name,
        phone: parsed.phone,
        email: parsed.email ?? null,
        vehicleType: parsed.vehicleType ?? null,
        status: "active" as const,
        defaultCostConfig: parsed.defaultCostConfig ?? null,
        internalNotes: parsed.internalNotes ?? null,
        deliveredCount: 0,
        failedCount: 0,
        createdAt: now.toISOString(),
        updatedAt: now.toISOString(),
        createdBy: uid,
      };
      await deps.store.create(`riders/${riderId}`, record);
      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: uid,
        action: "rider.created",
        resourceType: "rider",
        resourceId: riderId,
        metadata: { vehicleType: parsed.vehicleType ?? null },
      });
      return { id: riderId, ...record } as RiderRecord & Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

export const updateRiderSchema = z.object({
  tenantId: z.string(),
  riderId: z.string().min(1).max(64),
  name: nameSchema.optional(),
  phone: phoneSchema.optional(),
  email: emailSchema.nullable().optional(),
  vehicleType: z.enum(["motorcycle", "bicycle", "car", "van", "truck", "other"]).nullable().optional(),
  status: z.enum(["active", "inactive", "suspended"]).optional(),
  defaultCostConfig: riderCostConfigSchema.nullable().optional(),
  internalNotes: notesSchema.nullable().optional(),
});

export type UpdateRiderInput = z.infer<typeof updateRiderSchema>;

export const updateRider: Handler<UpdateRiderInput, ReturnType<typeof ok<{ riderId: string }>>> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(updateRiderSchema, input, {
    detail: "rider.invalid_update_input",
    userMessage: "Some rider details are not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "riders.manage");

  const path = `riders/${parsed.riderId}`;
  const existing = await deps.store.get(path);
  if (!existing || existing.data["tenantId"] !== parsed.tenantId) {
    throw errors.notFound("rider_missing", "That rider was not found.");
  }

  const patch: Record<string, unknown> = { updatedAt: deps.store.now().toISOString() };
  for (const field of ["name", "phone", "email", "vehicleType", "status", "defaultCostConfig", "internalNotes"] as const) {
    if (parsed[field] !== undefined) patch[field] = parsed[field];
  }
  await deps.store.set(path, patch, { merge: true });
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "rider.updated",
    resourceType: "rider",
    resourceId: parsed.riderId,
    metadata: { fields: Object.keys(patch).filter((key) => key !== "updatedAt") },
  });
  return ok({ riderId: parsed.riderId });
};

// ---------------------------------------------------------------------------
// Invitations
// ---------------------------------------------------------------------------

export const createRiderInvitationSchema = z.object({
  tenantId: z.string(),
  riderId: z.string().min(1).max(64),
  /** Email the rider will sign in with; the invitation is bound to it. */
  email: emailSchema,
});

export type CreateRiderInvitationInput = z.infer<typeof createRiderInvitationSchema>;

export interface RiderInvitationResult {
  invitationId: string;
  /** Deep-link token handed to the rider; single-use and time-limited. */
  token: string;
  expiresAt: string;
  inviteUrl: string;
}

const RIDER_INVITATION_TTL_DAYS = 14;

export const createRiderInvitation: Handler<
  CreateRiderInvitationInput,
  ReturnType<typeof ok<RiderInvitationResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(createRiderInvitationSchema, input, {
    detail: "rider.invalid_invitation_input",
    userMessage: "That rider invitation is not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "riders.manage");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const rider = await deps.store.get(`riders/${parsed.riderId}`);
  if (!rider || rider.data["tenantId"] !== parsed.tenantId) {
    throw errors.notFound("rider_missing", "That rider was not found.");
  }
  if (typeof rider.data["userId"] === "string" && rider.data["userId"].length > 0) {
    throw errors.failedPrecondition(
      "rider_already_linked",
      "That rider already has an account linked to their profile.",
    );
  }

  const now = deps.store.now();
  const token = stableHash("rider-invite", parsed.tenantId, parsed.riderId, parsed.email, now.toISOString()).slice(0, 32);
  const expiresAt = new Date(now.getTime() + RIDER_INVITATION_TTL_DAYS * 24 * 60 * 60 * 1000).toISOString();

  await deps.store.set(
    `riderInvitations/${token}`,
    {
      tenantId: parsed.tenantId,
      riderId: parsed.riderId,
      email: parsed.email,
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
    action: "rider.invited",
    resourceType: "riderInvitation",
    resourceId: token,
    metadata: { riderId: parsed.riderId, email: parsed.email },
  });

  const inviteUrl = `${deps.config.publicMarketingUrl.replace(/\/$/, "")}/rider-invite?token=${encodeURIComponent(token)}`;
  return ok({ invitationId: token, token, expiresAt, inviteUrl });
};

export const acceptRiderInvitationSchema = z.object({
  token: z.string().min(8).max(128),
});

export type AcceptRiderInvitationInput = z.infer<typeof acceptRiderInvitationSchema>;

export interface AcceptRiderInvitationResult {
  tenantId: string;
  riderId: string;
  role: "rider";
}

/**
 * Links the signed-in account to a rider profile.
 *
 * Requires a verified email matching the invitation. Nothing about the invitation
 * can be supplied by the client beyond its token, and the rider role is written by
 * this function only.
 */
export const acceptRiderInvitation: Handler<
  AcceptRiderInvitationInput,
  ReturnType<typeof ok<AcceptRiderInvitationResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(acceptRiderInvitationSchema, input, {
    detail: "rider.invalid_accept_input",
    userMessage: "That invitation link is not valid.",
  });
  const email = typeof context.claims["email"] === "string" ? (context.claims["email"] as string).toLowerCase() : null;
  if (!email) {
    throw errors.failedPrecondition("rider.email_required", "Sign in with the email address the invitation was sent to.");
  }
  if (context.claims["email_verified"] !== true) {
    throw errors.failedPrecondition("rider.email_not_verified", "Verify your email address, then open the invitation again.");
  }

  const invitation = await deps.store.get(`riderInvitations/${parsed.token}`);
  const now = deps.store.now();
  if (!invitation) throw errors.notFound("rider_invitation_missing", "That invitation was not found or was already used.");
  const data = invitation.data;
  if (data["status"] !== "pending") {
    throw errors.failedPrecondition("rider_invitation_not_pending", "That invitation has already been used.");
  }
  const expiresAt = typeof data["expiresAt"] === "string" ? Date.parse(data["expiresAt"] as string) : Number.NaN;
  if (Number.isFinite(expiresAt) && expiresAt < now.getTime()) {
    throw errors.failedPrecondition("rider_invitation_expired", "That invitation has expired. Ask the dispatcher to send a new one.");
  }
  if (typeof data["email"] === "string" && (data["email"] as string).toLowerCase() !== email) {
    throw errors.permissionDenied("rider_invitation_email_mismatch", "This invitation was sent to a different email address.");
  }

  const tenantId = String(data["tenantId"]);
  const riderId = String(data["riderId"]);
  const targetMembershipId = membershipId(tenantId, uid);

  await deps.store.runTransaction(async (tx) => {
    const rider = await tx.get(`riders/${riderId}`);
    if (!rider || rider.data["tenantId"] !== tenantId) {
      throw errors.notFound("rider_missing", "That rider profile no longer exists.");
    }
    const linkedUserId = rider.data["userId"];
    if (typeof linkedUserId === "string" && linkedUserId.length > 0 && linkedUserId !== uid) {
      throw errors.failedPrecondition("rider_already_linked", "That rider profile is already linked to another account.");
    }
    const membership = await tx.get(`memberships/${targetMembershipId}`);
    if (membership && membership.data["role"] !== "rider") {
      throw errors.failedPrecondition(
        "membership_role_conflict",
        "Your account already has a different role in this company. Ask the owner to update it.",
      );
    }
    tx.set(
      `riders/${riderId}`,
      { userId: uid, linkedAt: now.toISOString(), updatedAt: now.toISOString() },
      { merge: true },
    );
    tx.set(
      `memberships/${targetMembershipId}`,
      {
        tenantId,
        userId: uid,
        role: "rider",
        status: "active",
        riderId,
        email,
        grantedPermissions: [],
        revokedPermissions: [],
        createdAt: membership ? membership.data["createdAt"] : now.toISOString(),
        updatedAt: now.toISOString(),
        acceptedAt: now.toISOString(),
      },
      { merge: false },
    );
    tx.update(`riderInvitations/${parsed.token}`, { status: "accepted", acceptedAt: now.toISOString(), acceptedBy: uid });
  });

  await writeAuditLog(deps.store, {
    tenantId,
    actorUserId: uid,
    action: "rider.invitation_accepted",
    resourceType: "rider",
    resourceId: riderId,
    metadata: { email },
  });

  return ok({ tenantId, riderId, role: "rider" as const });
};

/** Bumps a rider's completed/failed counters when a delivery closes. */
export async function bumpRiderCounters(
  deps: Parameters<Handler<unknown, unknown>>[2],
  tenantId: string,
  riderId: string,
  outcome: "delivered" | "failed",
  at: Date,
): Promise<void> {
  const path = `riders/${riderId}`;
  await deps.store.runTransaction(async (tx) => {
    const doc = await tx.get(path);
    if (!doc || doc.data["tenantId"] !== tenantId) return;
    const delivered = typeof doc.data["deliveredCount"] === "number" ? (doc.data["deliveredCount"] as number) : 0;
    const failed = typeof doc.data["failedCount"] === "number" ? (doc.data["failedCount"] as number) : 0;
    tx.set(
      path,
      {
        deliveredCount: outcome === "delivered" ? delivered + 1 : delivered,
        failedCount: outcome === "failed" ? failed + 1 : failed,
        lastDeliveryAt: at.toISOString(),
        updatedAt: at.toISOString(),
      },
      { merge: true },
    );
  });
}
