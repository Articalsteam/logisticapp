/**
 * Pricing configuration validation tests.
 *
 * Two goals:
 *   1. a company can never save configuration that would make the engine produce a
 *      nonsense or dividing-by-zero price;
 *   2. the platform carries no hardcoded business rules for any particular customer.
 *      Nigeria-shaped *defaults* are allowed and useful; Nigeria-only *logic* is not.
 *      The second describe block enforces that by scanning the source.
 */

import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { parsePricingConfig, validatePricingConfigRelations } from "./validate";
import { starterPricingConfig } from "../config/platformDefaults";
import type { PricingConfig } from "./types";
import { DEFAULT_CURRENCY } from "../config/platformDefaults";

function raw(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    name: "Standard pricing",
    isActive: true,
    currency: "NGN",
    method: "base_plus_distance",
    baseChargeMinor: 70_000,
    perKmRateMinor: 15_000,
    distanceBands: [],
    zoneRules: [],
    fallbackToDistanceWhenZoneMissing: true,
    minimumChargeMinor: 80_000,
    maximumChargeMinor: null,
    roundingIncrementMinor: 0,
    roundingMode: "nearest",
    surcharges: [],
    riderCost: { method: "fixed", defaultFeeMinor: 50_000 },
    targetProfitAmountMinor: null,
    targetProfitMarginBps: null,
    ...overrides,
  };
}

const context = { tenantId: "mx-logistics", id: "config-1", version: 2, currency: "NGN" };

function parse(overrides: Record<string, unknown> = {}): PricingConfig {
  return parsePricingConfig(raw(overrides), context);
}

async function expectInvalid(overrides: Record<string, unknown>, detail: string): Promise<void> {
  try {
    parse(overrides);
  } catch (error) {
    expect((error as { detail?: string }).detail).toBe(detail);
    return;
  }
  throw new Error(`Expected ${detail}`);
}

describe("shape validation", () => {
  it("accepts a complete configuration and keeps the tenant context", () => {
    const config = parse();
    expect(config.tenantId).toBe("mx-logistics");
    expect(config.id).toBe("config-1");
    expect(config.version).toBe(2); // the backend controls the version
    expect(config.currency).toBe("NGN");
  });

  it("rejects fractional money, at any depth", () => {
    expect(() => parse({ baseChargeMinor: 700.5 })).toThrow();
    expect(() => parse({ minimumChargeMinor: 0.1 })).toThrow();
    expect(() =>
      parse({ distanceBands: [{ upToMetres: 5_000, chargeMinor: 1_000.75 }] }),
    ).toThrow();
    expect(() => parse({ riderCost: { method: "fixed", defaultFeeMinor: 500.5 } })).toThrow();
  });

  it("rejects negative money", () => {
    expect(() => parse({ perKmRateMinor: -1 })).toThrow();
  });

  it("rejects an unknown pricing method instead of silently falling back", () => {
    expect(() => parse({ method: "vibes" })).toThrow();
  });

  it("rejects a currency that is not an ISO-4217 code", () => {
    expect(() => parse({ currency: "naira" })).toThrow();
  });

  it("rejects a service level that does not exist", () => {
    expect(() => parse({ surcharges: [{ id: "x", label: "X", kind: "flat", amountMinor: 100, appliesToServiceLevels: ["teleport"] }] })).toThrow();
  });
});

describe("relationship validation", () => {
  it("rejects a maximum below the minimum", async () => {
    await expectInvalid({ minimumChargeMinor: 100_000, maximumChargeMinor: 90_000 }, "pricing.maximum_below_minimum");
  });

  it("rejects a target margin of 100% or more at the shape layer", async () => {
    await expectInvalid({ targetProfitMarginBps: 10_000 }, "pricing.invalid_shape");
    expect(() => parse({ targetProfitMarginBps: 9_999 })).not.toThrow();
  });

  it("also rejects an impossible margin at the relationship layer (defence in depth)", () => {
    const built = { ...parse(), targetProfitMarginBps: 12_000 };
    expect(() => validatePricingConfigRelations(built)).toThrow(/pricing\.margin_too_high/);
  });

  it("rejects an absurd rounding increment", async () => {
    await expectInvalid({ roundingIncrementMinor: 100_001 }, "pricing.rounding_too_large");
  });

  it("requires the inputs each pricing method actually needs", async () => {
    await expectInvalid({ method: "zone_based", zoneRules: [] }, "pricing.zone_rules_required");
    await expectInvalid({ method: "distance_bands", distanceBands: [] }, "pricing.bands_required");
    await expectInvalid({ method: "base_plus_distance", perKmRateMinor: 0 }, "pricing.per_km_rate_required");
  });

  it("requires the inputs each rider-costing method needs", async () => {
    await expectInvalid(
      { riderCost: { method: "distance_rate" } },
      "pricing.rider_rate_required",
    );
    await expectInvalid(
      { riderCost: { method: "distance_bands", bands: [] } },
      "pricing.rider_bands_required",
    );
    await expectInvalid(
      { riderCost: { method: "zone_based", zoneRules: [] } },
      "pricing.rider_zones_required",
    );
    await expectInvalid({ riderCost: { method: "fixed" } }, "pricing.rider_default_fee_required");
    await expectInvalid(
      { riderCost: { method: "historical_average" } },
      "pricing.rider_history_required",
    );
  });

  it("rejects a flat surcharge with no amount and a percentage surcharge with no rate", async () => {
    await expectInvalid(
      { surcharges: [{ id: "fuel", label: "Fuel", kind: "flat", amountMinor: 0 }] },
      "pricing.surcharge_amount_required:fuel",
    );
    await expectInvalid(
      { surcharges: [{ id: "fuel", label: "Fuel", kind: "percentage", percentageBps: 0 }] },
      "pricing.surcharge_percentage_required:fuel",
    );
  });
});

