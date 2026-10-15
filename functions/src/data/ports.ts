/**
 * Ports for the two Firebase products that are used through a narrow interface so
 * the handlers stay testable and so the emulator (or a future provider) can be
 * swapped in without touching business logic: file storage and push messaging.
 */

export interface StoredFile {
  path: string;
  sizeBytes: number;
  contentType: string;
}

export interface FileStoragePort {
  /** Writes a file, replacing any previous content at that path. */
  put(path: string, content: string | Uint8Array, contentType: string): Promise<StoredFile>;
  /**
   * Time-limited download URL for the authenticated owner of the file. Returns null
   * when the runtime cannot sign URLs (for example a local emulator without a
   * service account) so callers can fall back to the SDK with security rules.
   */
  signedUrl(path: string, expiresInMinutes: number): Promise<string | null>;
  exists(path: string): Promise<boolean>;
}

export interface PushMessage {
  title: string;
  body: string;
  /** Minimal data payload: identifiers only, never customer details. */
  data: Record<string, string>;
}

export interface PushSendResult {
  successCount: number;
  failureCount: number;
  /** Tokens the provider rejected as unregistered, so they can be pruned. */
  invalidTokens: string[];
  /** Set when messaging is not configured in this environment. */
  skipped?: boolean;
}

export interface PushPort {
  readonly isConfigured: boolean;
  send(tokens: string[], message: PushMessage): Promise<PushSendResult>;
}

/** Storage that reports "not configured" instead of pretending a file was written. */
export function createUnavailableFileStorage(): FileStoragePort {
  return {
    async put(): Promise<StoredFile> {
      throw new Error("File storage is not configured in this environment");
    },
    async signedUrl(): Promise<string | null> {
      return null;
    },
    async exists(): Promise<boolean> {
      return false;
    },
  };
}
