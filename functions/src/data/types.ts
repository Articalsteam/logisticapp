/**
 * Data-access port.
 *
 * Every Cloud Function talks to Firestore through this narrow interface instead of
 * the firebase-admin SDK directly. Three things fall out of that:
 *
 *  1. handlers are unit-testable without an emulator (see inMemoryDataStore.ts);
 *  2. authorization helpers cannot accidentally bypass a check by reaching for a
 *     raw Firestore handle;
 *  3. the persistence shape (ISO-8601 UTC strings for timestamps, integer minor
 *     units for money) is enforced in one place.
 *
 * TIMESTAMPS: the backend writes ISO-8601 UTC strings (ADR-011). They sort
 * lexicographically exactly as they sort chronologically, they are unambiguous in
 * Firestore rules, and they map onto kotlinx-datetime on the client without a
 * conversion layer. Backend code always takes time from `store.now()` so that a
 * single request uses a single clock.
 */

export type DocData = Record<string, unknown>;

export interface StoredDoc {
  /** Document id (last path segment). */
  id: string;
  /** Full path, e.g. "companies/acme/orders/ord-1". */
  path: string;
  data: DocData;
}

export type WhereOperator = "==" | "!=" | "<" | "<=" | ">" | ">=" | "in" | "array-contains";

export interface WhereClause {
  field: string;
  op: WhereOperator;
  value: unknown;
}

export interface OrderByClause {
  field: string;
  direction?: "asc" | "desc";
}

export interface QuerySpec {
  where?: WhereClause[];
  orderBy?: OrderByClause[];
  limit?: number;
  /**
   * Cursor pagination: the values of the `orderBy` fields of the last document
   * from the previous page. Only forward cursors are used.
   */
  cursor?: unknown[] | null;
}

/**
 * Writes staged inside a transaction. Firestore requires all reads before any
 * write, so handlers must call `get`/`getQuery` first.
 */
export interface TransactionContext {
  get(path: string): Promise<StoredDoc | null>;
  getQuery(collectionPath: string, spec?: QuerySpec): Promise<StoredDoc[]>;
  set(path: string, data: DocData, options?: { merge?: boolean }): void;
  /** Fails with an `already_exists` BackendError when the document exists. */
  create(path: string, data: DocData): void;
  update(path: string, data: DocData): void;
  delete(path: string): void;
}

export interface DataStore {
  /** The request clock. */
  now(): Date;
  /** Server-generated id. Never a client-supplied id. */
  newId(collectionPath: string): string;
  get(path: string): Promise<StoredDoc | null>;
  query(collectionPath: string, spec?: QuerySpec): Promise<StoredDoc[]>;
  count(collectionPath: string, spec?: QuerySpec): Promise<number>;
  set(path: string, data: DocData, options?: { merge?: boolean }): Promise<void>;
  create(path: string, data: DocData): Promise<void>;
  update(path: string, data: DocData): Promise<void>;
  delete(path: string): Promise<void>;
  runTransaction<T>(fn: (tx: TransactionContext) => Promise<T>): Promise<T>;
}

/** Builds a path from segments, rejecting empty segments early. */
export function docPath(...segments: string[]): string {
  if (segments.length === 0 || segments.length % 2 !== 0) {
    throw new Error(`A Firestore document path needs an even number of segments, received ${segments.length}`);
  }
  if (segments.some((segment) => segment.length === 0 || segment.includes("/"))) {
    throw new Error(`Invalid path segment in ${segments.join("/")}`);
  }
  return segments.join("/");
}

export function collectionPath(...segments: string[]): string {
  if (segments.length === 0 || segments.length % 2 !== 1) {
    throw new Error(`A Firestore collection path needs an odd number of segments, received ${segments.length}`);
  }
  if (segments.some((segment) => segment.length === 0 || segment.includes("/"))) {
    throw new Error(`Invalid path segment in ${segments.join("/")}`);
  }
  return segments.join("/");
}

/** Applies a `merge`-style shallow update with explicit null deletion. */
export function mergeDocs(current: DocData | null, patch: DocData): DocData {
  const result: DocData = { ...(current ?? {}) };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) continue;
    result[key] = value;
  }
  return result;
}
