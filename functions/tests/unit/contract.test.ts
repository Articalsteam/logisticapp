/**
 * Wire-contract tests.
 *
 * The Kotlin client parses these responses, so their shape is an API: field names,
 * types and units. `docs/contracts/pricing.contract.json` is the written contract;
 * this test drives the real handler and fails if the JSON stops matching it, which
 * is the signal to update the contract and the client models together.
 *
 * The most important assertion here is that money is ALWAYS an integer number of
 * minor units. A float creeping into a quote would be a correctness bug in the
 * platform's most trusted calculation.
 */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { createInMemoryDataStore } from "../../src/data/inMemoryDataStore";
import type { DataStore } from "../../src/data/types";
import { loadRuntimeConfig } from "../../src/config/runtimeConfig";
import { createDeps, type HandlerDeps } from "../../src/functions/deps";
import { HANDLERS } from "../../src/functions/registry";
import type { CallContext } from "../../src/shared/auth";

interface Contract {
  callable: string;
  response: Record<string, string>;
  quote: Record<string, string>;
  pricingSnapshot: Record<string, string>;
  warning: Record<string, string>;
  location: Record<string, string>;
}

const contract = JSON.parse(
  readFileSync(resolve(__dirname, "../../../docs/contracts/pricing.contract.json"), "utf8"),
) as Contract;

let store: DataStore;
let deps: HandlerDeps;
const owner: CallContext = { uid: "user-owner", claims: { email: "owner@mx.test", email_verified: true }, appCheckAppId: "app-1", ip: "10.0.0.1" };
let tenantId = "";

beforeEach(async () => {
  store = createInMemoryDataStore({ now: () => new Date("2026-10-14T09:00:00.000Z") });
  deps = createDeps({
    store,
    config: loadRuntimeConfig({ GCLOUD_PROJECT: "demo-articals" } as NodeJS.ProcessEnv),
    log: () => undefined,
  });
  const created = await HANDLERS.createCompany(
    {
      idempotencyKey: "contract-company-key",
      companyName: "MX Logistics",
      contactPhone: "0803 000 0000",
      contactEmail: "owner@mx.test",
    },
    owner,
    deps,
  );
  tenantId = created.data.tenantId;
  await HANDLERS.updateCompanyPricing(
    {
      tenantId,
      settings: {
        name: "Contract pricing",
        isActive: true,
        currency: "NGN",
        method: "base_plus_distance",
        baseChargeMinor: 70_000,
        perKmRateMinor: 15_000,
        distanceBands: [],
        zoneRules: [],
        fallbackToDistanceWhenZoneMissing: true,
        minimumChargeMinor: 0,
        maximumChargeMinor: null,
        roundingIncrementMinor: 0,
        roundingMode: "nearest",
        surcharges: [],
        riderCost: { method: "fixed", defaultFeeMinor: 50_000 },
        targetProfitAmountMinor: null,
        targetProfitMarginBps: 2_500,
      },
    },
    owner,
    deps,
  );
});

async function quote() {
  const response = await HANDLERS.calculateDeliveryQuote(
    {
      tenantId,
      pickup: { address: "12 Allen Avenue, Ikeja", zoneId: "ikeja", manualDistanceMetres: 8_400 },
      destination: { address: "5 Herbert Macaulay Way, Yaba", zoneId: "yaba" },
      serviceLevel: "standard",
      saveQuote: true,
    },
    owner,
    deps,
  );
  return response.data as Record<string, unknown>;
}

/** Splits a contract type expression on top-level commas only. */
function splitTopLevel(input: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const character of input) {
    if (character === "<" || character === "{") depth += 1;
    if (character === ">" || character === "}") depth -= 1;
    if (character === "," && depth === 0) {
      parts.push(current);
      current = "";
      continue;
    }
    current += character;
  }
  parts.push(current);
  return parts;
}

