/**
 * Time helpers.
 *
 * All instants are ISO-8601 UTC strings. Business dates ("which day does this
 * delivery belong to?") are computed in the *company's* timezone, which defaults
 * to Africa/Lagos — a dispatcher closing out the day at 00:30 must not see that
 * work land on tomorrow's report.
 */

export function toIso(instant: Date): string {
  return instant.toISOString();
}

export function parseIso(value: string): Date | null {
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : new Date(parsed);
}

export function isIsoInstant(value: unknown): value is string {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}

const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function isDateKey(value: unknown): value is string {
  if (typeof value !== "string" || !DATE_KEY_PATTERN.test(value)) return false;
  const parsed = parseDateKey(value);
  return parsed !== null && toDateKey(parsed) === value;
}

export function parseDateKey(value: string): Date | null {
  if (!DATE_KEY_PATTERN.test(value)) return null;
  const [year, month, day] = value.split("-").map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (Number.isNaN(date.getTime())) return null;
  return date;
}

export function toDateKey(instant: Date): string {
  return instant.toISOString().slice(0, 10);
}

/** Milliseconds the given timezone is ahead of UTC at that instant. */
export function zoneOffsetMs(instant: Date, timeZone: string): number {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  const parts: Record<string, string> = {};
  for (const part of formatter.formatToParts(instant)) parts[part.type] = part.value;
  const hour = Number(parts.hour) === 24 ? 0 : Number(parts.hour);
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    hour,
    Number(parts.minute),
    Number(parts.second),
  );
  return asUtc - instant.getTime();
}

/** The calendar day an instant falls on, in the company's timezone. */
export function businessDate(instant: Date, timeZone: string): string {
  const offset = zoneOffsetMs(instant, timeZone);
  return toDateKey(new Date(instant.getTime() + offset));
}

/** The UTC instant at which a business day starts in the company's timezone. */
export function startOfBusinessDay(dateKey: string, timeZone: string): Date {
  const parsed = parseDateKey(dateKey);
  if (!parsed) throw new Error(`Invalid date key: ${dateKey}`);
  const guess = parsed.getTime();
  // Two-pass correction handles DST-style offset changes around midnight.
  let offset = zoneOffsetMs(new Date(guess), timeZone);
  let instant = new Date(guess - offset);
  offset = zoneOffsetMs(instant, timeZone);
  instant = new Date(guess - offset);
  return instant;
}

/** Inclusive business day range as ISO instants: [start, endExclusive). */
export function businessDayRange(fromDateKey: string, toDateKeyValue: string, timeZone: string): { startIso: string; endExclusiveIso: string } {
  const start = startOfBusinessDay(fromDateKey, timeZone);
  const end = startOfBusinessDay(addDays(toDateKeyValue, 1), timeZone);
  return { startIso: start.toISOString(), endExclusiveIso: end.toISOString() };
}

export function addDays(dateKey: string, days: number): string {
  const parsed = parseDateKey(dateKey);
  if (!parsed) throw new Error(`Invalid date key: ${dateKey}`);
  return toDateKey(new Date(parsed.getTime() + days * 24 * 60 * 60 * 1000));
}

export function addDaysToInstant(instant: Date, days: number): Date {
  return new Date(instant.getTime() + days * 24 * 60 * 60 * 1000);
}

/** The last N business days ending at `dateKey` (inclusive), oldest first. */
export function recentDateKeys(dateKey: string, days: number): string[] {
  const keys: string[] = [];
  for (let offset = days - 1; offset >= 0; offset -= 1) keys.push(addDays(dateKey, -offset));
  return keys;
}

export function isoWeekKey(dateKey: string): string {
  const parsed = parseDateKey(dateKey);
  if (!parsed) throw new Error(`Invalid date key: ${dateKey}`);
  const target = new Date(Date.UTC(parsed.getUTCFullYear(), parsed.getUTCMonth(), parsed.getUTCDate()));
  const dayNumber = (target.getUTCDay() + 6) % 7;
  target.setUTCDate(target.getUTCDate() - dayNumber + 3);
  const firstThursday = new Date(Date.UTC(target.getUTCFullYear(), 0, 4));
  const firstDayNumber = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDayNumber + 3);
  const week = 1 + Math.round((target.getTime() - firstThursday.getTime()) / (7 * 24 * 60 * 60 * 1000));
  return `${target.getUTCFullYear()}-W${week.toString().padStart(2, "0")}`;
}
