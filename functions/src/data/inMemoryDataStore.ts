/**
 * In-memory DataStore used by the unit tests.
 *
 * It implements the same semantics as the Firestore adapter for the operations the
 * handlers rely on: read-your-writes inside transactions, `create` failing on an
 * existing document, ordering, filtering, cursor pagination and aggregate counts.
 * It is deliberately NOT a general Firestore emulator — it only supports what the
 * port exposes, which keeps the fake small enough to trust.
 */

import { errors } from "../shared/errors";
import {
  type DataStore,
  type DocData,
  type OrderByClause,
  type QuerySpec,
  type StoredDoc,
  type TransactionContext,
  type WhereClause,
} from "./types";

interface Entry {
  path: string;
  data: DocData;
}

function compareValues(a: unknown, b: unknown): number {
  if (a === b) return 0;
  if (a === null || a === undefined) return -1;
  if (b === null || b === undefined) return 1;
  if (typeof a === "number" && typeof b === "number") return a - b;
  if (typeof a === "boolean" && typeof b === "boolean") return a === b ? 0 : a ? 1 : -1;
  const left = String(a);
  const right = String(b);
  return left < right ? -1 : left > right ? 1 : 0;
}

function matchesWhere(data: DocData, clause: WhereClause): boolean {
  const value = data[clause.field];
  switch (clause.op) {
    case "==":
      return value === clause.value;
    case "!=":
      return value !== clause.value;
    case "<":
      return compareValues(value, clause.value) < 0;
    case "<=":
      return compareValues(value, clause.value) <= 0;
    case ">":
      return compareValues(value, clause.value) > 0;
    case ">=":
      return compareValues(value, clause.value) >= 0;
    case "in":
      return Array.isArray(clause.value) && (clause.value as unknown[]).includes(value);
    case "array-contains":
      return Array.isArray(value) && (value as unknown[]).includes(clause.value);
    default:
      throw new Error(`Unsupported where operator: ${String(clause.op)}`);
  }
}

function sortDocs(docs: StoredDoc[], orderBy: OrderByClause[]): StoredDoc[] {
  if (orderBy.length === 0) return docs;
  return [...docs].sort((left, right) => {
    for (const clause of orderBy) {
      const comparison = compareValues(left.data[clause.field], right.data[clause.field]);
      if (comparison !== 0) return clause.direction === "desc" ? -comparison : comparison;
    }
    return compareValues(left.id, right.id);
  });
}

function isAfterCursor(doc: StoredDoc, orderBy: OrderByClause[], cursor: unknown[]): boolean {
  // Forward cursor: keep documents whose ordering tuple is strictly greater than
  // the cursor's, comparing lexicographically on the orderBy fields.
  for (let index = 0; index < orderBy.length; index += 1) {
    const clause = orderBy[index];
    if (!clause) continue;
    const comparison = compareValues(doc.data[clause.field], cursor[index]);
    if (comparison === 0) continue;
    return clause.direction === "desc" ? comparison < 0 : comparison > 0;
  }
  return false;
}

export interface InMemoryDataStoreOptions {
  now?: () => Date;
  /** Deterministic ids in tests: idGenerator("orders") -> "orders-1", ... */
  idGenerator?: (collectionPath: string) => string;
}

