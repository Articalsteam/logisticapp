/**
 * Routing provider implementations.
 *
 * Server-side keys live only in Cloud Functions secrets (spec §8):
 *   firebase functions:secrets:set GOOGLE_ROUTES_API_KEY
 *   firebase functions:secrets:set MAPBOX_ACCESS_TOKEN
 * They are injected as environment variables at runtime and are never sent to the
 * app, never stored in Firestore, and never logged.
 */

import {
  ROUTING_TIMEOUT_MS,
  RoutingError,
  type GeoPoint,
  type RouteEndpoint,
  type RouteRequest,
  type RouteResult,
  type RoutingProvider,
} from "./types";

async function fetchJson(url: string, init: RequestInit & { provider: string }): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ROUTING_TIMEOUT_MS);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    if (response.status === 429) {
      throw new RoutingError(
        "rate_limited",
        `${init.provider} rate limited the request`,
        "The mapping service is busy right now. Try again in a moment.",
      );
    }
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      throw new RoutingError(
        "provider_error",
        `${init.provider} responded ${response.status}: ${body.slice(0, 300)}`,
        "The mapping service could not calculate this route. Check the addresses and try again.",
      );
    }
    return (await response.json()) as unknown;
  } catch (error) {
    if (error instanceof RoutingError) throw error;
    if (error instanceof Error && error.name === "AbortError") {
      throw new RoutingError("timeout", `${init.provider} timed out`, "The mapping service took too long to respond. Try again.");
    }
    throw new RoutingError(
      "provider_error",
      `${init.provider} request failed: ${error instanceof Error ? error.message : String(error)}`,
      "We could not reach the mapping service. Check your connection and try again.",
    );
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// Google Routes API
// ---------------------------------------------------------------------------

/**
 * Google Routes API (routes.googleapis.com/directions/v2:computeRoutes).
 * https://developers.google.com/maps/documentation/routes
 * The request accepts either an address or coordinates for each waypoint.
 */
export function createGoogleRoutesProvider(apiKey: string | null): RoutingProvider {
  return {
    id: "google_routes",
    isConfigured: typeof apiKey === "string" && apiKey.length > 0,
    async calculateRoute(request: RouteRequest): Promise<RouteResult> {
      if (!apiKey) {
        throw new RoutingError(
          "not_configured",
          "GOOGLE_ROUTES_API_KEY is not configured",
          "Road distances are not switched on yet. Ask the dispatcher to record the distance manually.",
        );
      }
      const toWaypoint = (endpoint: RouteEndpoint) =>
        endpoint.coordinates
          ? { location: { latLng: { latitude: endpoint.coordinates.latitude, longitude: endpoint.coordinates.longitude } } }
          : { address: endpoint.address };

      if (!request.origin.coordinates && !request.origin.address) {
        throw new RoutingError("invalid_address", "origin missing", "Enter a pickup address or drop a pin on the map.");
      }

      const payload = {
        origin: toWaypoint(request.origin),
        destination: toWaypoint(request.destination),
        travelMode: "DRIVE",
        routingPreference: "TRAFFIC_UNAWARE",
        ...(request.regionCode ? { regionCode: request.regionCode } : {}),
      };

      const body = (await fetchJson("https://routes.googleapis.com/directions/v2:computeRoutes", {
        method: "POST",
        provider: "Google Routes",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": apiKey,
          "X-Goog-FieldMask": "routes.distanceMeters,routes.duration,routes.description,routes.legs.startLocation,routes.legs.endLocation",
        },
        body: JSON.stringify(payload),
      })) as {
        routes?: Array<{
          distanceMeters?: number;
          duration?: string;
          description?: string;
          legs?: Array<{ startLocation?: { latLng?: { latitude: number; longitude: number } }; endLocation?: { latLng?: { latitude: number; longitude: number } } }>;
        }>;
      };

      const route = body.routes?.[0];
      if (!route?.distanceMeters) {
        throw new RoutingError(
          "no_route",
          "Google Routes returned no route",
          "Google could not find a drivable route between those locations. Check the addresses or pin the points on the map.",
        );
      }
      const leg = route.legs?.[0];
      const durationSeconds = route.duration ? Number.parseFloat(route.duration.replace("s", "")) : undefined;
      return {
        metres: Math.round(route.distanceMeters),
        durationSeconds: Number.isFinite(durationSeconds) ? Math.round(durationSeconds as number) : undefined,
        summary: route.description,
        provider: "google_routes",
        originCoordinates: leg?.startLocation?.latLng as GeoPoint | undefined,
        destinationCoordinates: leg?.endLocation?.latLng as GeoPoint | undefined,
      };
    },
  };
}

