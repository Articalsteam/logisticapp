/**
 * Order lifecycle.
 *
 * Every state change goes through this module, which is why the rules can be tight:
 * clients cannot write orders at all, so the transition table, the entitlement gate,
 * the tenant check and the audit trail cannot be skipped by a crafted request.
 *
 * Pricing is never taken from the client. `createOrder` recomputes the quote from
 * the active pricing configuration and stores both the price and the rule snapshot
 * that produced it, so later edits to pricing settings cannot rewrite history.
 */

import { z } from "zod";
import { canTransition, isOrderStatus, type OrderStatus } from "../domain/orderStatus";
import type { AdditionalCostInput, QuoteBreakdown } from "../pricing/types";
import { REVIEW_REQUIRED_WARNING_CODES } from "../pricing/engine";
import { writeAuditLog } from "../shared/audit";
import { assertPermission, membershipId, requireAuth, requireEntitlementForWrite, requireMembership } from "../shared/auth";
import { errors } from "../shared/errors";
import { requireIdempotencyKey, withIdempotency } from "../shared/idempotency";
import { formatOrderNumber, generateTrackingReference, nextSequence, stableHash } from "../shared/ids";
import { businessDate } from "../shared/time";
import { addressSchema, nameSchema, notesSchema, phoneSchema, zoneIdSchema } from "../shared/validation";
import { bumpDailyRollup } from "../data/rollups";
import type { DataStore } from "../data/types";
import { incrementCustomerOrderCount } from "./customers";
import { computeQuote, quotePricingInputSchema, type QuotePricingInput } from "./pricing";
import { ok, type Handler, type HandlerDeps } from "./deps";

void incrementCustomerOrderCount;

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

const locationInput = z.object({
  address: addressSchema,
  zoneId: zoneIdSchema.nullable().optional(),
  area: z.string().trim().max(60).nullable().optional(),
  latitude: z.number().min(-90).max(90).nullable().optional(),
  longitude: z.number().min(-180).max(180).nullable().optional(),
  contactName: z.string().trim().max(80).optional(),
  contactPhone: phoneSchema.optional(),
  notes: notesSchema.optional(),
  manualDistanceMetres: z.number().int().min(1).max(2_000_000).nullable().optional(),
});

export interface OrderRecord extends Record<string, unknown> {
  id: string;
  tenantId: string;
  orderNumber: string;
  trackingReference: string;
  status: OrderStatus;
}

export function orderTimeZone(company: Record<string, unknown> | null): string {
  return company && typeof company["timezone"] === "string" ? (company["timezone"] as string) : "Africa/Lagos";
}

export function orderCurrency(company: Record<string, unknown> | null): string {
  return company && typeof company["currency"] === "string" ? (company["currency"] as string) : "NGN";
}

/** Appends an immutable delivery event. The history is never rewritten. */
async function appendDeliveryEvent(
  store: DataStore,
  params: {
    tenantId: string;
    orderId: string;
    status: OrderStatus;
    actorUserId: string;
    notes?: string | null;
    metadata?: Record<string, unknown>;
  },
): Promise<string> {
  const id = store.newId("deliveryEvents");
  await store.create(`orders/${params.orderId}/deliveryEvents/${id}`, {
    tenantId: params.tenantId,
    orderId: params.orderId,
    status: params.status,
    actorUserId: params.actorUserId,
    notes: params.notes ?? null,
    metadata: params.metadata ?? {},
    createdAt: store.now().toISOString(),
  });
  return id;
}

export interface NotificationOutboxEntry {
  tenantId: string;
  type: "rider_assignment" | "delivery_status" | "subscription_expiring" | "subscription_changed" | "member_invited";
  /** Recipients by user id; the trigger resolves device tokens. */
  recipientUserIds: string[];
  title: string;
  body: string;
  /** Deliberately minimal: notification payloads never carry sensitive details. */
  data: Record<string, string>;
}

/**
 * Queues a push notification. The actual send happens in a Firestore trigger so a
 * messaging failure can never roll back a delivery operation.
 */
export async function queueNotification(store: DataStore, entry: NotificationOutboxEntry): Promise<string> {
  const id = store.newId("notificationOutbox");
  await store.create(`notificationOutbox/${id}`, {
    ...entry,
    status: "pending",
    attempts: 0,
    createdAt: store.now().toISOString(),
  });
  return id;
}

/**
 * Keeps the public tracking projection in step with the order.
 *
 * The projection physically contains only the fields a customer may see, which is
 * why public tracking can be served without ever reading a private order document.
 */
