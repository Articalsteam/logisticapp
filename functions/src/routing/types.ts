/**
 * Routing / mapping provider abstraction (ADR-006).
 *
 * The company can change mapping provider without touching any calling code. Only
 * road distance from a real provider (or an explicitly labelled manual distance) is
 * ever used for pricing — straight-line distance is never substituted silently.
 */

export const ROUTING_PROVIDER_IDS = ["google_routes", "mapbox_directions", "manual_only"] as const;
export type RoutingProviderId = (typeof ROUTING_PROVIDER_IDS)[number];

export interface GeoPoint {
  latitude: number;
  longitude: number;
}

export interface RouteEndpoint {
  address?: string;
  coordinates?: GeoPoint;
}

export interface RouteRequest {
  origin: RouteEndpoint;
  destination: RouteEndpoint;
  /** ISO-3166 alpha-2 region hint, e.g. "NG". */
  regionCode?: string;
}

export interface RouteResult {
  metres: number;
  durationSeconds?: number;
  summary?: string;
  provider: RoutingProviderId;
  originCoordinates?: GeoPoint;
  destinationCoordinates?: GeoPoint;
}

export type RoutingErrorCode =
  | "not_configured"
  | "invalid_address"
  | "no_route"
  | "timeout"
  | "rate_limited"
  | "provider_error";

export class RoutingError extends Error {
  readonly code: RoutingErrorCode;
  /** Safe to display in the app. */
  readonly userMessage: string;

  constructor(code: RoutingErrorCode, message: string, userMessage: string) {
    super(message);
    this.name = "RoutingError";
    this.code = code;
    this.userMessage = userMessage;
  }
}

export interface RoutingProvider {
  readonly id: RoutingProviderId;
  /** False when the provider has no credentials configured in this environment. */
  readonly isConfigured: boolean;
  calculateRoute(request: RouteRequest): Promise<RouteResult>;
}

export const ROUTING_TIMEOUT_MS = 8_000;
