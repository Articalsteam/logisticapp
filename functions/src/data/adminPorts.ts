/**
 * Firebase Admin implementations of the testable ports.
 *
 * All three live behind interfaces so that handlers never import firebase-admin
 * directly, and so the unit tests can substitute fakes.
 */

import { getApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import type { AdminAuthPort } from "./adminAuth";
import type { FileStoragePort, PushMessage, PushPort, PushSendResult, StoredFile } from "./ports";

export function createAdminAuthPort(): AdminAuthPort {
  const auth = getAuth(getApp());
  return {
    async setCustomUserClaims(uid, claims) {
      await auth.setCustomUserClaims(uid, claims);
    },
    async getUser(uid) {
      try {
        const user = await auth.getUser(uid);
        return {
          uid: user.uid,
          email: user.email ?? null,
          emailVerified: user.emailVerified,
          customClaims: (user.customClaims ?? {}) as Record<string, unknown>,
        };
      } catch {
        return null;
      }
    },
    async revokeRefreshTokens(uid) {
      await auth.revokeRefreshTokens(uid);
    },
  };
}

export function createAdminFileStorage(bucketName?: string): FileStoragePort {
  const bucket = bucketName ? getStorage(getApp()).bucket(bucketName) : getStorage(getApp()).bucket();
  return {
    async put(path, content, contentType): Promise<StoredFile> {
      const file = bucket.file(path);
      await file.save(typeof content === "string" ? Buffer.from(content, "utf8") : Buffer.from(content), {
        contentType,
        resumable: false,
      });
      const [metadata] = await file.getMetadata();
      return { path, sizeBytes: Number(metadata.size ?? 0), contentType: metadata.contentType ?? contentType };
    },
    async signedUrl(path, expiresInMinutes) {
      try {
        const [url] = await bucket.file(path).getSignedUrl({
          action: "read",
          expires: Date.now() + expiresInMinutes * 60 * 1000,
        });
        return url;
      } catch {
        // Local emulator without a signing service account, or missing permissions.
        // The app falls back to downloading through the SDK with security rules.
        return null;
      }
    },
    async exists(path) {
      const [exists] = await bucket.file(path).exists();
      return exists;
    },
  };
}

/**
 * FCM through the Admin SDK: FCM on Android, FCM through APNs on iOS. The platform
 * is recorded with the token but FCM routes to APNs transparently for iOS tokens.
 */
export function createAdminPush(): PushPort {
  let configured = true;
  try {
    getMessaging(getApp());
  } catch {
    configured = false;
  }
  return {
    isConfigured: configured,
    async send(tokens: string[], message: PushMessage): Promise<PushSendResult> {
      if (!configured) {
        return { successCount: 0, failureCount: 0, invalidTokens: [], skipped: true };
      }
      const response = await getMessaging(getApp()).sendEachForMulticast({
        tokens,
        notification: { title: message.title, body: message.body },
        data: message.data,
        android: { priority: "high", notification: { channelId: "articals_deliveries" } },
        apns: { payload: { aps: { sound: "default" } } },
      });
      const invalidTokens: string[] = [];
      response.responses.forEach((entry, index) => {
        const code = entry.error?.code;
        if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
          const token = tokens[index];
          if (token) invalidTokens.push(token);
        }
      });
      return {
        successCount: response.successCount,
        failureCount: response.failureCount,
        invalidTokens,
      };
    },
  };
}
