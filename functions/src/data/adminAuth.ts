/**
 * Narrow port over Firebase Authentication's administrative API.
 *
 * Only custom claims are managed through it (platform administrator authority), and
 * it is a port so the handler can be tested without an emulator.
 */

export interface AdminAuthPort {
  setCustomUserClaims(uid: string, claims: Record<string, unknown> | null): Promise<void>;
  getUser(uid: string): Promise<{ uid: string; email: string | null; emailVerified: boolean; customClaims: Record<string, unknown> } | null>;
  revokeRefreshTokens(uid: string): Promise<void>;
}
