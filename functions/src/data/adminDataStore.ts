/**
 * Firebase Admin implementation of the DataStore port.
 *
 * This is the only file in the codebase that imports firebase-admin's Firestore.
 * Cloud Functions run with the Admin SDK, which BYPASSES Firestore security rules
 * — which is exactly why every handler independently checks authentication,
 * membership and permission through src/shared/auth.ts.
 */

import type { DocumentData, Firestore, Query } from "firebase-admin/firestore";
import { errors } from "../shared/errors";
import {
  type DataStore,
  type DocData,
  type QuerySpec,
  type StoredDoc,
  type TransactionContext,
  collectionPath,
} from "./types";

function toStored(path: string, id: string, data: DocumentData | undefined): StoredDoc {
  return { id, path, data: (data ?? {}) as DocData };
}

function idFromPath(path: string): string {
  const segments = path.split("/");
  const id = segments[segments.length - 1];
  if (!id) throw new Error(`Cannot derive an id from path ${path}`);
  return id;
}

function applyQuery(query: Query<DocumentData>, spec: QuerySpec | undefined): Query<DocumentData> {
  let result = query;
  for (const clause of spec?.where ?? []) {
    result = result.where(clause.field, clause.op as never, clause.value as never);
  }
  const orderBy = spec?.orderBy ?? [];
  for (const clause of orderBy) {
    result = result.orderBy(clause.field, clause.direction ?? "asc");
  }
  if (spec?.cursor && spec.cursor.length > 0) {
    result = result.startAfter(...spec.cursor);
  }
  if (spec?.limit && spec.limit > 0) {
    result = result.limit(spec.limit);
  }
  return result;
}

export function createAdminDataStore(db: Firestore, clock: () => Date = () => new Date()): DataStore {
  const store: DataStore = {
    now: clock,

    newId(path: string): string {
      return db.collection(collectionPath(path)).doc().id;
    },

    async get(path: string): Promise<StoredDoc | null> {
      const snapshot = await db.doc(path).get();
      if (!snapshot.exists) return null;
      return toStored(path, snapshot.id, snapshot.data());
    },

    async query(path: string, spec?: QuerySpec): Promise<StoredDoc[]> {
      const snapshot = await applyQuery(db.collection(collectionPath(path)), spec).get();
      return snapshot.docs.map((document) => toStored(document.ref.path, document.id, document.data()));
    },

    async count(path: string, spec?: QuerySpec): Promise<number> {
      const aggregate = await applyQuery(db.collection(collectionPath(path)), spec).count().get();
      return aggregate.data().count;
    },

    async set(path: string, data: DocData, options?: { merge?: boolean }): Promise<void> {
      await db.doc(path).set(data as DocumentData, { merge: options?.merge ?? false });
    },

    async create(path: string, data: DocData): Promise<void> {
      try {
        await db.doc(path).create(data as DocumentData);
      } catch (error) {
        if (isAlreadyExists(error)) {
          throw errors.alreadyExists(`doc_exists:${path.split("/").slice(0, 1).join("")}`, "That record already exists.");
        }
        throw error;
      }
    },

    async update(path: string, data: DocData): Promise<void> {
      try {
        await db.doc(path).update(data as DocumentData);
      } catch (error) {
        if (isNotFound(error)) {
          throw errors.notFound(`doc_missing:${path}`);
        }
        throw error;
      }
    },

    async delete(path: string): Promise<void> {
      await db.doc(path).delete();
    },

    async runTransaction<T>(fn: (tx: TransactionContext) => Promise<T>): Promise<T> {
      return db.runTransaction(async (transaction) => {
        const tx: TransactionContext = {
          async get(path) {
            const snapshot = await transaction.get(db.doc(path));
            if (!snapshot.exists) return null;
            return toStored(path, snapshot.id, snapshot.data());
          },
          async getQuery(path, spec) {
            const snapshot = await transaction.get(applyQuery(db.collection(collectionPath(path)), spec));
            return snapshot.docs.map((document) => toStored(document.ref.path, document.id, document.data()));
          },
          set(path, data, options) {
            transaction.set(db.doc(path), data as DocumentData, { merge: options?.merge ?? false });
          },
          create(path, data) {
            transaction.create(db.doc(path), data as DocumentData);
          },
          update(path, data) {
            transaction.update(db.doc(path), data as DocumentData);
          },
          delete(path) {
            transaction.delete(db.doc(path));
          },
        };
        return fn(tx);
      });
    },
  };

  return store;
}

function isAlreadyExists(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: number }).code === 6;
}

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: number }).code === 5;
}

export { idFromPath };
