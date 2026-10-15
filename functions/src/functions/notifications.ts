/**
 * Push notifications.
 *
 * Two halves:
 *  - `registerDeviceToken` stores a bounded, validated token set per user;
 *  - `processNotificationOutbox` is invoked by a Firestore trigger for each queued
 *    notification, sends it through the PushPort and prunes dead tokens.
 *
 * Payload rules (spec §20): minimal data, identifiers only. Notification text never
 * contains customer names, addresses, phone numbers, prices or profit figures, so a
 * lock-screen preview cannot leak business detail.
 */

import { z } from "zod";
import type { PushPort } from "../data/ports";
import { writeAuditLogSafely } from "../shared/audit";
import { requireAuth } from "../shared/auth";
import { errors } from "../shared/errors";
import { stableHash } from "../shared/ids";
import { ok, type Handler } from "./deps";

const MAX_DEVICE_TOKENS_PER_USER = 10;

export const registerDeviceTokenSchema = z.object({
  token: z.string().trim().min(20).max(4096),
  platform: z.enum(["android", "ios"]),
  /** App version, kept for support diagnostics; never used for authorization. */
  appVersion: z.string().max(40).optional(),
});

export type RegisterDeviceTokenInput = z.infer<typeof registerDeviceTokenSchema>;

export interface RegisterDeviceTokenResult {
  registered: boolean;
  tokenCount: number;
}

export const registerDeviceToken: Handler<
  RegisterDeviceTokenInput,
  ReturnType<typeof ok<RegisterDeviceTokenResult>>
> = async (input, context, deps) => {
  const uid = requireAuth(context);
  const parsed = registerDeviceTokenSchema.safeParse(input);
  if (!parsed.success) {
    throw errors.invalidArgument("notifications.invalid_token", "That device could not be registered for notifications.");
  }

  const tokenHash = stableHash(parsed.data.token);
  const now = deps.store.now();
  const path = `users/${uid}`;

  const tokenCount = await deps.store.runTransaction(async (tx) => {
    const user = await tx.get(path);
    const existing = Array.isArray(user?.data["deviceTokens"]) ? ([...(user!.data["deviceTokens"] as unknown[])] as unknown[]) : [];
    const filtered = existing.filter(
      (entry) => typeof entry === "object" && entry !== null && (entry as Record<string, unknown>)["hash"] !== tokenHash,
    );
    // Bounded: keep the most recent tokens only, so the field cannot grow forever.
    const next = [
      ...filtered,
      { hash: tokenHash, token: parsed.data.token, platform: parsed.data.platform, registeredAt: now.toISOString() },
    ].slice(-MAX_DEVICE_TOKENS_PER_USER);

    tx.set(
      path,
      {
        // The raw token is stored server-side only; the client never reads this field
        // (firestore.rules denies reading the users collection from other devices).
        deviceTokens: next,
        updatedAt: now.toISOString(),
        isActive: true,
      },
      { merge: true },
    );
    return next.length;
  });

  return ok({ registered: true, tokenCount });
};

export interface OutboxEntry {
  id: string;
  tenantId: string;
  type: string;
  recipientUserIds: string[];
  title: string;
  body: string;
  data: Record<string, string>;
  attempts?: number;
}

export interface ProcessOutboxResult {
  sent: number;
  failed: number;
  skipped: boolean;
}

/**
 * Sends one queued notification.
 *
 * Idempotent by intent: a retried invocation marks the document as sent, and a
 * second delivery would only repeat the same message. Attempts are counted so a
 * permanently failing entry stops being retried forever.
 */
export function createProcessNotificationOutbox(
  push: PushPort,
): Handler<OutboxEntry, ReturnType<typeof ok<ProcessOutboxResult>>> {
  return async (entry, _context, deps) => {
    const path = `notificationOutbox/${entry.id}`;
    const doc = await deps.store.get(path);
    if (!doc) throw errors.notFound("notifications.outbox_missing", "That notification is no longer queued.");
    if (doc.data["status"] === "sent") return ok({ sent: 0, failed: 0, skipped: true });

    if (!push.isConfigured) {
      // Honest behaviour: record that messaging is not configured rather than
      // pretending the notification was delivered.
      await deps.store.set(
        path,
        { status: "skipped", skippedReason: "messaging_not_configured", updatedAt: deps.store.now().toISOString() },
        { merge: true },
      );
      return ok({ sent: 0, failed: 0, skipped: true });
    }

    const tokens: string[] = [];
    for (const userId of entry.recipientUserIds) {
      const user = await deps.store.get(`users/${userId}`);
      if (!user) continue;
      const deviceTokens = Array.isArray(user.data["deviceTokens"]) ? (user.data["deviceTokens"] as unknown[]) : [];
      for (const deviceToken of deviceTokens) {
        if (typeof deviceToken === "object" && deviceToken !== null) {
          const token = (deviceToken as Record<string, unknown>)["token"];
          if (typeof token === "string" && token.length > 0) tokens.push(token);
        }
      }
    }

    if (tokens.length === 0) {
      await deps.store.set(path, { status: "skipped", skippedReason: "no_devices", updatedAt: deps.store.now().toISOString() }, { merge: true });
      return ok({ sent: 0, failed: 0, skipped: true });
    }

    const result = await push.send(tokens, { title: entry.title, body: entry.body, data: entry.data });

    if (result.invalidTokens.length > 0) {
      for (const userId of entry.recipientUserIds) {
        const user = await deps.store.get(`users/${userId}`);
        if (!user) continue;
        const deviceTokens = Array.isArray(user.data["deviceTokens"]) ? (user.data["deviceTokens"] as unknown[]) : [];
        const invalidHashes = new Set(result.invalidTokens.map((token) => stableHash(token)));
        const kept = deviceTokens.filter((deviceToken) => {
          if (typeof deviceToken !== "object" || deviceToken === null) return true;
          const hash = (deviceToken as Record<string, unknown>)["hash"];
          return typeof hash !== "string" || !invalidHashes.has(hash);
        });
        await deps.store.set(`users/${userId}`, { deviceTokens: kept, updatedAt: deps.store.now().toISOString() }, { merge: true });
      }
    }

    const attempts = typeof doc.data["attempts"] === "number" ? (doc.data["attempts"] as number) : 0;
    await deps.store.set(
      path,
      {
        status: result.successCount > 0 ? "sent" : "failed",
        attempts: attempts + 1,
        successCount: result.successCount,
        failureCount: result.failureCount,
        updatedAt: deps.store.now().toISOString(),
      },
      { merge: true },
    );

    await writeAuditLogSafely(deps.store, {
      tenantId: entry.tenantId,
      actorUserId: "system",
      action: "notifications.dispatched",
      resourceType: "notificationOutbox",
      resourceId: entry.id,
      metadata: { type: entry.type, successCount: result.successCount, failureCount: result.failureCount },
    });

    return ok({ sent: result.successCount, failed: result.failureCount, skipped: false });
  };
}