export async function syncPublicTracking(
  store: DataStore,
  params: {
    tenantId: string;
    orderId: string;
    reference: string;
    status: OrderStatus;
    company: Record<string, unknown> | null;
    pickupArea?: string | null;
    destinationArea?: string | null;
    customerPhone?: string | null;
    occurredAt: Date;
    eventNote?: string | null;
    statusLabel: string;
    createIfMissing: boolean;
  },
): Promise<void> {
  const path = `publicTracking/${params.reference}`;
  const existing = await store.get(path);
  if (!existing && !params.createIfMissing) return;

  const tracking = (params.company?.["publicTracking"] ?? {}) as Record<string, unknown>;
  const shareAreas = tracking["shareAreas"] !== false;
  const requireVerification = tracking["requireVerification"] === true;

  const history = Array.isArray(existing?.data["history"]) ? ([...(existing!.data["history"] as unknown[])] as unknown[]) : [];
  if (existing) {
    history.push({
      status: params.status,
      label: params.statusLabel,
      at: params.occurredAt.toISOString(),
      ...(params.eventNote ? { note: params.eventNote } : {}),
    });
  }
  const boundedHistory = history.slice(-25); // bounded: never grow without limit

  const verificationSalt = typeof existing?.data["verificationSalt"] === "string" ? (existing!.data["verificationSalt"] as string) : stableHash(params.reference, "salt");
  const phoneDigits = (params.customerPhone ?? "").replace(/\D/g, "");
  const verificationHash =
    requireVerification && phoneDigits.length >= 4 ? stableHash(verificationSalt, phoneDigits.slice(-4)) : null;

  await store.set(
    path,
    {
      tenantId: params.tenantId,
      orderId: params.orderId,
      reference: params.reference,
      status: params.status,
      statusLabel: params.statusLabel,
      companyName: params.company && typeof params.company["name"] === "string" ? params.company["name"] : null,
      companyLogoUrl: params.company && typeof params.company["logoUrl"] === "string" ? params.company["logoUrl"] : null,
      brandColor: params.company && typeof params.company["brandColor"] === "string" ? params.company["brandColor"] : null,
      supportPhone: typeof tracking["supportPhone"] === "string" ? tracking["supportPhone"] : null,
      pickupArea: shareAreas ? (params.pickupArea ?? null) : null,
      destinationArea: shareAreas ? (params.destinationArea ?? null) : null,
      requiresVerification: requireVerification,
      verificationSalt,
      verificationHash,
      history: boundedHistory,
      createdAt: existing ? existing.data["createdAt"] : params.occurredAt.toISOString(),
      updatedAt: params.occurredAt.toISOString(),
    },
    { merge: false },
  );
}

// ---------------------------------------------------------------------------
// Create order
// ---------------------------------------------------------------------------

export const createOrderSchema = quotePricingInputSchema.extend({
  tenantId: z.string().min(1).max(64),
  idempotencyKey: z.string(),
  customerId: z.string().max(64).nullable().optional(),
  /** Inline customer creation from the order form (spec §11). */
  newCustomer: z
    .object({
      name: nameSchema,
      customerType: z.enum(["individual", "business"]),
      phone: phoneSchema,
      email: z.string().trim().toLowerCase().email().max(160).optional(),
    })
    .nullable()
    .optional(),
  /** Used when the delivery is for a one-off sender with no stored record. */
  customerSnapshot: z
    .object({ name: nameSchema, phone: phoneSchema.optional(), customerType: z.enum(["individual", "business"]).optional() })
    .nullable()
    .optional(),
  description: z.string().trim().min(2).max(240),
  itemCategory: z.enum(["food", "medicine", "documents", "parcel", "errand", "furniture", "other"]).optional(),
  scheduledAt: z
    .string()
    .refine((value) => !Number.isNaN(Date.parse(value)), "Use an ISO date-time")
    .nullable()
    .optional(),
  assignedRiderId: z.string().max(64).nullable().optional(),
  /** Warning codes the dispatcher explicitly accepted before creating the order. */
  acknowledgedWarningCodes: z.array(z.string().max(60)).max(20).optional(),
  internalNotes: notesSchema.optional(),
  saveQuote: z.boolean().optional(),
});

export type CreateOrderInput = z.infer<typeof createOrderSchema>;

export interface CreateOrderResult {
  orderId: string;
  orderNumber: string;
  trackingReference: string;
  status: OrderStatus;
  trackingUrl: string;
  requiresVerification: boolean;
  customerChargeMinor: number;
  estimatedRiderCostMinor: number;
  estimatedProfitMinor: number;
  estimatedMarginBps: number;
  warnings: QuoteBreakdown["warnings"];
  quoteId: string | null;
  distanceSource: string | null;
}