// ---------------------------------------------------------------------------
// Mapbox Directions API
// ---------------------------------------------------------------------------

interface MapboxGeocodeResponse {
  features?: Array<{ center?: [number, number]; place_name?: string }>;
}

interface MapboxDirectionsResponse {
  routes?: Array<{ distance?: number; duration?: number; weight_name?: string }>;
  message?: string;
}

export function createMapboxProvider(accessToken: string | null): RoutingProvider {
  return {
    id: "mapbox_directions",
    isConfigured: typeof accessToken === "string" && accessToken.length > 0,
    async calculateRoute(request: RouteRequest): Promise<RouteResult> {
      if (!accessToken) {
        throw new RoutingError(
          "not_configured",
          "MAPBOX_ACCESS_TOKEN is not configured",
          "Road distances are not switched on yet. Ask the dispatcher to record the distance manually.",
        );
      }

      const geocode = async (endpoint: RouteEndpoint, label: string): Promise<GeoPoint> => {
        if (endpoint.coordinates) return endpoint.coordinates;
        if (!endpoint.address) {
          throw new RoutingError("invalid_address", `${label} missing`, `Enter a ${label} address or drop a pin on the map.`);
        }
        const url = new URL(`https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(endpoint.address)}.json`);
        url.searchParams.set("access_token", accessToken);
        url.searchParams.set("limit", "1");
        if (request.regionCode) url.searchParams.set("country", request.regionCode.toLowerCase());
        const body = (await fetchJson(url.toString(), { provider: "Mapbox Geocoding" })) as MapboxGeocodeResponse;
        const center = body.features?.[0]?.center;
        if (!center) {
          throw new RoutingError(
            "invalid_address",
            `Mapbox could not geocode the ${label} address`,
            `We could not find that ${label} address. Try a more specific address or pick the point on the map.`,
          );
        }
        return { longitude: center[0], latitude: center[1] };
      };

      const origin = await geocode(request.origin, "pickup");
      const destination = await geocode(request.destination, "destination");

      const url = new URL(
        `https://api.mapbox.com/directions/v5/mapbox/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}`,
      );
      url.searchParams.set("access_token", accessToken);
      url.searchParams.set("overview", "false");
      const body = (await fetchJson(url.toString(), { provider: "Mapbox Directions" })) as MapboxDirectionsResponse;
      const route = body.routes?.[0];
      if (!route?.distance) {
        throw new RoutingError(
          "no_route",
          `Mapbox returned no route: ${body.message ?? "unknown reason"}`,
          "No drivable route could be found between those locations. Check the addresses or pin the points on the map.",
        );
      }
      return {
        metres: Math.round(route.distance),
        durationSeconds: route.duration ? Math.round(route.duration) : undefined,
        provider: "mapbox_directions",
        originCoordinates: origin,
        destinationCoordinates: destination,
      };
    },
  };
}

// ---------------------------------------------------------------------------
// Manual-only
// ---------------------------------------------------------------------------

/**
 * Used when no routing provider is configured. It never fabricates a distance: the
 * dispatcher must enter one, clearly marked as manual, and every quote/order that
 * uses it carries `distance.source === "manual"` (spec §8).
 */
export function createManualOnlyProvider(): RoutingProvider {
  return {
    id: "manual_only",
    isConfigured: false,
    async calculateRoute(): Promise<RouteResult> {
      throw new RoutingError(
        "not_configured",
        "No routing provider is configured",
        "Road distance calculation is not switched on. Enter the distance manually if you know it.",
      );
    },
  };
}
