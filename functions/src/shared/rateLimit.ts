/**
 * Fixed-window rate limiting, used to make tracking-reference enumeration
 * impractical and to bound abuse on other public entry points.
 *
 * Firestore-backed so it works across function instances without extra
 * infrastructure (the alternative — Memorystore or a dedicated limiter — is not
 * justified at pilot scale). Windows are keyed by bucket + window start, and are
 * cleaned up by the scheduled maintenance function.
 */

import type { DataStore } from "../data/types";
import { errors } from "./errors";
import { stableHash } from "./ids";

export interface RateLimitPolicy {
  limit: number;
  windowSeconds: number;
}

export interface RateLimitResult {
  allowed: boolean;
  remaining: number;
  resetAt: string;
  limit: number;
}

export async function checkRateLimit(
  store: DataStore,
  bucketKey: string,
  policy: RateLimitPolicy,
): Promise<RateLimitResult> {
  const now = store.now();
  const windowMs = policy.windowSeconds * 1000;
  const windowStart = Math.floor(now.getTime() / windowMs) * windowMs;
  const resetAt = new Date(windowStart + windowMs).toISOString();
  // Hashing keeps caller-supplied identifiers (IPs, references) out of the database.
  const path = `rateLimits/${stableHash(bucketKey, String(windowStart))}`;

  return store.runTransaction(async (tx) => {
    const doc = await tx.get(path);
    const current = typeof doc?.data["count"] === "number" ? (doc.data["count"] as number) : 0;
    if (current >= policy.limit) {
      return { allowed: false, remaining: 0, resetAt, limit: policy.limit };
    }
    const next = current + 1;
    tx.set(
      path,
      {
        count: next,
        windowStart: new Date(windowStart).toISOString(),
        expiresAt: new Date(windowStart + windowMs * 2).toISOString(),
      },
      { merge: true },
    );
    return { allowed: true, remaining: policy.limit - next, resetAt, limit: policy.limit };
  });
}

/** Throws a typed `resource_exhausted` error when the caller is over the limit. */
export async function enforceRateLimit(store: DataStore, bucketKey: string, policy: RateLimitPolicy, message: string): Promise<RateLimitResult> {
  const result = await checkRateLimit(store, bucketKey, policy);
  if (!result.allowed) {
    throw errors.resourceExhausted("rate_limited", message);
  }
  return result;
}