export const createOrder: Handler<CreateOrderInput, ReturnType<typeof ok<CreateOrderResult>>> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = parseOrderInput(createOrderSchema, input, {
    detail: "order.invalid_create_input",
    userMessage: "Some delivery details are not valid.",
  });
  const idempotencyKey = requireIdempotencyKey(parsed.idempotencyKey);
  const membership = await requireMembership(deps.store, context, parsed.tenantId, "orders.create");
  if (parsed.customerPriceOverride) assertPermission(membership, "quotes.override_price");
  const entitlement = await requireEntitlementForWrite(deps.store, parsed.tenantId);

  const company = await deps.store.get(`companies/${parsed.tenantId}`);
  if (!company) throw errors.notFound("company_missing", "That company workspace was not found.");

  const outcome = await withIdempotency<CreateOrderResult & Record<string, unknown>>(
    deps.store,
    { scope: "createOrder", tenantId: parsed.tenantId, actorUserId: uid, key: idempotencyKey },
    async () => {
      const now = deps.store.now();
      const timezone = orderTimeZone(company.data);
      const currency = orderCurrency(company.data);
      const businessDateKey = businessDate(now, timezone);

      const computation = await computeQuote(deps, parsed.tenantId, parsed as QuotePricingInput);
      const quoteResult = computation.result;
      if (quoteResult.status !== "ok" || !quoteResult.quote) {
        throw errors.failedPrecondition(
          "quote.needs_input",
          quoteResult.warnings[0]?.message ?? "The delivery price could not be calculated.",
          { warnings: quoteResult.warnings },
        );
      }
      const quote = quoteResult.quote;

      // A quote that needs review (unconfirmed rider cost, price below cost, a
      // manual distance without confirmation) can only become an order once the
      // dispatcher has explicitly acknowledged it.
      const acknowledged = new Set(parsed.acknowledgedWarningCodes ?? []);
      const unacknowledged = quote.warnings.filter(
        (warning) =>
          (warning.severity === "blocker" || REVIEW_REQUIRED_WARNING_CODES.includes(warning.code)) && !acknowledged.has(warning.code),
      );
      if (unacknowledged.length > 0) {
        throw errors.failedPrecondition(
          "quote.needs_review",
          "Review this delivery before saving it.",
          { unacknowledged: unacknowledged.map((warning) => ({ code: warning.code, message: warning.message })) },
        );
      }

      // --- customer -------------------------------------------------------
      let customerId: string | null = typeof parsed.customerId === "string" && parsed.customerId.length > 0 ? parsed.customerId : null;
      let customerSnapshot: Record<string, unknown> = {};
      if (customerId) {
        const customer = await deps.store.get(`customers/${customerId}`);
        if (!customer || customer.data["tenantId"] !== parsed.tenantId) {
          throw errors.notFound("customer_missing", "That customer was not found.");
        }
        customerSnapshot = {
          name: customer.data["name"] ?? null,
          phone: customer.data["phone"] ?? null,
          customerType: customer.data["customerType"] ?? null,
        };
      } else if (parsed.newCustomer) {
        customerId = deps.store.newId("customers");
        const record = {
          tenantId: parsed.tenantId,
          name: parsed.newCustomer.name,
          customerType: parsed.newCustomer.customerType,
          phone: parsed.newCustomer.phone,
          email: parsed.newCustomer.email ?? null,
          address: null,
          notes: null,
          status: "active" as const,
          orderCount: 0,
          createdAt: now.toISOString(),
          updatedAt: now.toISOString(),
          createdBy: uid,
        };
        await deps.store.create(`customers/${customerId}`, record);
        customerSnapshot = { name: record.name, phone: record.phone, customerType: record.customerType };
      } else if (parsed.customerSnapshot) {
        customerSnapshot = {
          name: parsed.customerSnapshot.name,
          phone: parsed.customerSnapshot.phone ?? null,
          customerType: parsed.customerSnapshot.customerType ?? "individual",
        };
      } else {
        throw errors.invalidArgument(
          "order.customer_required",
          "Choose a customer, add a new one, or enter a name and phone number for this delivery.",
        );
      }

      // --- rider assignment (optional at creation) ------------------------
      let assignedRiderId: string | null = null;
      let riderSnapshot: Record<string, unknown> | null = null;
      if (parsed.assignedRiderId) {
        const assignment = await loadAssignableRider(deps.store, parsed.tenantId, parsed.assignedRiderId);
        assignedRiderId = assignment.id;
        riderSnapshot = assignment.snapshot;
      }

      // --- identifiers ----------------------------------------------------
      const prefix = typeof company.data["orderNumberPrefix"] === "string" ? (company.data["orderNumberPrefix"] as string) : "ORD";
      const sequence = await nextSequence(deps.store, parsed.tenantId, "orders", businessDateKey);
      const orderNumber = formatOrderNumber(prefix, businessDateKey, sequence);
      const insertId = deps.store.newId("orders");
      const trackingReference = await reserveTrackingReference(deps.store, insertId);

      const initialStatus: OrderStatus = assignedRiderId ? "assigned" : "pending";

      const orderDoc = {
        tenantId: parsed.tenantId,
        orderNumber,
        trackingReference,
        customerId,
        customerSnapshot,
        pickup: {
          address: parsed.pickup.address,
          zoneId: parsed.pickup.zoneId ?? null,
          area: parsed.pickup.area ?? parsed.pickup.zoneId ?? null,
          latitude: parsed.pickup.latitude ?? null,
          longitude: parsed.pickup.longitude ?? null,
          contactName: parsed.pickup.contactName ?? null,
          contactPhone: parsed.pickup.contactPhone ?? null,
          notes: parsed.pickup.notes ?? null,
        },
        destination: {
          address: parsed.destination.address,
          zoneId: parsed.destination.zoneId ?? null,
          area: parsed.destination.area ?? parsed.destination.zoneId ?? null,
          latitude: parsed.destination.latitude ?? null,
          longitude: parsed.destination.longitude ?? null,
          contactName: parsed.destination.contactName ?? null,
          contactPhone: parsed.destination.contactPhone ?? null,
          notes: parsed.destination.notes ?? null,
        },
        deliveryType: parsed.deliveryType ?? null,
        itemCategory: parsed.itemCategory ?? null,
        description: parsed.description,
        serviceLevel: parsed.serviceLevel,
        status: initialStatus,
        assignedRiderId,
        riderSnapshot,
        distance: quote.distance,
        pricingSnapshot: quote.pricingSnapshot,
        customerChargeMinor: quote.recommendedCustomerChargeMinor,
        estimatedCustomerChargeMinor: quote.computedCustomerChargeMinor,
        estimatedRiderCostMinor: quote.estimatedRiderCost.amountMinor,
        riderCostConfidence: quote.estimatedRiderCost.confidence,
        estimatedAdditionalCostsMinor: quote.estimatedAdditionalDeliveryCostsMinor,
        estimatedProfitMinor: quote.estimatedProfitMinor,
        estimatedMarginBps: quote.estimatedMarginBps,
        actualRiderCostMinor: null,
        actualAdditionalCostsMinor: null,
        actualCustomerChargeMinor: null,
        actualProfitMinor: null,
        actualsRecordedAt: null,
        additionalCosts: parsed.additionalCosts ?? [],
        acknowledgedWarnings: [...acknowledged],
        internalNotes: parsed.internalNotes ?? null,
        scheduledAt: parsed.scheduledAt ?? null,
        businessDate: businessDateKey,
        currency,
        createdBy: uid,
        createdAt: now.toISOString(),
        updatedAt: now.toISOString(),
        completedAt: null,
        cancelledAt: null,
      };

      await deps.store.runTransaction(async (tx) => {
        tx.create(`orders/${insertId}`, orderDoc);
      });

      await appendDeliveryEvent(deps.store, {
        tenantId: parsed.tenantId,
        orderId: insertId,
        status: initialStatus,
        actorUserId: uid,
        notes: assignedRiderId ? "Order created and assigned" : "Order created",
        metadata: { orderNumber, distanceSource: quote.distance?.source ?? null },
      });

      const trackingRequiresVerification = ((company.data["publicTracking"] ?? {}) as Record<string, unknown>)["requireVerification"] === true;
      await syncPublicTracking(deps.store, {
        tenantId: parsed.tenantId,
        orderId: insertId,
        reference: trackingReference,
        status: initialStatus,
        company: company.data,
        pickupArea: parsed.pickup.area ?? parsed.pickup.zoneId ?? null,
        destinationArea: parsed.destination.area ?? parsed.destination.zoneId ?? null,
        customerPhone: typeof customerSnapshot["phone"] === "string" ? (customerSnapshot["phone"] as string) : null,
        occurredAt: now,
        statusLabel: initialStatus === "assigned" ? "rider_assigned" : "order_received",
        createIfMissing: true,
      });

      await bumpDailyRollup(
        deps.store,
        parsed.tenantId,
        businessDateKey,
        {
          ordersCreated: 1,
          estimatedProfitMinor: 0, // estimated profit is only counted once the delivery closes
          ...(initialStatus === "assigned" ? { ordersAssigned: 1 } : {}),
        },
        currency,
      );

      if (customerId) await incrementCustomerOrderCount(deps, parsed.tenantId, customerId, now);

      if (assignedRiderId) {
        const linkedRiderUserId = await riderUserId(deps.store, parsed.tenantId, assignedRiderId);
        if (linkedRiderUserId) {
          await queueNotification(deps.store, {
            tenantId: parsed.tenantId,
            type: "rider_assignment",
            recipientUserIds: [linkedRiderUserId],
            title: "New delivery assigned",
            body: `${orderNumber} is ready for pickup.`,
            data: { orderId: insertId, type: "rider_assignment" },
          });
        }
      }

      await writeAuditLog(deps.store, {
        tenantId: parsed.tenantId,
        actorUserId: uid,
        action: "order.created",
        resourceType: "order",
        resourceId: insertId,
        metadata: {
          orderNumber,
          status: initialStatus,
          customerChargeMinor: quote.recommendedCustomerChargeMinor,
          estimatedRiderCostMinor: quote.estimatedRiderCost.amountMinor,
          estimatedProfitMinor: quote.estimatedProfitMinor,
          distanceSource: quote.distance?.source ?? null,
          pricingConfigVersion: quote.pricingSnapshot.pricingConfigVersion,
          entitlement: entitlement.state,
        },
      });

      const created: CreateOrderResult = {
        orderId: insertId,
        orderNumber,
        trackingReference,
        status: initialStatus,
        trackingUrl: `${deps.config.publicTrackingBaseUrl.replace(/\/$/, "")}/track.html?ref=${encodeURIComponent(trackingReference)}`,
        requiresVerification: trackingRequiresVerification,
        customerChargeMinor: quote.recommendedCustomerChargeMinor,
        estimatedRiderCostMinor: quote.estimatedRiderCost.amountMinor,
        estimatedProfitMinor: quote.estimatedProfitMinor,
        estimatedMarginBps: quote.estimatedMarginBps,
        warnings: quote.warnings,
        quoteId: null,
        distanceSource: quote.distance?.source ?? null,
      };
      return created as CreateOrderResult & Record<string, unknown>;
    },
  );

  deps.log("order.created", { orderId: outcome.result.orderId, replayed: outcome.replayed, tenantId: parsed.tenantId });
  return ok(outcome.result, outcome.replayed);
};

