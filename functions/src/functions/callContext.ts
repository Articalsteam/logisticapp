/**
 * Maps the Firebase callable request onto the plain CallContext the handlers use.
 *
 * Keeping this mapping in one place means handlers never depend on
 * firebase-functions, which is what makes them unit-testable, and it guarantees the
 * App Check result and the caller's IP (for rate limiting) are carried through.
 */

import type { CallableRequest } from "firebase-functions/v2/https";
import type { CallContext } from "../shared/auth";

export function resolveCallContext(request: CallableRequest<unknown>): CallContext {
  const token = (request.auth?.token ?? {}) as Record<string, unknown>;
  const rawRequest = request.rawRequest as { ip?: string; headers?: Record<string, string | string[] | undefined> } | undefined;
  const userAgentHeader = rawRequest?.headers?.["user-agent"];
  return {
    uid: request.auth?.uid ?? null,
    claims: token,
    appCheckAppId: request.app?.appId ?? null,
    ip: rawRequest?.ip ?? null,
    userAgent: Array.isArray(userAgentHeader) ? (userAgentHeader[0] ?? null) : (userAgentHeader ?? null),
  };
}

/** Context for internal, non-user-triggered work (triggers, scheduled jobs). */
export function contactPayload(overrides: Partial<CallContext> = {}): CallContext {
  return { uid: null, claims: {}, appCheckAppId: null, ip: null, userAgent: "internal", ...overrides };
}