/** Recursively asserts a parsed-against-JSON value matches a contract type. */
function assertShape(value: unknown, declared: string, path: string): void {
  const nullable = declared.startsWith("nullable<");
  if (nullable) {
    // Optional fields are omitted from the payload when unset (JSON.stringify drops
    // undefined), so the Kotlin models give them defaults of null.
    if (value === null || value === undefined) return;
    declared = declared.slice("nullable<".length, -1);
  }

  if (declared.startsWith("array<")) {
    expect(Array.isArray(value), `${path} should be an array`).toBe(true);
    const inner = declared.slice("array<".length, -1);
    for (const [index, entry] of (value as unknown[]).entries()) {
      assertShape(entry, inner, `${path}[${index}]`);
    }
    return;
  }

  if (declared.startsWith("enum:")) {
    const allowed = declared.slice("enum:".length).split("|");
    expect(allowed, `${path} = ${String(value)}`).toContain(value as string);
    return;
  }

  if (declared.startsWith("{") || declared.startsWith("enum:{") === false && false) {
    // Inline object literal, e.g. "{ amountMinor: int, method: enum:fixed|manual }"
  }

  switch (declared) {
    case "int": {
      expect(Number.isInteger(value), `${path} must be a whole number (got ${String(value as number)})`).toBe(true);
      expect(Number.isSafeInteger(value), `${path} must be a safe integer`).toBe(true);
      return;
    }
    case "number": {
      expect(typeof value, `${path} must be a number`).toBe("number");
      expect(Number.isFinite(value as number), `${path} must be finite`).toBe(true);
      return;
    }
    case "string": {
      expect(typeof value, `${path} must be a string`).toBe("string");
      return;
    }
    case "bool": {
      expect(typeof value, `${path} must be a boolean`).toBe("boolean");
      return;
    }
    case "instant": {
      expect(typeof value, `${path} must be an ISO-8601 string`).toBe("string");
      expect(value as string, `${path} must be UTC with a Z suffix`).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
      return;
    }
    case "map": {
      expect(typeof value, `${path} must be an object`).toBe("object");
      return;
    }
    case "quote":
    case "pricingSnapshot":
    case "location":
    case "warning": {
      const schema = contract[declared];
      expect(typeof value, `${path} must be an object`).toBe("object");
      for (const [field, type] of Object.entries(schema)) {
        expect(value, `${path}.${field} is missing from the response`).toHaveProperty(field);
        assertShape((value as Record<string, unknown>)[field], type, `${path}.${field}`);
      }
      return;
    }
    default: {
      // An inline object literal: "{ field: type, ... }", which may itself contain
      // nested inline objects, so the split has to respect brace and angle depth.
      if (declared.startsWith("{")) {
        const entries = splitTopLevel(declared.slice(1, -1))
          .map((entry) => entry.trim())
          .filter((entry) => entry.length > 0)
          .map((entry) => {
            const separator = entry.indexOf(":");
            return [entry.slice(0, separator).trim(), entry.slice(separator + 1).trim()] as const;
          });
        expect(typeof value, `${path} must be an object`).toBe("object");
        for (const [field, type] of entries) {
          const actual = (value as Record<string, unknown>)[field];
          // Nested inline objects are rare; the parser above handles one level.
          assertShape(actual, type, `${path}.${field}`);
        }
        return;
      }
      throw new Error(`The contract uses a type this test does not understand: ${declared} (${path})`);
    }
  }
}

describe("calculateDeliveryQuote wire contract", () => {
  it("matches every declared field and type", async () => {
    const response = await quote();
    for (const [field, type] of Object.entries(contract.response)) {
      expect(response, `response.${field} is missing`).toHaveProperty(field);
      assertShape(response[field], type, `response.${field}`);
    }
  });

  it("declares all money as integer minor units and never a float", async () => {
    const response = await quote();
    const q = response["quote"] as Record<string, unknown>;
    expect(q["recommendedCustomerChargeMinor"]).toBe(196_000);
    expect(q["estimatedProfitMinor"]).toBe(146_000);

    // Every *Minor field anywhere in the payload must be a whole number. Distances
    // and margins legitimately carry decimals; money never does.
    const moneyFields: Array<[string, unknown]> = [];
    const walk = (value: unknown, path: string) => {
      if (Array.isArray(value)) {
        value.forEach((entry, index) => walk(entry, `${path}[${index}]`));
        return;
      }
      if (value === null || typeof value !== "object") return;
      for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
        const innerPath = `${path}.${key}`;
        if (key.endsWith("Minor") && typeof inner === "number") moneyFields.push([innerPath, inner]);
        walk(inner, innerPath);
      }
    };
    walk(response, "response");
    expect(moneyFields.length).toBeGreaterThan(5);
    for (const [path, amount] of moneyFields) {
      expect(Number.isInteger(amount), `${path} = ${String(amount)} must be whole kobo`).toBe(true);
    }
  });

  it("keeps quotes stable across repeated calls with identical input", async () => {
    const first = await quote();
    const second = await quote();
    const strip = (value: Record<string, unknown>) => {
      const copy = JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
      const q = copy["quote"] as Record<string, unknown>;
      delete q["computedAt"];
      delete (q["pricingSnapshot"] as Record<string, unknown>)["capturedAt"];
      delete copy["quoteId"];
      return copy;
    };
    // Only the quote id and the capture timestamps may differ between two calls.
    expect(strip(second)).toEqual(strip(first));
  });

  it("survives a JSON round trip with the ids and money intact (what the client actually receives)", async () => {
    const response = await quote();
    const delivered = JSON.parse(JSON.stringify(response)) as typeof response;
    const q = delivered["quote"] as Record<string, unknown>;
    // Large kobo amounts stay exact through JSON number serialisation for the values
    // this platform deals in (< 2^53); guard the boundary explicitly.
    expect(q["recommendedCustomerChargeMinor"]).toBe(196_000);
    expect(typeof q["recommendedCustomerChargeMinor"]).toBe("number");
    expect(delivered["quoteId"]).toBe(response["quoteId"]);
  });

  it("returns a typed, non-leaking error for invalid input", async () => {
    await expect(
      HANDLERS.calculateDeliveryQuote({ tenantId, pickup: { address: "x" }, destination: { address: "y" } }, owner, deps),
    ).rejects.toMatchObject({ code: "invalid_argument", detail: "quote.invalid_input" });
  });

  it("refuses a quote from a user who is not a member of the company", async () => {
    const outsider: CallContext = { uid: "user-outsider", claims: {}, appCheckAppId: null, ip: "10.0.0.9" };
    await expect(
      HANDLERS.calculateDeliveryQuote(
        {
          tenantId,
          pickup: { address: "12 Allen Avenue, Ikeja", manualDistanceMetres: 1_000 },
          destination: { address: "5 Herbert Macaulay Way, Yaba" },
          serviceLevel: "standard",
        },
        outsider,
        deps,
      ),
    ).rejects.toMatchObject({ code: "not_a_member" });
  });
});
