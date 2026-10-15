/**
 * Validation primitives shared by every function.
 *
 * Client-side validation exists for a good user experience; THIS is the copy that
 * decides what gets stored. Every handler validates its input here first.
 */

import { z } from "zod";
import { errors } from "./errors";

export const minorSchema = z
  .number()
  .int("Amounts must be whole minor units (kobo)")
  .min(0, "Amounts must not be negative")
  .max(Number.MAX_SAFE_INTEGER);

export const nameSchema = z.string().trim().min(1, "A name is required").max(80);
export const shortTextSchema = z.string().trim().min(1).max(60);
export const longTextSchema = z.string().trim().min(1).max(1000);
export const notesSchema = z.string().trim().max(1000);
export const addressSchema = z.string().trim().min(3, "An address is required").max(240);
export const tenantIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9_-]+$/, "Invalid company identifier");

/** Nigerian numbers in local or international form; we store what the user typed. */
export const phoneSchema = z
  .string()
  .trim()
  .min(7, "A phone number is required")
  .max(24)
  .regex(/^\+?[0-9 ()-]+$/, "Phone numbers may only contain digits, spaces and + - ( )");

export const emailSchema = z.string().trim().toLowerCase().email("Enter a valid email address").max(160);

export const dateKeySchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Use the YYYY-MM-DD format")
  .refine((value) => !Number.isNaN(Date.parse(`${value}T00:00:00Z`)), "That date does not exist");

export const isoInstantSchema = z
  .string()
  .refine((value) => !Number.isNaN(Date.parse(value)), "Use an ISO-8601 date-time");

export const currencySchema = z.string().length(3).regex(/^[A-Z]{3}$/, "Use an ISO-4217 code such as NGN");

export const timezoneSchema = z
  .string()
  .min(3)
  .max(60)
  .refine((value) => {
    try {
      new Intl.DateTimeFormat("en-US", { timeZone: value });
      return true;
    } catch {
      return false;
    }
  }, "Unknown time zone");

export const zoneIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(40)
  .regex(/^[a-z0-9-]+$/, "Zone ids use lower-case letters, numbers and hyphens");

export const permissionKeySchema = z.string().max(60);

export const listQuerySchema = z.object({
  limit: z.number().int().min(1).max(100).optional(),
  cursor: z.array(z.unknown()).max(4).nullable().optional(),
  orderBy: z.string().max(40).optional(),
  direction: z.enum(["asc", "desc"]).optional(),
});

/** Runs a zod schema and converts failures into a typed backend error. */
export function parseWith<T extends z.ZodTypeAny>(
  schema: T,
  value: unknown,
  params: { detail: string; userMessage: string },
): z.infer<T> {
  const result = schema.safeParse(value);
  if (!result.success) {
    const fields: Record<string, string> = {};
    for (const issue of result.error.issues) {
      const path = issue.path.join(".") || "value";
      if (!fields[path]) fields[path] = issue.message;
    }
    throw errors.invalidArgument(params.detail, params.userMessage, fields);
  }
  return result.data;
}

export function requireObject(value: unknown, detail: string, userMessage: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw errors.invalidArgument(detail, userMessage);
  }
  return value as Record<string, unknown>;
}

/** Trims and collapses whitespace; used for human-entered free text. */
export function normalizeWhitespace(value: string): string {
  return value.replace(/\s+/g, " ").trim();
}

/** Two-letter uppercase country code list is not needed yet; keep the field bounded. */
export const countryCodeSchema = z.string().length(2).regex(/^[A-Z]{2}$/);

export const coordinatesSchema = z.object({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
});

export const locationInputSchema = z.object({
  address: addressSchema,
  zoneId: zoneIdSchema.nullable().optional(),
  latitude: z.number().min(-90).max(90).nullable().optional(),
  longitude: z.number().min(-180).max(180).nullable().optional(),
  /**
   * Dispatcher-supplied road distance in metres, allowed only when the routing
   * provider cannot answer (spec §8) and explicitly marked as manual.
   */
  manualDistanceMetres: z.number().int().min(1).max(2_000_000).nullable().optional(),
  contactName: z.string().trim().max(80).optional(),
  contactPhone: phoneSchema.optional(),
  notes: notesSchema.optional(),
});
