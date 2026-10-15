/**
 * Typed error surface for the trusted backend.
 *
 * Every callable function returns errors through this module so that:
 *  - the client can map them to friendly messages (the codes are mirrored in
 *    Kotlin: `com.articals.logistics.core.network.BackendErrorCode`),
 *  - no stack trace, secret or internal detail leaks to the app,
 *  - the audit log records a stable machine-readable code.
 */

export const BACKEND_ERROR_CODES = [
  "unauthenticated",
  "permission_denied",
  "not_a_member",
  "membership_inactive",
  "invalid_argument",
  "failed_precondition",
  "not_found",
  "already_exists",
  "conflict",
  "resource_exhausted",
  "entitlement_limited",
  "entitlement_suspended",
  "unsupported",
  "provider_unavailable",
  "internal",
] as const;

export type BackendErrorCode = (typeof BACKEND_ERROR_CODES)[number];

export class BackendError extends Error {
  readonly code: BackendErrorCode;
  /** Machine-readable detail code, e.g. "pricing.distance_unavailable". */
  readonly detail: string;
  /** Safe, user-facing message. Never contains internal details. */
  readonly userMessage: string;
  /** Extra safe context for the client (field errors, warnings). */
  readonly meta: Record<string, unknown>;

  constructor(params: {
    code: BackendErrorCode;
    detail: string;
    userMessage: string;
    meta?: Record<string, unknown>;
    cause?: unknown;
  }) {
    super(`${params.code}:${params.detail}`);
    this.name = "BackendError";
    this.code = params.code;
    this.detail = params.detail;
    this.userMessage = params.userMessage;
    this.meta = params.meta ?? {};
    if (params.cause !== undefined) {
      this.cause = params.cause;
    }
  }

  toPayload(): { code: BackendErrorCode; detail: string; message: string; meta: Record<string, unknown> } {
    return { code: this.code, detail: this.detail, message: this.userMessage, meta: this.meta };
  }
}

export const errors = {
  unauthenticated: (detail = "no_auth_context") =>
    new BackendError({
      code: "unauthenticated",
      detail,
      userMessage: "Please sign in again to continue.",
    }),

  permissionDenied: (
    detail: string,
    userMessage = "You do not have permission to do that.",
    meta?: Record<string, unknown>,
  ) => new BackendError({ code: "permission_denied", detail, userMessage, meta }),

  notAMember: () =>
    new BackendError({
      code: "not_a_member",
      detail: "membership_missing",
      userMessage: "This account is not a member of that company workspace.",
    }),

  membershipInactive: (status: string) =>
    new BackendError({
      code: "membership_inactive",
      detail: `membership_${status}`,
      userMessage: "Your access to this workspace has been disabled. Contact the company owner.",
    }),

  invalidArgument: (detail: string, userMessage = "Some of the details you entered are not valid.", meta?: Record<string, unknown>) =>
    new BackendError({ code: "invalid_argument", detail, userMessage, meta }),

  failedPrecondition: (detail: string, userMessage: string, meta?: Record<string, unknown>) =>
    new BackendError({ code: "failed_precondition", detail, userMessage, meta }),

  notFound: (detail: string, userMessage = "We could not find that record.") =>
    new BackendError({ code: "not_found", detail, userMessage }),

  alreadyExists: (detail: string, userMessage = "That record already exists.") =>
    new BackendError({ code: "already_exists", detail, userMessage }),

  conflict: (detail: string, userMessage: string) =>
    new BackendError({ code: "conflict", detail, userMessage }),

  resourceExhausted: (detail: string, userMessage: string) =>
    new BackendError({ code: "resource_exhausted", detail, userMessage }),

  entitlementLimited: (state: string) =>
    new BackendError({
      code: state === "suspended" ? "entitlement_suspended" : "entitlement_limited",
      detail: `entitlement_${state}`,
      userMessage:
        state === "suspended"
          ? "This company account is suspended. Contact Articals Studio support."
          : "This company account is limited until billing is settled. You can still view and export your data.",
    }),

  unsupported: (detail: string, userMessage: string) =>
    new BackendError({ code: "unsupported", detail, userMessage }),

  providerUnavailable: (detail: string, userMessage: string, meta?: Record<string, unknown>) =>
    new BackendError({ code: "provider_unavailable", detail, userMessage, meta }),

  internal: (detail: string, cause?: unknown) =>
    new BackendError({
      code: "internal",
      detail,
      userMessage: "Something went wrong on our side. Please try again.",
      cause,
    }),
};

/** Wraps unknown thrown values so that nothing internal reaches the client. */
export function toBackendError(error: unknown, fallbackDetail = "unexpected_error"): BackendError {
  if (error instanceof BackendError) return error;
  return errors.internal(fallbackDetail, error);
}