async function reserveTrackingReference(store: DataStore, seed: string): Promise<string> {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const reference = generateTrackingReference();
    const existing = await store.get(`publicTracking/${reference}`);
    if (!existing) return reference;
    void seed;
  }
  throw errors.internal("tracking_reference_exhausted");
}

async function loadAssignableRider(
  store: DataStore,
  tenantId: string,
  riderId: string,
): Promise<{ id: string; snapshot: Record<string, unknown> }> {
  const rider = await store.get(`riders/${riderId}`);
  if (!rider || rider.data["tenantId"] !== tenantId) {
    throw errors.notFound("rider_missing", "That rider was not found.");
  }
  if (rider.data["status"] !== "active") {
    throw errors.failedPrecondition("rider_not_active", "That rider is not active and cannot be assigned deliveries.");
  }
  return {
    id: riderId,
    snapshot: {
      name: rider.data["name"] ?? null,
      phone: rider.data["phone"] ?? null,
      vehicleType: rider.data["vehicleType"] ?? null,
    },
  };
}

async function riderUserId(store: DataStore, tenantId: string, riderId: string): Promise<string | null> {
  const rider = await store.get(`riders/${riderId}`);
  if (!rider || rider.data["tenantId"] !== tenantId) return null;
  const userId = rider.data["userId"];
  return typeof userId === "string" && userId.length > 0 ? userId : null;
}

function parseOrderInput<T extends z.ZodTypeAny>(
  schema: T,
  input: unknown,
  messages: { detail: string; userMessage: string },
): z.infer<T> {
  const parsed = schema.safeParse(input);
  if (!parsed.success) {
    const fields: Record<string, string> = {};
    for (const issue of parsed.error.issues) {
      const path = issue.path.join(".") || "value";
      if (!fields[path]) fields[path] = issue.message;
    }
    throw errors.invalidArgument(messages.detail, messages.userMessage, fields);
  }
  return parsed.data;
}

export { parseOrderInput, appendDeliveryEvent, loadAssignableRider, riderUserId, membershipId };
export type { AdditionalCostInput };
