/**
 * Idempotency for create operations.
 *
 * Mobile networks in Lagos drop connections mid-request and clients retry. Without
 * a guard, a retried "create order" produces two deliveries and two rider
 * assignments. The client sends a client-generated `idempotencyKey`; the backend
 * records the outcome and replays it for repeat attempts.
 *
 * Behaviour:
 *  - first call:  runs the operation, stores the response;
 *  - retry after success: returns the stored response, `replayed: true`;
 *  - retry while the first attempt is still running: `conflict`, ask to retry;
 *  - retry after a failure: the key is freed, so the operation is attempted again.
 */

import type { DataStore, DocData } from "../data/types";
import { errors } from "./errors";
import { stableHash } from "./ids";

export interface IdempotencyScope {
  /** Stable operation name, e.g. "createOrder". */
  scope: string;
  tenantId: string;
  actorUserId: string;
  /** Client-generated key. Required for create operations. */
  key: string;
}

export interface IdempotentResult<T> {
  result: T;
  replayed: boolean;
}

const TTL_HOURS = 24;

export function idempotencyDocPath(scope: IdempotencyScope): string {
  return `idempotencyKeys/${stableHash(scope.scope, scope.tenantId, scope.actorUserId, scope.key)}`;
}

export function requireIdempotencyKey(value: unknown): string {
  if (typeof value !== "string" || value.trim().length < 8 || value.length > 128) {
    throw errors.invalidArgument(
      "idempotency_key_required",
      "This action needs a client-generated request key so it cannot be applied twice.",
    );
  }
  return value.trim();
}

export async function withIdempotency<T extends DocData | Record<string, unknown>>(
  store: DataStore,
  scope: IdempotencyScope,
  run: () => Promise<T>,
): Promise<IdempotentResult<T>> {
  const path = idempotencyDocPath(scope);
  const now = store.now();
  const expiresAt = new Date(now.getTime() + TTL_HOURS * 60 * 60 * 1000).toISOString();

  const existing = await store.get(path);
  if (existing) {
    const status = existing.data["status"];
    if (status === "completed") {
      const response = existing.data["response"];
      return { result: (response ?? {}) as T, replayed: true };
    }
    if (status === "in_progress") {
      const startedAt = typeof existing.data["createdAt"] === "string" ? (existing.data["createdAt"] as string) : "";
      throw errors.conflict(
        "request_in_progress",
        `This request is already being processed (started ${startedAt}). Please wait a moment and try again.`,
      );
    }
    // status === "failed": fall through and retry the operation.
  }

  await store.set(
    path,
    {
      scope: scope.scope,
      tenantId: scope.tenantId,
      actorUserId: scope.actorUserId,
      status: "in_progress",
      createdAt: now.toISOString(),
      expiresAt,
    },
    { merge: true },
  );

  try {
    const result = await run();
    await store.set(
      path,
      {
        status: "completed",
        completedAt: store.now().toISOString(),
        expiresAt,
        response: result as DocData,
      },
      { merge: true },
    );
    return { result, replayed: false };
  } catch (error) {
    await store.set(
      path,
      {
        status: "failed",
        failedAt: store.now().toISOString(),
        failureCode: error instanceof Error ? error.name : "unknown",
        expiresAt,
      },
      { merge: true },
    );
    throw error;
  }
}
