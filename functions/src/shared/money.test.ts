/**
 * Money arithmetic tests.
 *
 * The whole platform stores money as an integer number of minor units (kobo) and
 * never as a floating-point number (ADR-007). These tests pin the exact rounding
 * behaviour of the helpers the pricing engine depends on, and prove that the
 * helpers refuse to work with a value that is not a whole number of kobo.
 */

import { describe, expect, it } from "vitest";
import {
  addMinor,
  assertMinor,
  assertSignedMinor,
  clampMinor,
  currencySymbol,
  formatMinor,
  isMinor,
  majorStringToMinor,
  marginBps,
  minorToMajorString,
  MoneyError,
  percentageMinor,
  roundToIncrement,
  scaleMinor,
  subMinor,
} from "./money";

describe("validation", () => {
  it("accepts whole kobo amounts", () => {
    expect(assertMinor(0, "amount")).toBe(0);
    expect(assertMinor(196_000, "amount")).toBe(196_000);
    expect(isMinor(1)).toBe(true);
    expect(isMinor(1.5)).toBe(false);
  });

  it("rejects fractions, NaN, infinities and unsafe integers", () => {
    for (const bad of [1.5, 0.1, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 53 + 1]) {
      expect(() => assertMinor(bad, "amount")).toThrow(MoneyError);
    }
    expect(() => assertMinor("100", "amount")).toThrow(/integer amount in minor units/);
  });

  it("rejects a negative amount where only a credit is allowed", () => {
    expect(() => assertMinor(-1, "amount")).toThrow(/negative/);
    // …but a signed helper exists for variances, which may legitimately be negative.
    expect(assertSignedMinor(-18_000, "variance")).toBe(-18_000);
  });
});

describe("addition and subtraction", () => {
  it("adds a list of amounts exactly", () => {
    expect(addMinor(196_000, 9_800, 10_000)).toBe(215_800);
    expect(addMinor()).toBe(0);
  });

  it("subtracts and allows a negative result (a loss is still a real number)", () => {
    expect(subMinor(196_000, 50_000)).toBe(146_000);
    expect(subMinor(40_000, 50_000)).toBe(-10_000);
  });
});

describe("scaling and percentages", () => {
  it("scales by a ratio with half-up rounding", () => {
    // ₦150 per km × 8,400 m
    expect(scaleMinor(15_000, 8_400, 1_000)).toBe(126_000);
    // ₦0.12 × 1 m → 0.12 kobo → 0
    expect(scaleMinor(12, 1, 1_000)).toBe(0);
    // ties round up: 5 kobo × 1 / 2 = 2.5 → 3
    expect(scaleMinor(5, 1, 2)).toBe(3);
    expect(scaleMinor(1, 1, 3)).toBe(0);
    expect(scaleMinor(2, 1, 3)).toBe(1);
  });

  it("computes basis-point percentages", () => {
    expect(percentageMinor(196_000, 500)).toBe(9_800); // 5%
    expect(percentageMinor(196_000, 2_500)).toBe(49_000); // 25%
    expect(percentageMinor(1, 5_000)).toBe(1); // 50% of 1 kobo rounds up to 1
  });

  it("clamps within an optional maximum", () => {
    expect(clampMinor(120_000, 80_000, 100_000)).toBe(100_000);
    expect(clampMinor(50_000, 80_000, 100_000)).toBe(80_000);
    expect(clampMinor(90_000, 80_000, null)).toBe(90_000);
  });
});

describe("rounding to an increment", () => {
  it("rounds to the nearest increment, half up", () => {
    expect(roundToIncrement(196_000, 5_000, "nearest")).toBe(195_000);
    expect(roundToIncrement(197_500, 5_000, "nearest")).toBe(200_000);
    expect(roundToIncrement(96_300, 5_000, "nearest")).toBe(95_000);
  });

  it("rounds up and down on request", () => {
    expect(roundToIncrement(96_300, 5_000, "up")).toBe(100_000);
    expect(roundToIncrement(96_300, 5_000, "down")).toBe(95_000);
  });

  it("does nothing for an increment of zero or one", () => {
    expect(roundToIncrement(96_345, 0, "nearest")).toBe(96_345);
    expect(roundToIncrement(96_345, 1, "nearest")).toBe(96_345);
  });

  it("rejects a negative or fractional increment", () => {
    expect(() => roundToIncrement(100, -5, "nearest")).toThrow(MoneyError);
    expect(() => roundToIncrement(100, 2.5, "nearest")).toThrow(MoneyError);
  });
});

describe("margins", () => {
  it("reports basis points, rounded half up", () => {
    expect(marginBps(146_000, 196_000)).toBe(7_449); // 74.49%
    expect(marginBps(25_000, 100_000)).toBe(2_500);
    expect(marginBps(-10_000, 100_000)).toBe(-1_000);
  });

  it("returns zero rather than dividing by zero", () => {
    expect(marginBps(0, 0)).toBe(0);
    expect(marginBps(5_000, 0)).toBe(0);
  });
});

describe("formatting", () => {
  it("parses what a human types into exact kobo", () => {
    expect(majorStringToMinor("1250.50")).toBe(125_050);
    expect(majorStringToMinor("1,250.50")).toBe(125_050);
    expect(majorStringToMinor("1250")).toBe(125_000);
    expect(majorStringToMinor("0.05")).toBe(5);
    expect(majorStringToMinor("-40.5")).toBe(-4_050);
    expect(majorStringToMinor("₦1,250.50")).toBe(125_050);
  });

  it("refuses more precision than a kobo instead of silently truncating", () => {
    expect(() => majorStringToMinor("12.345")).toThrow(MoneyError);
    expect(() => majorStringToMinor("abc")).toThrow(MoneyError);
    // A leading zero is required, which the amount field supplies as the user types.
    expect(() => majorStringToMinor(".5")).toThrow(MoneyError);
  });

  it("renders kobo as a human amount", () => {
    expect(minorToMajorString(125_050)).toBe("1,250.50");
    expect(minorToMajorString(0)).toBe("0.00");
    expect(minorToMajorString(-4_050)).toBe("-40.50");
    expect(minorToMajorString(125_050, { withSign: true })).toBe("+1,250.50");
  });

  it("formats with the currency symbol for the interface", () => {
    expect(formatMinor(125_050, "NGN")).toBe("₦1,250.50");
    expect(formatMinor(196_000, "NGN")).toBe("₦1,960.00");
    expect(currencySymbol("NGN")).toBe("₦");
    expect(currencySymbol("GHS")).toBe("GH₵");
    expect(currencySymbol("USD")).toBe("$");
    expect(currencySymbol("XOF")).toBe("XOF"); // unknown currency falls back to its code
  });
});

describe("precision guarantees", () => {
  it("keeps a large amount exact (no float drift) across many operations", () => {
    let total = 0;
    for (let index = 0; index < 1_000; index += 1) {
      total = addMinor(total, 12_345);
    }
    expect(total).toBe(12_345_000);
    expect(Number.isInteger(total)).toBe(true);
  });

  it("states the largest representable amount the platform accepts", () => {
    expect(Number.MAX_SAFE_INTEGER).toBe(9_007_199_254_740_991);
    // Far beyond any plausible kobo amount: ₦90 trillion.
    expect(assertMinor(90_000_000_000_000_00, "amount")).toBe(90_000_000_000_000_00);
  });
});
