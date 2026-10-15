/**
 * Role → permission matrix.
 *
 * This is the authoritative definition. The Kotlin app mirrors it in
 * `com.articals.logistics.domain.permissions.Permissions` purely to hide UI that
 * a user cannot use — the backend always re-checks.
 *
 * Adding a permission is a deliberate act: add it here, grant it to the roles
 * that need it, mirror it in Kotlin, and cover it with a test.
 */

export const PERMISSIONS = [
  // Company-level administration
  "company.settings.view",
  "company.settings.manage",
  "staff.view",
  "staff.manage",
  // Pricing
  "pricing.view",
  "pricing.manage",
  "quotes.create",
  "quotes.override_price",
  // Orders
  "orders.view_all",
  "orders.view_assigned",
  "orders.create",
  "orders.update",
  "orders.cancel",
  "orders.assign",
  "orders.correct_status",
  "orders.update_status",
  "orders.record_actuals",
  "orders.record_proof",
  // Riders, customers, expenses
  "riders.view",
  "riders.manage",
  "customers.view",
  "customers.manage",
  "expenses.view",
  "expenses.manage",
  // Reporting
  "reports.view_operational",
  "reports.view_financial",
  "reports.export",
  // Subscription visibility (owner sees status + dates, never billing internals)
  "subscription.view_status",
  // Public tracking link handling
  "tracking.share_link",
] as const;

export type Permission = (typeof PERMISSIONS)[number];

export const ROLES = ["owner", "manager", "dispatcher", "rider"] as const;
export type Role = (typeof ROLES)[number];

const ALL_PERMISSIONS = [...PERMISSIONS] as Permission[];

/**
 * Default permissions per role.
 *
 * `manager` is intentionally broad but does not include company settings,
 * subscription status or staff management by default — the owner may grant those
 * individually through the membership document (`grantedPermissions`).
 */
export const ROLE_PERMISSIONS: Record<Role, readonly Permission[]> = {
  owner: ALL_PERMISSIONS,
  manager: [
    "company.settings.view",
    "staff.view",
    "pricing.view",
    "pricing.manage",
    "quotes.create",
    "orders.view_all",
    "orders.create",
    "orders.update",
    "orders.cancel",
    "orders.assign",
    "orders.update_status",
    "orders.correct_status",
    "orders.record_actuals",
    "orders.record_proof",
    "riders.view",
    "riders.manage",
    "customers.view",
    "customers.manage",
    "expenses.view",
    "expenses.manage",
    "reports.view_operational",
    "reports.view_financial",
    "reports.export",
    "tracking.share_link",
  ],
  dispatcher: [
    "company.settings.view",
    "pricing.view",
    "quotes.create",
    "orders.view_all",
    "orders.create",
    "orders.update",
    "orders.assign",
    "orders.update_status",
    // The acceptance flow has the dispatcher closing a delivery out with the rider's
    // real fee; an owner can revoke this per membership if they prefer to keep it.
    "orders.record_actuals",
    "orders.record_proof",
    "riders.view",
    "customers.view",
    "customers.manage",
    "expenses.view",
    "expenses.manage",
    "reports.view_operational",
    "tracking.share_link",
  ],
  rider: ["orders.view_assigned", "orders.update_status", "orders.record_proof"],
};

export interface MembershipPermissions {
  role: Role;
  /** Extra permissions the owner explicitly granted beyond the role default. */
  grantedPermissions?: readonly string[];
  /** Permissions the owner explicitly removed from the role default. */
  revokedPermissions?: readonly string[];
}

/**
 * Resolves the effective permission set for a membership.
 *
 * Owner always keeps the full set: revoking permissions from the owner role would
 * allow a workspace to lock itself out of administration.
 */
export function effectivePermissions(membership: MembershipPermissions): Set<Permission> {
  if (membership.role === "owner") return new Set(ALL_PERMISSIONS);

  const base = new Set<Permission>(ROLE_PERMISSIONS[membership.role]);
  for (const granted of membership.grantedPermissions ?? []) {
    if (isPermission(granted)) base.add(granted);
  }
  for (const revoked of membership.revokedPermissions ?? []) {
    if (isPermission(revoked)) base.delete(revoked);
  }
  return base;
}

export function isPermission(value: unknown): value is Permission {
  return typeof value === "string" && (PERMISSIONS as readonly string[]).includes(value);
}

export function hasPermission(membership: MembershipPermissions, permission: Permission): boolean {
  return effectivePermissions(membership).has(permission);
}

/**
 * Permissions an owner may never delegate, and which therefore cannot appear in
 * a membership's `grantedPermissions`.
 */
export const NON_DELEGABLE_PERMISSIONS: readonly Permission[] = [];

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

export function isPlatformAdmin(claims: Record<string, unknown> | undefined | null): boolean {
  // Custom claim set exclusively by trusted backend code (see
  // functions/src/functions/admin/setPlatformAdmin.ts). A client can never write
  // this claim, and providing `role: "admin"` in a request body does nothing.
  return claims !== null && claims !== undefined && claims["platformAdmin"] === true;
}
