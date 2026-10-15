/**
 * Customer records (individuals and corporate accounts).
 *
 * Kept deliberately small for the MVP: contact details, type, internal notes and a
 * rolling order count. Recurring orders, credit accounts, invoicing and bulk import
 * are future work (spec §11) and the schema leaves room for them.
 */

import { z } from "zod";
import { writeAuditLog } from "../shared/audit";
import { requireAuth, requireEntitlementForWrite, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { addressSchema, emailSchema, nameSchema, notesSchema, parseWith, phoneSchema } from "../shared/validation";
import { ok, type Handler } from "./deps";

export const createCustomerSchema = z.object({
  tenantId: z.string(),
  idempotencyKey: z.string(),
  name: nameSchema,
  customerType: z.enum(["individual", "business"]),
  phone: phoneSchema,
  email: emailSchema.optional(),
  address: addressSchema.optional(),
  notes: notesSchema.optional(),
});

export type CreateCustomerInput = z.infer<typeof createCustomerSchema>;

export interface CustomerRecord {
  id: string;
  tenantId: string;
  name: string;
  customerType: "individual" | "business";
  phone: string;
  email: string | null;
  address: string | null;
  notes: string | null;
  status: "active" | "archived";
  orderCount: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
}

export const createCustomer: Handler<CreateCustomerInput, ReturnType<typeof ok<CustomerRecord>>> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseWith(createCustomerSchema, input, {
    detail: "customer.invalid_create_input",
    userMessage: "Some customer details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);
  await requireMembership(deps.store, context, parsed.tenantId, "customers.manage");
  await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const outcome = await withIdempotency<CustomerRecord & Record<string, unknown>>(
    deps.store,
    { scope: "createCustomer", tenantId: parsed.tenantId, actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const customerId = deps.store.newId("customers");
      const record = {
        tenantId: parsed.tenantId,
        name: parsed.name,
        customerType: parsed.customerType,
        phone: parsed.phone,
        email: parsed.email ?? null,
        address: parsed.address ?? null,
        notes: parsed.notes ?? null,
        status: "active" as const,
        orderCount: 0,
        createdAt: now.toISOString(),
        updatedAt: now.toISOString(),
        createdBy: uid,
      };
      await deps.store.create(`customers/${customerId}`, record);
      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: uid,
        action: "customer.created",
        resourceType: "customer",
        resourceId: customerId,
        metadata: { customerType: parsed.customerType },
      });
      return { id: customerId, ...record } as CustomerRecord & Record<string, unknown>;
    },
  );

  return ok(outcome.result, outcome.replayed);
};

export const updateCustomerSchema = z.object({
  tenantId: z.string(),
  customerId: z.string().min(1).max(64),
  name: nameSchema.optional(),
  customerType: z.enum(["individual", "business"]).optional(),
  phone: phoneSchema.optional(),
  email: emailSchema.nullable().optional(),
  address: addressSchema.nullable().optional(),
  notes: notesSchema.nullable().optional(),
  status: z.enum(["active", "archived"]).optional(),
});

export type UpdateCustomerInput = z.infer<typeof updateCustomerSchema>;

export const updateCustomer: Handler<UpdateCustomerInput, ReturnType<typeof ok<{ customerId: string }>>> = async (
  input,
  context,
  deps,
) => {
  const uid = requireAuth(context);
  const parsed = parseWith(updateCustomerSchema, input, {
    detail: "customer.invalid_update_input",
    userMessage: "Some customer details are not valid.",
  });
  await requireMembership(deps.store, context, parsed.tenantId, "customers.manage");

  const path = `customers/${parsed.customerId}`;
  const existing = await deps.store.get(path);
  if (!existing || existing.data["tenantId"] !== parsed.tenantId) {
    // Cross-tenant reads return "not found" rather than "forbidden": a member of
    // another company learns nothing about whether the id exists.
    throw errors.notFound("customer_missing", "That customer was not found.");
  }

  const patch: Record<string, unknown> = { updatedAt: deps.store.now().toISOString() };
  for (const field of ["name", "customerType", "phone", "email", "address", "notes", "status"] as const) {
    if (parsed[field] !== undefined) patch[field] = parsed[field];
  }
  await deps.store.set(path, patch, { merge: true });
  await writeAuditLog(deps.store, {
    tenantId: parsed.tenantId,
    actorUserId: uid,
    action: "customer.updated",
    resourceType: "customer",
    resourceId: parsed.customerId,
    metadata: { fields: Object.keys(patch).filter((key) => key !== "updatedAt") },
  });
  return ok({ customerId: parsed.customerId });
};

/**
 * Bumps a customer's order counter. Called by order creation so the customer list
 * can show "12 deliveries" without counting orders per row.
 */
export async function incrementCustomerOrderCount(
  deps: Parameters<Handler<unknown, unknown>>[2],
  tenantId: string,
  customerId: string,
  at: Date,
): Promise<void> {
  const path = `customers/${customerId}`;
  await deps.store.runTransaction(async (tx) => {
    const doc = await tx.get(path);
    if (!doc || doc.data["tenantId"] !== tenantId) return;
    const current = typeof doc.data["orderCount"] === "number" ? (doc.data["orderCount"] as number) : 0;
    tx.set(path, { orderCount: current + 1, lastOrderAt: at.toISOString(), updatedAt: at.toISOString() }, { merge: true });
  });
}
