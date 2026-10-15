/**
 * Handler dependencies and the shared handler shape.
 *
 * Handlers are plain async functions of `(input, context, deps)`. The Firebase
 * wrapper in src/index.ts is the only place that knows about `onCall`, so every
 * handler can be exercised by the unit tests through an in-memory DataStore.
 */

import type { DataStore } from "../data/types";
import type { RuntimeConfig } from "../config/runtimeConfig";
import type { RoutingProvider } from "../routing/types";
import type { CallContext } from "../shared/auth";

export type LogFields = Record<string, unknown>;

export interface HandlerDeps {
  store: DataStore;
  routing: RoutingProvider;
  config: RuntimeConfig;
  /** Structured logging that never includes secrets (see shared/audit.ts). */
  log: (event: string, fields?: LogFields) => void;
}

export type Handler<TInput, TOutput> = (input: TInput, context: CallContext, deps: HandlerDeps) => Promise<TOutput>;

/** Standard structured result envelope returned by every callable. */
export interface OperationResult<T> {
  ok: true;
  data: T;
  /** True when a retried request replayed an earlier successful result. */
  replayed?: boolean;
}

export function ok<T>(data: T, replayed?: boolean): OperationResult<T> {
  return replayed === undefined ? { ok: true, data } : { ok: true, data, replayed };
}

export function createDeps(params: {
  store: DataStore;
  config: RuntimeConfig;
  log?: (event: string, fields?: LogFields) => void;
}): HandlerDeps {
  return {
    store: params.store,
    routing: params.config.routing,
    config: params.config,
    log: params.log ?? (() => undefined),
  };
}
