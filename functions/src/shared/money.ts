/**
 * Money in Articals Logistics.
 *
 * DECISION (see docs/ARCHITECTURE_DECISIONS.md ADR-007): every monetary amount in
 * Firestore, in Cloud Functions payloads and in the Kotlin shared code is stored
 * as an **integer count of minor units** — kobo for NGN. There is no floating
 * point money anywhere in the system.
 *
 *   ₦1,250.50  ->  125050  (kobo)
 *
 * Rounding policy: intermediate products (for example rate-per-km × metres) are
 * computed with BigInt so they are exact, then rounded **once** using the
 * configured rounding rule, at the step documented in the pricing pipeline. The
 * helpers below never round implicitly.
 */

/** Integer amount in minor units (kobo for NGN). Always a safe integer. */
export type Minor = number;

export class MoneyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MoneyError";
  }
}

function assertSafeInteger(value: number, label: string): Minor {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new MoneyError(`${label} must be a finite number, received ${String(value)}`);
  }
  if (!Number.isInteger(value)) {
    throw new MoneyError(`${label} must be an integer number of minor units, received ${value}`);
  }
  if (!Number.isSafeInteger(value)) {
    throw new MoneyError(`${label} exceeds the safe integer range: ${value}`);
  }
  return value;
}

/** Validates that a value is a non-negative integer amount in minor units. */
export function assertMinor(value: unknown, label: string): Minor {
  if (typeof value !== "number") {
    throw new MoneyError(`${label} must be an integer amount in minor units (kobo)`);
  }
  const checked = assertSafeInteger(value, label);
  if (checked < 0) {
    throw new MoneyError(`${label} must not be negative, received ${checked}`);
  }
  return checked;
}

/** Validates an amount that may legitimately be negative (adjustments, refunds). */
export function assertSignedMinor(value: unknown, label: string): Minor {
  if (typeof value !== "number") {
    throw new MoneyError(`${label} must be an integer amount in minor units (kobo)`);
  }
  return assertSafeInteger(value, label);
}

