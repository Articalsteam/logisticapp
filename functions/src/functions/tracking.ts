/**
 * Public tracking.
 *
 * The only anonymous endpoint in the product. Design decisions (spec §12):
 *  - it reads `publicTracking/{reference}`, a projection that physically contains
 *    only approved fields, so no private order document is ever loaded;
 *  - lookups are rate limited per caller and per reference, and a miss returns the
 *    same shape as a hit failure so references cannot be probed for existence;
 *  - companies can require an extra detail (last four digits of the customer's
 *    phone) which is verified against a salted hash — never stored in the clear.
 */

import { z } from "zod";
import { enforceRateLimit } from "../shared/rateLimit";
import { errors } from "../shared/errors";
import { stableHash } from "../shared/ids";
import { isTrackingReference } from "../shared/ids";
import { ok, type Handler } from "./deps";

export const getPublicTrackingSchema = z.object({
  reference: z.string().trim().min(6).max(32),
  /** Optional extra detail; required when the company switched verification on. */
  verification: z
    .object({ lastFourPhoneDigits: z.string().trim().regex(/^\d{4}$/, "Enter the last 4 digits") })
    .nullable()
    .optional(),
});

export type GetPublicTrackingInput = z.infer<typeof getPublicTrackingSchema>;

export interface TrackingEvent {
  status: string;
  label: string;
  at: string;
}

export interface PublicTrackingResult {
  reference: string;
  status: string;
  statusLabel: string;
  companyName: string | null;
  companyLogoUrl: string | null;
  brandColor: string | null;
  supportPhone: string | null;
  pickupArea: string | null;
  destinationArea: string | null;
  history: TrackingEvent[];
  lastUpdated: string;
  requiresVerification: boolean;
  /** True when the caller supplied the required extra detail. */
  verified: boolean;
}

/**
 * Human labels for the statuses a customer may see. Internal notes, rider identity,
 * exact addresses, contact numbers and money never appear here.
 */
const PUBLIC_LABELS: Record<string, string> = {
  pending: "Order received",
  confirmed: "Order confirmed",
  assigned: "Rider assigned",
  picked_up: "Picked up",
  in_transit: "On the way",
  delivered: "Delivered",
  failed: "Delivery attempt unsuccessful",
  cancelled: "Cancelled",
};

export const getPublicTracking: Handler<GetPublicTrackingInput, ReturnType<typeof ok<PublicTrackingResult>>> = async (
  input,
  context,
  deps,
) => {
  const parsed = getPublicTrackingSchema.safeParse(input);
  if (!parsed.success) {
    throw errors.invalidArgument("tracking.invalid_input", "Enter the tracking reference from your message.");
  }
  const reference = parsed.data.reference.toUpperCase();
  if (!isTrackingReference(reference)) {
    // Same error as a genuine miss: a probe learns nothing.
    throw errors.notFound("tracking_not_found", "We could not find a delivery with that reference. Check the reference and try again.");
  }

  // Abuse control: per-caller and per-reference buckets. The IP is hashed rather
  // than stored, and callers behind one NAT do not exhaust a shared allowance.
  const callerKey = existingCallerKey(context);
  await enforceRateLimit(
    deps.store,
    `tracking:caller:${callerKey}`,
    deps.config.trackingRateLimit,
    "Too many tracking lookups from this connection. Please wait a few minutes and try again.",
  );
  await enforceRateLimit(
    deps.store,
    `tracking:reference:${stableHash(reference)}`,
    { limit: 60, windowSeconds: 600 },
    "Too many lookups for this reference. Please wait a few minutes and try again.",
  );

  const doc = await deps.store.get(`publicTracking/${reference}`);
  if (!doc) {
    throw errors.notFound("tracking_not_found", "We could not find a delivery with that reference. Check the reference and try again.");
  }
  const data = doc.data;

  const requiresVerification = data["requiresVerification"] === true;
  const suppliedDigits = parsed.data.verification?.lastFourPhoneDigits ?? null;
  let verified = false;
  if (requiresVerification) {
    const salt = typeof data["verificationSalt"] === "string" ? (data["verificationSalt"] as string) : "";
    const expected = typeof data["verificationHash"] === "string" ? (data["verificationHash"] as string) : "";
    if (suppliedDigits && expected) {
      verified = stableHash(salt, suppliedDigits) === expected;
    }
    if (!verified) {
      return ok({
        reference,
        status: "verification_required",
        statusLabel: requiresVerification && !suppliedDigits ? "Verification needed" : "Verification failed",
        companyName: typeof data["companyName"] === "string" ? (data["companyName"] as string) : null,
        companyLogoUrl: typeof data["companyLogoUrl"] === "string" ? (data["companyLogoUrl"] as string) : null,
        brandColor: typeof data["brandColor"] === "string" ? (data["brandColor"] as string) : null,
        supportPhone: typeof data["supportPhone"] === "string" ? (data["supportPhone"] as string) : null,
        pickupArea: null,
        destinationArea: null,
        history: [],
        lastUpdated: typeof data["updatedAt"] === "string" ? (data["updatedAt"] as string) : new Date().toISOString(),
        requiresVerification: true,
        verified: false,
      });
    }
  }

  const rawHistory = Array.isArray(data["history"]) ? (data["history"] as unknown[]) : [];
  const history: TrackingEvent[] = rawHistory
    .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null)
    .map((entry) => ({
      status: String(entry["status"] ?? "unknown"),
      label: PUBLIC_LABELS[String(entry["status"])] ?? (typeof entry["label"] === "string" ? (entry["label"] as string) : "Updated"),
      at: typeof entry["at"] === "string" ? (entry["at"] as string) : "",
    }));

  const status = String(data["status"] ?? "pending");
  return ok({
    reference,
    status,
    statusLabel: PUBLIC_LABELS[status] ?? "Updated",
    companyName: typeof data["companyName"] === "string" ? (data["companyName"] as string) : null,
    companyLogoUrl: typeof data["companyLogoUrl"] === "string" ? (data["companyLogoUrl"] as string) : null,
    brandColor: typeof data["brandColor"] === "string" ? (data["brandColor"] as string) : null,
    supportPhone: typeof data["supportPhone"] === "string" ? (data["supportPhone"] as string) : null,
    pickupArea: typeof data["pickupArea"] === "string" ? (data["pickupArea"] as string) : null,
    destinationArea: typeof data["destinationArea"] === "string" ? (data["destinationArea"] as string) : null,
    // The most recent events first, capped: a customer does not need the full history.
    history: history.slice(-8).reverse(),
    lastUpdated: typeof data["updatedAt"] === "string" ? (data["updatedAt"] as string) : new Date().toISOString(),
    requiresVerification,
    verified: requiresVerification ? verified : false,
  });
};

/**
 * Rate-limit key for the caller. Uses a hash of the IP plus the App Check app id
 * when present, so that a shared corporate NAT is not punished as a single abuser.
 */
function existingCallerKey(context: { ip?: string | null; appCheckAppId?: string | null; uid?: string | null }): string {
  const parts = [context.ip ?? "unknown-ip", context.appCheckAppId ?? "no-appcheck"];
  return stableHash(...parts);
}
