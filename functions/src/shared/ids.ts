/**
 * Identifier generation.
 *
 * Two rules matter here:
 *  1. public tracking references must be unpredictable (spec §9) — they are the
 *     only thing standing between a stranger and someone's delivery status, so they
 *     are generated from a CSPRNG, never from a sequence or a document id;
 *  2. every identifier that appears in a URL or a store listing is server-generated.
 */

import { createHash, randomBytes, randomUUID } from "node:crypto";
import type { DataStore } from "../data/types";

/** Crockford base32 — no I, L, O or U, so references survive being read aloud. */
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

function randomBase32(length: number): string {
  const bytes = randomBytes(length);
  let output = "";
  for (let index = 0; index < length; index += 1) {
    output += ALPHABET[(bytes[index] ?? 0) % ALPHABET.length];
  }
  return output;
}

export const TRACKING_REFERENCE_PREFIX = "ART";

/**
 * Public tracking reference, e.g. "ART-7K4M2QX9TB".
 * 10 characters of CSPRNG output ≈ 50 bits of entropy, so enumeration is
 * impractical; `getPublicTracking` additionally rate-limits lookups.
 */
export function generateTrackingReference(prefix: string = TRACKING_REFERENCE_PREFIX): string {
  return `${prefix}-${randomBase32(10)}`;
}

export function isTrackingReference(value: unknown): value is string {
  return typeof value === "string" && /^[A-Z0-9]{2,6}-[0-9A-HJKMNP-TV-Z]{6,16}$/.test(value);
}

export function newRequestId(): string {
  return randomUUID();
}

/**
 * Stable, non-reversible key used for idempotency documents and rate-limit buckets.
 * Never store the raw key: hashing keeps request content out of the database.
 */
export function stableHash(...parts: string[]): string {
  return createHash("sha256").update(parts.join("|")).digest("hex").slice(0, 32);
}

/**
 * Per-tenant, per-day human-readable sequence (used for order numbers).
 * A transaction guarantees uniqueness even if two dispatchers tap "create" at once.
 */
export async function nextSequence(store: DataStore, tenantId: string, name: string, scope = "global"): Promise<number> {
  const path = `counters/${tenantId}_${name}_${scope}`;
  return store.runTransaction(async (tx) => {
    const doc = await tx.get(path);
    const previous = typeof doc?.data["value"] === "number" ? (doc.data["value"] as number) : 0;
    const next = previous + 1;
    tx.set(
      path,
      {
        tenantId,
        name,
        scope,
        value: next,
        updatedAt: store.now().toISOString(),
      },
      { merge: true },
    );
    return next;
  });
}

/**
 * Human-friendly order number, e.g. "MXL-261014-0007".
 * `prefix` comes from company settings (2–4 alphanumerics), the middle section is
 * the business date (yyMMdd) so staff can eyeball the day, and the last section is
 * the per-day sequence.
 */
export function formatOrderNumber(prefix: string, businessDateKey: string, sequence: number): string {
  const [year, month, day] = businessDateKey.split("-") as [string, string, string];
  return `${prefix}-${year.slice(2)}${month}${day}-${sequence.toString().padStart(4, "0")}`;
}

export function sanitizePrefix(value: unknown, fallback = "ORD"): string {
  if (typeof value !== "string") return fallback;
  const cleaned = value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 4);
  return cleaned.length >= 2 ? cleaned : fallback;
}

/** Slug from a company name; used for display only, never for authorization. */
export function slugify(value: string): string {
  return value
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .slice(0, 48);
}
