/**
 * Builds the Admin-backed DataStore.
 *
 * Kept separate from the port implementation so `createAdminDataStore` stays a pure
 * adapter. A fresh store (and therefore a fresh clock read) is built per callable
 * invocation in src/index.ts, and handlers capture `store.now()` once when they need a
 * consistent instant for a whole operation.
 */

import type { Firestore } from "firebase-admin/firestore";
import { createAdminDataStore } from "./adminDataStore";
import type { DataStore } from "./types";

export function createAdminDataStoreForApp(db: Firestore, clock: () => Date = () => new Date()): DataStore {
  return createAdminDataStore(db, clock);
}
