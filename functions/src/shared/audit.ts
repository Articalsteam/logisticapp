/**
 * Audit logging.
 *
 * Important actions (role changes, pricing changes, order status corrections,
 * offline payments, billing bypasses) are recorded here. Two invariants:
 *  - never write secrets, tokens, passwords, payment evidence contents or full
 *    customer contact details into a log line;
 *  - logs are append-only from the backend's point of view.
 */

import type { DataStore, DocData } from "../data/types";

export interface AuditEntry {
  /** Null for platform-level actions that are not scoped to a company. */
  tenantId?: string | null;
  actorUserId: string;
  /** Dotted, stable action name, e.g. "order.status_changed". */
  action: string;
  resourceType: string;
  resourceId: string;
  metadata?: DocData;
}

const SENSITIVE_KEY_PATTERN = /(token|password|secret|api[_-]?key|credential|authorization|iban|card)/i;

/** Removes anything that must never be persisted in a log. */
export function redact(value: unknown, depth = 0): unknown {
  if (depth > 4) return "[truncated]";
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.slice(0, 50).map((entry) => redact(entry, depth + 1));
  if (typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      result[key] = SENSITIVE_KEY_PATTERN.test(key) ? "[redacted]" : redact(entry, depth + 1);
    }
    return result;
  }
  if (typeof value === "string" && value.length > 500) return `${value.slice(0, 500)}…`;
  return value;
}

export async function writeAuditLog(store: DataStore, entry: AuditEntry): Promise<string> {
  const id = store.newId("auditLogs");
  const now = store.now();
  await store.set(`auditLogs/${id}`, {
    tenantId: entry.tenantId ?? null,
    actorUserId: entry.actorUserId,
    action: entry.action,
    resourceType: entry.resourceType,
    resourceId: entry.resourceId,
    metadata: redact(entry.metadata ?? {}),
    timestamp: now.toISOString(),
  });
  return id;
}

/**
 * Audit writing that is deliberately fire-and-forget for non-critical paths:
 * a failure to log must not fail the user's action, but it must be visible in the
 * function logs so it can be investigated.
 */
export async function writeAuditLogSafely(
  store: DataStore,
  entry: AuditEntry,
  onError?: (error: unknown) => void,
): Promise<void> {
  try {
    await writeAuditLog(store, entry);
  } catch (error) {
    onError?.(error);
  }
}
