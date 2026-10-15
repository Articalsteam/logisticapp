/**
 * Distance resolution.
 *
 * One place decides what distance (if any) a quote may use:
 *   1. the configured routing provider, when addresses or coordinates are available;
 *   2. an explicitly entered manual distance, clearly marked as such;
 *   3. nothing at all — the dispatcher is asked to correct the location.
 *
 * Straight-line distance is never substituted (spec §8): a wrong distance is worse
 * than no distance, because it produces a confident wrong price.
 */

import { errors } from "../shared/errors";
import type { QuoteDistance } from "../pricing/types";
import { RoutingError, type RouteEndpoint, type RoutingProvider } from "./types";
import type { GeoPoint } from "./types";

export interface ResolveDistanceInput {
  pickup: { address: string; latitude?: number | null; longitude?: number | null; manualDistanceMetres?: number | null };
  destination: { address: string; latitude?: number | null; longitude?: number | null };
  /** When true, a provider failure is reported instead of silently falling back. */
  allowManualFallback: boolean;
}

export interface ResolveDistanceOutcome {
  distance: QuoteDistance | null;
  /** Set when the provider failed but a manual distance was supplied instead. */
  fallbackReason?: string;
}

function toEndpoint(location: { address: string; latitude?: number | null; longitude?: number | null }): RouteEndpoint {
  const hasCoordinates = typeof location.latitude === "number" && typeof location.longitude === "number";
  const coordinates: GeoPoint | undefined = hasCoordinates
    ? { latitude: location.latitude as number, longitude: location.longitude as number }
    : undefined;
  return coordinates ? { coordinates, address: location.address } : { address: location.address };
}

export async function resolveDistance(
  routing: RoutingProvider,
  input: ResolveDistanceInput,
  now: Date,
  regionCode: string,
): Promise<ResolveDistanceOutcome> {
  const manualMetres = input.pickup.manualDistanceMetres ?? null;
  const hasManual = typeof manualMetres === "number" && manualMetres > 0;

  if (!routing.isConfigured) {
    return {
      distance: hasManual ? manual(manualMetres, now) : null,
      ...(hasManual ? { fallbackReason: "routing_provider_not_configured" } : {}),
    };
  }

  try {
    const result = await routing.calculateRoute({
      origin: toEndpoint(input.pickup),
      destination: toEndpoint(input.destination),
      regionCode,
    });
    return {
      distance: {
        metres: result.metres,
        source: "provider",
        provider: result.provider,
        ...(result.summary ? { summary: result.summary } : {}),
        ...(result.durationSeconds !== undefined ? { durationSeconds: result.durationSeconds } : {}),
        calculatedAt: now.toISOString(),
        ...(result.originCoordinates ? { pickupCoordinates: result.originCoordinates } : {}),
        ...(result.destinationCoordinates ? { destinationCoordinates: result.destinationCoordinates } : {}),
      },
    };
  } catch (error) {
    const routingError = error instanceof RoutingError ? error : null;
    if (hasManual && input.allowManualFallback) {
      return {
        distance: manual(manualMetres, now),
        fallbackReason: routingError?.code ?? "provider_error",
      };
    }
    throw errors.providerUnavailable(
      `routing_${routingError?.code ?? "unknown"}`,
      routingError?.userMessage ??
        "We could not calculate the road distance for those locations. Check the addresses and try again.",
      { provider: routing.id },
    );
  }
}

function manual(metres: number, now: Date): QuoteDistance {
  return {
    metres: Math.round(metres),
    source: "manual",
    provider: "manual",
    calculatedAt: now.toISOString(),
  };
}