describe("distance bands", () => {
  const band = (upToMetres: number | null, chargeMinor: number) => ({ upToMetres, chargeMinor });

  it("accepts a complete ladder with one catch-all", () => {
    const config = parse({
      method: "distance_bands",
      distanceBands: [band(3_000, 100_000), band(7_000, 160_000), band(null, 300_000)],
    });
    expect(config.distanceBands).toHaveLength(3);
  });

  it("rejects two catch-all bands", async () => {
    await expectInvalid(
      { method: "distance_bands", distanceBands: [band(null, 100_000), band(null, 200_000)] },
      "pricing.distanceBands.multiple_catch_all",
    );
  });

  it("rejects duplicate band bounds", async () => {
    await expectInvalid(
      { method: "distance_bands", distanceBands: [band(5_000, 100_000), band(5_000, 200_000), band(null, 300_000)] },
      "pricing.distanceBands.duplicate_bounds",
    );
  });

  it("rejects a band that ends at zero distance", () => {
    expect(() => parse({ method: "distance_bands", distanceBands: [band(0, 100_000), band(null, 300_000)] })).toThrow();
  });

  it("allows a single open-ended band, which covers every distance", () => {
    expect(() => parse({ method: "distance_bands", distanceBands: [band(null, 250_000)] })).not.toThrow();
  });
});

describe("semantic checks called directly", () => {
  it("accepts a configuration the app saved", () => {
    expect(() => validatePricingConfigRelations(parse())).not.toThrow();
  });
});

describe("no hardcoded customer rules (ADR-003, spec §2)", () => {
  const sourceRoot = resolve(__dirname, "..");
  const files: string[] = [];
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith(".ts") && !entry.name.endsWith(".test.ts")) files.push(path);
    }
  };
  walk(sourceRoot);

  it("scans a meaningful number of source files", () => {
    expect(files.length).toBeGreaterThan(20);
  });

  it("never mentions the pilot customer's name in engine code", () => {
    const offenders = files.filter((file) => /mx\s*logistics|mxlogistics/i.test(readFileSync(file, "utf8")));
    expect(offenders).toEqual([]);
  });

  it("keeps tenant-specific values out of the engine and out of defaults", () => {
    // Prices belong in a company's pricing configuration, never in code that every
    // tenant shares. These are the only money defaults the platform ships, and they
    // exist so a brand-new workspace has something sane to edit.
    const config = starterPricingConfig({ tenantId: "any-tenant", id: "config-1" });
    expect(config.tenantId).toBe("any-tenant");
    expect(config.currency).toBe(DEFAULT_CURRENCY);
    expect(config.baseChargeMinor).toBeGreaterThan(0);
    expect(config.riderCost.method).toBeTruthy();
  });

  it("produces a configuration any tenant can override completely", () => {
    const ghana = starterPricingConfig({ tenantId: "accra-couriers", id: "config-2", currency: "GHS", baseChargeMinor: 2_500 });
    expect(ghana.currency).toBe("GHS");
    expect(ghana.baseChargeMinor).toBe(2_500);
    expect(ghana.tenantId).toBe("accra-couriers");
  });

  it("seeds every new company with the same rule set, so no customer is special-cased", () => {
    const first = starterPricingConfig({ tenantId: "tenant-a", id: "config-a" });
    const second = starterPricingConfig({ tenantId: "tenant-b", id: "config-b" });
    const { tenantId: _a, id: _b, ...firstRules } = first;
    const { tenantId: _c, id: _d, ...secondRules } = second;
    expect(firstRules).toEqual(secondRules);
  });
});