export function isMinor(value: unknown): value is Minor {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function addMinor(...amounts: Minor[]): Minor {
  let total = 0n;
  for (const amount of amounts) {
    total += BigInt(assertMinor(amount, "amount"));
  }
  return fromBigInt(total, "sum of minor units");
}

export function subMinor(a: Minor, b: Minor): Minor {
  return fromBigInt(BigInt(assertMinor(a, "a")) - BigInt(assertMinor(b, "b")), "difference of minor units");
}

/**
 * Exact multiplication of a money amount by a rational factor, rounded half-up
 * to the nearest minor unit.
 *
 * Used for `rate per kilometre × distance` where distance is kept in whole
 * metres: `scale(minor, metres, 1000)`.
 */
export function scaleMinor(amount: Minor, numerator: number, denominator: number): Minor {
  if (!Number.isInteger(numerator)) {
    throw new MoneyError(`numerator must be an integer, received ${numerator}`);
  }
  if (!Number.isInteger(denominator) || denominator <= 0) {
    throw new MoneyError(`denominator must be a positive integer, received ${denominator}`);
  }
  const base = BigInt(assertMinor(amount, "amount"));
  const product = base * BigInt(numerator);
  return fromBigInt(divRoundHalfUp(product, BigInt(denominator)), "scaled minor units");
}

/**
 * Basis points helper. 10000 bps = 100%.
 * Percentages are stored in bps so that no float ever touches money.
 */
export function percentageMinor(amount: Minor, bps: number): Minor {
  if (!Number.isInteger(bps) || bps < 0) {
    throw new MoneyError(`percentage must be a non-negative integer in basis points, received ${bps}`);
  }
  return scaleMinor(amount, bps, 10_000);
}

export function clampMinor(value: Minor, min: Minor, max: Minor | null): Minor {
  const withMin = Math.max(assertMinor(value, "value"), assertMinor(min, "min"));
  if (max === null) return withMin;
  return Math.min(withMin, assertMinor(max, "max"));
}

export type RoundingMode = "nearest" | "up" | "down";

/**
 * Rounds an amount to the nearest multiple of `incrementMinor`.
 * A rounding increment of 0 or 1 means "no rounding".
 * `mode: "nearest"` rounds half-up, which is the default accounting behaviour.
 */
export function roundToIncrement(amount: Minor, incrementMinor: Minor, mode: RoundingMode = "nearest"): Minor {
  const amountChecked = assertSignedMinor(amount, "amount");
  const increment = assertMinor(incrementMinor, "roundingIncrement");
  if (increment <= 1) return amountChecked;

  const step = BigInt(increment);
  const value = BigInt(amountChecked);
  const remainder = ((value % step) + step) % step;
  if (remainder === 0n) return amountChecked;

  const lower = value - remainder;
  const upper = lower + step;
  switch (mode) {
    case "down":
      return fromBigInt(lower, "rounded amount");
    case "up":
      return fromBigInt(upper, "rounded amount");
    case "nearest":
    default:
      return fromBigInt(remainder * 2n >= step ? upper : lower, "rounded amount");
  }
}

/**
 * Profit margin in basis points. `part` and `whole` are minor units.
 * Returns 0 when `whole` is zero instead of dividing by zero, and signals that
 * case through `marginBps.isDefined` semantics at the call site (see pricing engine).
 */
export function marginBps(part: Minor, whole: Minor): number {
  const wholeValue = assertMinor(whole, "whole");
  if (wholeValue === 0) return 0;
  const numerator = BigInt(assertSignedMinor(part, "part")) * 10_000n;
  const result = divRoundHalfUp(numerator, BigInt(wholeValue));
  return fromBigInt(result, "margin");
}

/** Converts a major-unit decimal string ("1250.50") to minor units, exactly. */
export function majorStringToMinor(input: string): Minor {
  // Tolerates what a person actually types or pastes: thousands separators, a
  // currency symbol ("₦1,250.50"), a leading/trailing sign and stray spaces.
  const trimmed = input
    .replace(/[\s,]/g, "")
    .replace(/^[^\d+\-.]*/, "") // leading currency symbol(s), e.g. ₦ or GH₵
    .replace(/[^\d.]+$/, ""); // trailing currency code, e.g. "1250.50 NGN"
  const match = /^(-)?(\d+)(?:\.(\d{1,2}))?$/.exec(trimmed);
  if (!match) {
    throw new MoneyError(`"${input}" is not a valid amount with at most two decimal places`);
  }
  const [, sign, whole, fraction = ""] = match;
  const fractionPadded = (fraction + "00").slice(0, 2);
  const total = BigInt(whole ?? "0") * 100n + BigInt(fractionPadded);
  const signed = sign === "-" ? -total : total;
  return fromBigInt(signed, "amount");
}

/** Formats minor units as a human-readable amount, e.g. 125050 -> "1,250.50". */
export function minorToMajorString(amount: Minor, options: { withSign?: boolean } = {}): string {
  const value = assertSignedMinor(amount, "amount");
  const negative = value < 0;
  const absolute = Math.abs(value);
  const whole = Math.trunc(absolute / 100);
  const fraction = absolute % 100;
  const wholeText = whole.toLocaleString("en-NG", { maximumFractionDigits: 0 });
  const sign = negative ? "-" : options.withSign ? "+" : "";
  return `${sign}${wholeText}.${fraction.toString().padStart(2, "0")}`;
}

const CURRENCY_SYMBOLS: Record<string, string> = {
  NGN: "₦",
  USD: "$",
  GBP: "£",
  EUR: "€",
  GHS: "GH₵",
  KES: "KSh",
};

export function currencySymbol(currency: string): string {
  return CURRENCY_SYMBOLS[currency.toUpperCase()] ?? currency.toUpperCase();
}

/** Display helper: 125050 NGN -> "₦1,250.50". Never use this to compute. */
export function formatMinor(amount: Minor, currency = "NGN"): string {
  return `${currencySymbol(currency)}${minorToMajorString(amount)}`;
}

function divRoundHalfUp(numerator: bigint, denominator: bigint): bigint {
  if (denominator === 0n) throw new MoneyError("division by zero");
  const negative = numerator < 0n !== denominator < 0n;
  const absNumerator = numerator < 0n ? -numerator : numerator;
  const absDenominator = denominator < 0n ? -denominator : denominator;
  const quotient = (absNumerator * 2n + absDenominator) / (absDenominator * 2n);
  return negative ? -quotient : quotient;
}

function fromBigInt(value: bigint, label: string): Minor {
  if (value > BigInt(Number.MAX_SAFE_INTEGER) || value < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new MoneyError(`${label} is out of range: ${value.toString()}`);
  }
  return Number(value);
}
