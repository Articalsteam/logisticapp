/**
 * Runtime configuration.
 *
 * Secrets are read from the environment that Cloud Functions secrets populate:
 *   GOOGLE_ROUTES_API_KEY, MAPBOX_ACCESS_TOKEN
 * Nothing here is ever shipped to the mobile app.
 */

import {
  createGoogleRoutesProvider,
  createManualOnlyProvider,
  createMapboxProvider,
} from "../routing/providers";
import { ROUTING_PROVIDER_IDS, type RoutingProvider, type RoutingProviderId } from "../routing/types";

export interface TrackingRateLimitConfig {
  limit: number;
  windowSeconds: number;
}

export interface RuntimeConfig {
  environment: "development" | "production";
  projectId: string;
  routingProviderId: RoutingProviderId;
  routing: RoutingProvider;
  /** ISO alpha-2 region hint passed to the routing provider. */
  routingRegionCode: string;
  publicTrackingBaseUrl: string;
  publicMarketingUrl: string;
  trackingRateLimit: TrackingRateLimitConfig;
  /** True when App Check tokens must be present for public endpoints. */
  enforceAppCheck: boolean;
}

function intFromEnv(value: string | undefined, fallback: number): number {
  const parsed = value === undefined ? Number.NaN : Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadRuntimeConfig(env: NodeJS.ProcessEnv = process.env): RuntimeConfig {
  const projectId = env.GCLOUD_PROJECT ?? env.GOOGLE_CLOUD_PROJECT ?? "demo-articals";
  const environment: RuntimeConfig["environment"] =
    env.ARTICALS_ENV === "production" || projectId.endsWith("-prod") ? "production" : "development";

  const requested = (env.ROUTING_PROVIDER ?? "").trim();
  const providerId: RoutingProviderId = (ROUTING_PROVIDER_IDS as readonly string[]).includes(requested)
    ? (requested as RoutingProviderId)
    : env.GOOGLE_ROUTES_API_KEY
      ? "google_routes"
      : env.MAPBOX_ACCESS_TOKEN
        ? "mapbox_directions"
        : "manual_only";

  const routing =
    providerId === "google_routes"
      ? createGoogleRoutesProvider(env.GOOGLE_ROUTES_API_KEY ?? env.ROUTING_API_KEY ?? null)
      : providerId === "mapbox_directions"
        ? createMapboxProvider(env.MAPBOX_ACCESS_TOKEN ?? null)
        : createManualOnlyProvider();

  return {
    environment,
    projectId,
    routingProviderId: providerId,
    routing,
    routingRegionCode: env.ROUTING_REGION_CODE ?? "NG",
    publicTrackingBaseUrl: env.PUBLIC_TRACKING_BASE_URL ?? "https://track.articals.example",
    publicMarketingUrl: env.PUBLIC_MARKETING_URL ?? "https://articals.example",
    trackingRateLimit: {
      limit: intFromEnv(env.TRACKING_RATE_LIMIT_PER_WINDOW, 30),
      windowSeconds: intFromEnv(env.TRACKING_RATE_LIMIT_WINDOW_SECONDS, 600),
    },
    enforceAppCheck: env.ENFORCE_APP_CHECK === "true" || environment === "production",
  };
}

export function trackingUrl(config: RuntimeConfig, trackingReference: string): string {
  return `${config.publicTrackingBaseUrl.replace(/\/$/, "")}/track.html?ref=${encodeURIComponent(trackingReference)}`;
}