export function createInMemoryDataStore(options: InMemoryDataStoreOptions = {}): DataStore {
  const entries = new Map<string, Entry>();
  const clock = options.now ?? (() => new Date());
  let idCounter = 0;
  const idGenerator =
    options.idGenerator ?? ((path: string) => `${path.replace(/\//g, "_")}_${(idCounter += 1).toString().padStart(4, "0")}`);

  const directChildren = (collection: string): StoredDoc[] => {
    const prefix = `${collection}/`;
    const result: StoredDoc[] = [];
    for (const [path, entry] of entries) {
      if (!path.startsWith(prefix)) continue;
      const remainder = path.slice(prefix.length);
      if (remainder.includes("/")) continue; // nested subcollections are not direct children
      result.push({ id: path.slice(path.lastIndexOf("/") + 1), path, data: entry.data });
    }
    return result;
  };

  const select = (collection: string, spec?: QuerySpec): StoredDoc[] => {
    let docs = directChildren(collection);
    for (const clause of spec?.where ?? []) {
      docs = docs.filter((doc) => matchesWhere(doc.data, clause));
    }
    const orderBy = spec?.orderBy ?? [];
    docs = sortDocs(docs, orderBy);
    if (spec?.cursor && spec.cursor.length > 0) {
      docs = docs.filter((doc) => isAfterCursor(doc, orderBy, spec.cursor as unknown[]));
    }
    if (spec?.limit && spec.limit > 0) {
      docs = docs.slice(0, spec.limit);
    }
    return docs;
  };

  const applyWrites = (writes: Array<() => void>): void => {
    for (const write of writes) write();
  };

  // Serialises transactions so concurrent handlers cannot interleave their reads.
  let transactionQueue: Promise<unknown> = Promise.resolve();

  const store: DataStore = {
    now: clock,
    newId(collection) {
      return idGenerator(collection);
    },
    async get(path) {
      const entry = entries.get(path);
      return entry ? { id: path.slice(path.lastIndexOf("/") + 1), path, data: { ...entry.data } } : null;
    },
    async query(collection, spec) {
      return select(collection, spec).map((doc) => ({ ...doc, data: { ...doc.data } }));
    },
    async count(collection, spec) {
      return select(collection, spec).length;
    },
    async set(path, data, options) {
      const existing = entries.get(path);
      const next = options?.merge && existing ? { ...existing.data, ...data } : { ...data };
      entries.set(path, { path, data: next });
    },
    async create(path, data) {
      if (entries.has(path)) {
        throw errors.alreadyExists("doc_exists", "That record already exists.");
      }
      entries.set(path, { path, data: { ...data } });
    },
    async update(path, data) {
      const existing = entries.get(path);
      if (!existing) throw errors.notFound("doc_missing");
      entries.set(path, { path, data: { ...existing.data, ...data } });
    },
    async delete(path) {
      entries.delete(path);
    },
    async runTransaction<T>(fn: (tx: TransactionContext) => Promise<T>): Promise<T> {
      const run = async (): Promise<T> => {
        const overlay = new Map<string, Entry | null>();
        const pendingWrites: Array<() => void> = [];

        const read = (path: string): Entry | null => {
          if (overlay.has(path)) return overlay.get(path) ?? null;
          return entries.get(path) ?? null;
        };

        const tx: TransactionContext = {
          async get(path) {
            const entry = read(path);
            return entry ? { id: path.slice(path.lastIndexOf("/") + 1), path, data: { ...entry.data } } : null;
          },
          async getQuery(collection, spec) {
            // Reads see committed data plus this transaction's pending overlay.
            const committed = select(collection, spec);
            const overlayDocs = [...overlay.entries()]
              .filter(([, entry]) => entry && entry.path.startsWith(`${collection}/`))
              .map(([path, entry]) => ({ id: path.slice(path.lastIndexOf("/") + 1), path, data: entry!.data }));
            const merged = new Map<string, StoredDoc>();
            for (const doc of [...committed, ...overlayDocs]) merged.set(doc.path, doc);
            let docs = [...merged.values()];
            for (const clause of spec?.where ?? []) docs = docs.filter((doc) => matchesWhere(doc.data, clause));
            docs = sortDocs(docs, spec?.orderBy ?? []);
            if (spec?.limit && spec.limit > 0) docs = docs.slice(0, spec.limit);
            return docs.map((doc) => ({ ...doc, data: { ...doc.data } }));
          },
          set(path, data, options) {
            const current = read(path);
            const next = options?.merge && current ? { ...current.data, ...data } : { ...data };
            overlay.set(path, { path, data: next });
            pendingWrites.push(() => entries.set(path, { path, data: next }));
          },
          create(path, data) {
            if (read(path)) throw errors.alreadyExists("doc_exists", "That record already exists.");
            overlay.set(path, { path, data: { ...data } });
            pendingWrites.push(() => {
              if (entries.has(path)) throw errors.alreadyExists("doc_exists", "That record already exists.");
              entries.set(path, { path, data: { ...data } });
            });
          },
          update(path, data) {
            const current = read(path);
            if (!current) throw errors.notFound("doc_missing");
            const next = { ...current.data, ...data };
            overlay.set(path, { path, data: next });
            pendingWrites.push(() => entries.set(path, { path, data: next }));
          },
          delete(path) {
            overlay.set(path, null);
            pendingWrites.push(() => entries.delete(path));
          },
        };

        const result = await fn(tx);
        applyWrites(pendingWrites);
        return result;
      };

      const queued = transactionQueue.then(run);
      // Keep the queue alive even when a transaction fails.
      transactionQueue = queued.then(
        () => undefined,
        () => undefined,
      );
      return queued;
    },
  };

  return store;
}
