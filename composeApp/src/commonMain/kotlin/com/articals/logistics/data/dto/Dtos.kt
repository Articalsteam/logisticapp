package com.articals.logistics.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data transfer objects.
 *
 * These mirror exactly what the Cloud Functions return and what the Firestore
 * documents contain (camelCase, money as whole minor units, instants as ISO-8601 UTC
 * strings). The field names are part of the API contract with the backend — see
 * docs/contracts/pricing.contract.json for the quote callable, which is asserted
 * against the real handler in functions/tests/unit/contract.test.ts.
 *
 * Every field that the backend may omit is nullable or defaulted, because a missing
 * key must never crash the app: a client released today must keep working against a
 * backend deployed tomorrow.
 */

/** Every callable responds with this envelope: `{ ok, data, replayed? }`. */
@Serializable
data class Envelope<T>(
    val ok: Boolean = true,
    val data: T,
    val replayed: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Company, membership, subscription
// ---------------------------------------------------------------------------

@Serializable
data class CompanyDto(
    val id: String = "",
    val name: String = "",
    val slug: String? = null,
    val logoUrl: String? = null,
    val brandColor: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val currency: String = "NGN",
    val timezone: String = "Africa/Lagos",
    val serviceAreas: List<String> = emptyList(),
    val orderNumberPrefix: String = "ORD",
    val status: String = "active",
    val planId: String? = null,
    val activePricingConfigId: String? = null,
    val onboarding: OnboardingDto? = null,
    val publicTracking: PublicTrackingSettingsDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class OnboardingDto(
    val isComplete: Boolean = false,
    val completedSteps: List<String> = emptyList(),
    val startedAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class PublicTrackingSettingsDto(
    val shareAreas: Boolean = true,
    val requireVerification: Boolean = false,
)

@Serializable
data class MembershipDto(
    val id: String = "",
    val tenantId: String = "",
    val userId: String = "",
    val role: String = "dispatcher",
    val status: String = "active",
    val displayName: String? = null,
    val email: String? = null,
    val grantedPermissions: List<String> = emptyList(),
    val revokedPermissions: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class BypassViewDto(val active: Boolean = false, val until: String? = null, val reason: String? = null)

@Serializable
data class OfflinePaymentViewDto(
    val amountMinor: Long = 0,
    val currency: String = "NGN",
    val method: String = "bank_transfer",
    val receivedAt: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
)

/** Exactly what `getMySubscription` returns — company-visible billing facts only. */
@Serializable
data class SubscriptionViewDto(
    val planId: String = "pilot",
    val planName: String = "",
    val planPriceMinor: Long = 0,
    val planCurrency: String = "NGN",
    val status: String = "trialing",
    val billingMode: String = "trial",
    val entitlement: String = "trialing",
    val entitlementEffectiveUntil: String? = null,
    val entitlementLabel: String = "",
    val trialEndsAt: String? = null,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val bypass: BypassViewDto? = null,
    val lastOfflinePayment: OfflinePaymentViewDto? = null,
    val daysRemaining: Int? = null,
    val writesAllowed: Boolean = true,
    /** Honest labelling: "Free trial", "Recorded offline by Articals Studio", … */
    val billingNotice: String? = null,
)

@Serializable
data class PlanDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val priceMinor: Long = 0,
    val currency: String = "NGN",
    val periodDays: Int = 30,
    val trialDays: Int = 14,
    val features: List<String> = emptyList(),
    val isActive: Boolean = true,
)

@Serializable
data class EntitlementPolicyDto(
    val trialDays: Int = 30,
    val graceDays: Int = 7,
    val bypassMaxDays: Int = 90,
    val standardPeriodDays: Int = 30,
)

// ---------------------------------------------------------------------------
// Pricing
// ---------------------------------------------------------------------------

@Serializable
data class DistanceBandDto(
    val upToMetres: Long? = null,
    val chargeMinor: Long = 0,
    val label: String? = null,
)

@Serializable
data class ZoneRuleDto(
    val fromZoneId: String = "",
    val toZoneId: String = "",
    val chargeMinor: Long = 0,
    val adjustmentMinor: Long? = null,
)

@Serializable
data class SurchargeDto(
    val id: String = "",
    val label: String = "",
    val kind: String = "flat",
    val amountMinor: Long? = null,
    val percentageBps: Int? = null,
    val appliesToServiceLevels: List<String> = emptyList(),
)

@Serializable
data class RiderCostConfigDto(
    val method: String = "fixed",
    val perKmRateMinor: Long? = null,
    val bands: List<DistanceBandDto> = emptyList(),
    val zoneRules: List<ZoneRuleDto> = emptyList(),
    val defaultFeeMinor: Long? = null,
    val historicalAverageMinor: Long? = null,
    val historicalSampleSize: Int? = null,
    val minimumCostMinor: Long? = null,
)

/** The company's saved pricing rules. Only the fields the app edits are modelled. */
@Serializable
data class PricingConfigDto(
    val id: String = "",
    val tenantId: String = "",
    val name: String = "Standard pricing",
    val isActive: Boolean = true,
    val version: Int = 1,
    val currency: String = "NGN",
    val method: String = "base_plus_distance",
    val baseChargeMinor: Long = 0,
    val perKmRateMinor: Long = 0,
    val distanceBands: List<DistanceBandDto> = emptyList(),
    val zoneRules: List<ZoneRuleDto> = emptyList(),
    val fallbackToDistanceWhenZoneMissing: Boolean = true,
    val minimumChargeMinor: Long = 0,
    val maximumChargeMinor: Long? = null,
    val roundingIncrementMinor: Long = 0,
    val roundingMode: String = "nearest",
    val surcharges: List<SurchargeDto> = emptyList(),
    val riderCost: RiderCostConfigDto = RiderCostConfigDto(),
    val targetProfitAmountMinor: Long? = null,
    val targetProfitMarginBps: Int? = null,
)

@Serializable
data class QuoteLocationDto(
    val address: String,
    val zoneId: String? = null,
    val area: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val notes: String? = null,
    val manualDistanceMetres: Long? = null,
)

@Serializable
data class AdditionalCostDto(
    val id: String,
    val label: String,
    val amountMinor: Long,
    val kind: String = "delivery",
)

@Serializable
data class AmountWithReasonDto(val amountMinor: Long, val reason: String, val note: String? = null)

@Serializable
data class PriceOverrideDto(
    val amountMinor: Long,
    val reason: String,
    val allowBelowMinimum: Boolean? = null,
)

@Serializable
data class TargetProfitOverrideDto(val amountMinor: Long? = null, val marginBps: Int? = null)

/** Request body for `calculateDeliveryQuote` (and the pricing part of `createOrder`). */
@Serializable
data class QuoteRequestDto(
    val tenantId: String,
    val pickup: QuoteLocationDto,
    val destination: QuoteLocationDto,
    val serviceLevel: String = "standard",
    val deliveryType: String? = null,
    val additionalCosts: List<AdditionalCostDto> = emptyList(),
    val riderCostOverride: AmountWithReasonDto? = null,
    val customerPriceOverride: PriceOverrideDto? = null,
    val targetProfitOverride: TargetProfitOverrideDto? = null,
    val saveQuote: Boolean? = null,
    val orderId: String? = null,
)

@Serializable
data class QuoteWarningDto(
    val code: String = "",
    val severity: String = "info",
    val message: String = "",
)

@Serializable
data class QuoteLineDto(
    val code: String = "",
    val label: String = "",
    val amountMinor: Long = 0,
    val note: String? = null,
)

@Serializable
data class QuoteDistanceDto(
    val metres: Long = 0,
    val source: String = "provider",
    val provider: String = "unknown",
    val summary: String? = null,
    val durationSeconds: Long? = null,
    val calculatedAt: String? = null,
)

@Serializable
data class RiderCostEstimateDto(
    val amountMinor: Long = 0,
    val method: String = "fixed",
    /** confirmed | estimated | unconfirmed */
    val confidence: String = "estimated",
    val note: String? = null,
)

@Serializable
data class RoundingDto(val incrementMinor: Long = 0, val beforeMinor: Long = 0, val afterMinor: Long = 0)

@Serializable
data class TargetProfitDto(
    val kind: String = "none",
    val value: Long = 0,
    val satisfied: Boolean = true,
    val shortfallMinor: Long = 0,
)

@Serializable
data class PricingSnapshotDto(
    val engineVersion: String? = null,
    val pricingConfigId: String? = null,
    val pricingConfigVersion: Int? = null,
    val pricingConfigName: String? = null,
    val currency: String? = null,
    val capturedAt: String? = null,
)

@Serializable
data class QuoteDto(
    val currency: String = "NGN",
    val method: String = "base_plus_distance",
    val engineVersion: String = "",
    val lines: List<QuoteLineDto> = emptyList(),
    val distance: QuoteDistanceDto? = null,
    val distanceKm: Double? = null,
    val computedCustomerChargeMinor: Long = 0,
    val recommendedCustomerChargeMinor: Long = 0,
    val linesTotalBeforeRounding: Long = 0,
    val appliedMinimumCharge: Boolean = false,
    val appliedMaximumCharge: Boolean = false,
    val rounding: RoundingDto? = null,
    val manualPriceOverrideApplied: Boolean = false,
    val estimatedRiderCost: RiderCostEstimateDto = RiderCostEstimateDto(),
    val estimatedAdditionalDeliveryCostsMinor: Long = 0,
    val estimatedTotalDeliveryCostMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
    val estimatedMarginBps: Int = 0,
    val estimatedMarginIsDefined: Boolean = true,
    val targetProfit: TargetProfitDto = TargetProfitDto(),
    val warnings: List<QuoteWarningDto> = emptyList(),
    val requiresReviewBeforeAccepting: Boolean = false,
    val pricingSnapshot: PricingSnapshotDto = PricingSnapshotDto(),
    val computedAt: String? = null,
)

@Serializable
data class QuoteResponseDto(
    val status: String = "ok",
    val quote: QuoteDto? = null,
    val warnings: List<QuoteWarningDto> = emptyList(),
    val quoteId: String? = null,
    val distanceFallbackReason: String? = null,
    val routingProvider: String? = null,
)

// ---------------------------------------------------------------------------
// Orders, riders, customers, expenses
// ---------------------------------------------------------------------------

@Serializable
data class OrderLocationDto(
    val address: String = "",
    val zoneId: String? = null,
    val area: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val notes: String? = null,
)

@Serializable
data class CustomerSnapshotDto(
    val name: String = "",
    val phone: String? = null,
    val customerType: String = "individual",
)

@Serializable
data class RiderSnapshotDto(
    val name: String = "",
    val phone: String? = null,
    val riderId: String? = null,
)

@Serializable
data class OrderDto(
    val id: String = "",
    val tenantId: String = "",
    val orderNumber: String = "",
    val trackingReference: String = "",
    val status: String = "pending",
    val businessDate: String = "",
    val customerId: String? = null,
    val customerSnapshot: CustomerSnapshotDto? = null,
    val pickup: OrderLocationDto = OrderLocationDto(),
    val destination: OrderLocationDto = OrderLocationDto(),
    val deliveryType: String? = null,
    val itemCategory: String? = null,
    val description: String? = null,
    val serviceLevel: String = "standard",
    val assignedRiderId: String? = null,
    val riderSnapshot: RiderSnapshotDto? = null,
    val distance: QuoteDistanceDto? = null,
    val customerChargeMinor: Long = 0,
    val estimatedCustomerChargeMinor: Long = 0,
    val estimatedRiderCostMinor: Long = 0,
    val riderCostConfidence: String? = null,
    val estimatedAdditionalCostsMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
    val estimatedMarginBps: Int = 0,
    val actualRiderCostMinor: Long? = null,
    val actualAdditionalCostsMinor: Long? = null,
    val actualCustomerChargeMinor: Long? = null,
    val actualProfitMinor: Long? = null,
    val actualsRecordedAt: String? = null,
    val additionalCosts: List<AdditionalCostDto> = emptyList(),
    val acknowledgedWarnings: List<String> = emptyList(),
    val internalNotes: String? = null,
    val scheduledAt: String? = null,
    val currency: String = "NGN",
    val createdBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val failedAt: String? = null,
    val failureReason: String? = null,
)

@Serializable
data class DeliveryEventDto(
    val id: String = "",
    val status: String = "",
    val actorUserId: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class RiderDto(
    val id: String = "",
    val tenantId: String = "",
    val name: String = "",
    val phone: String? = null,
    val email: String? = null,
    val vehicleType: String? = null,
    val vehiclePlate: String? = null,
    val status: String = "active",
    val userId: String? = null,
    val invitedEmail: String? = null,
    val notes: String? = null,
    val deliveriesCompleted: Int = 0,
    val deliveriesFailed: Int = 0,
    val rating: Double? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CustomerDto(
    val id: String = "",
    val tenantId: String = "",
    val name: String = "",
    val customerType: String = "individual",
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val totalOrders: Int = 0,
    val status: String = "active",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ExpenseDto(
    val id: String = "",
    val tenantId: String = "",
    val category: String = "other",
    val kind: String = "operating",
    val amountMinor: Long = 0,
    val currency: String = "NGN",
    val expenseDate: String = "",
    val businessDate: String = "",
    val label: String? = null,
    val notes: String? = null,
    val orderId: String? = null,
    val riderId: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
)

// ---------------------------------------------------------------------------
// Dashboard, reports, tracking, admin
// ---------------------------------------------------------------------------

@Serializable
data class DashboardRangeDto(
    val fromDateKey: String = "",
    val toDateKey: String = "",
    val timezone: String = "Africa/Lagos",
    val currency: String = "NGN",
)

@Serializable
data class DashboardCountsDto(
    val deliveriesToday: Int = 0,
    val ordersInRange: Int = 0,
    val pending: Int = 0,
    val awaitingAssignment: Int = 0,
    val inProgress: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
)

@Serializable
data class DashboardFinancialsDto(
    val revenueMinor: Long = 0,
    val riderPaymentsMinor: Long = 0,
    val operatingExpensesMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
    val actualProfitMinor: Long = 0,
    val averageProfitPerDeliveryMinor: Long = 0,
    val deliveriesWithFinalAmounts: Int = 0,
    val deliveriesAwaitingFinalAmounts: Int = 0,
)

@Serializable
data class DashboardTrendPointDto(
    val date: String = "",
    val deliveriesCompleted: Int = 0,
    val revenueMinor: Long = 0,
    val actualProfitMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
)

@Serializable
data class DashboardDataQualityDto(
    val missingDays: List<String> = emptyList(),
    val hasUnfinalizedRevenue: Boolean = false,
    val pricingVersionChanged: Boolean = false,
)

@Serializable
data class DashboardMetricsDto(
    val range: DashboardRangeDto = DashboardRangeDto(),
    val counts: DashboardCountsDto = DashboardCountsDto(),
    /** Null when the caller lacks `reports.view_financial` (dispatchers, riders). */
    val financials: DashboardFinancialsDto? = null,
    val trends: List<DashboardTrendPointDto> = emptyList(),
    val dataQuality: DashboardDataQualityDto = DashboardDataQualityDto(),
    val generatedAt: String? = null,
)

/** Result of `exportCompanyReport`; the CSV itself is built on the server. */
@Serializable
data class ReportExportDto(
    val fileName: String = "",
    /** Null when Cloud Storage was unavailable — the app then shares the inline preview. */
    val storagePath: String? = null,
    val downloadUrl: String? = null,
    val rowCount: Int = 0,
    val truncated: Boolean = false,
    val generatedAt: String? = null,
)

@Serializable
data class TrackingHistoryDto(
    val status: String = "",
    val label: String = "",
    val at: String = "",
)

@Serializable
data class TrackingResultDto(
    val reference: String = "",
    val status: String = "unknown",
    val statusLabel: String = "",
    val companyName: String? = null,
    val companyLogoUrl: String? = null,
    val brandColor: String? = null,
    val supportPhone: String? = null,
    val pickupArea: String? = null,
    val destinationArea: String? = null,
    val history: List<TrackingHistoryDto> = emptyList(),
    val lastUpdated: String? = null,
    val requiresVerification: Boolean = false,
    val verified: Boolean = false,
)

@Serializable
data class PlatformCompanyDto(
    val tenantId: String = "",
    val name: String = "",
    val slug: String? = null,
    val status: String = "unknown",
    val planId: String = "none",
    val entitlement: String = "unknown",
    val entitlementEffectiveUntil: String? = null,
    val billingMode: String = "trial",
    val trialEndsAt: String? = null,
    val currentPeriodEnd: String? = null,
    val bypassActive: Boolean = false,
    val bypassUntil: String? = null,
    val onboardingComplete: Boolean = false,
    val createdAt: String? = null,
    val createdBy: String? = null,
)

@Serializable
data class UpcomingExpiryDto(
    val tenantId: String = "",
    val name: String = "",
    val entitlement: String = "",
    val daysRemaining: Int? = null,
)

@Serializable
data class ActiveBypassDto(val tenantId: String = "", val until: String? = null, val reason: String? = null)

@Serializable
data class PlatformOverviewCompaniesDto(
    val total: Int = 0,
    val trialing: Int = 0,
    val activePaid: Int = 0,
    val activeOffline: Int = 0,
    val activeBypass: Int = 0,
    val grace: Int = 0,
    val limited: Int = 0,
    val suspended: Int = 0,
    val onboardingIncomplete: Int = 0,
)

@Serializable
data class PlatformOverviewRevenueDto(
    /** Paid through automated billing. */
    val automated: Int = 0,
    /** Paid offline and recorded by a platform administrator. */
    val offline: Int = 0,
    /** Trials, bypasses, complimentary: never counted as revenue. */
    val nonRevenue: Int = 0,
)

@Serializable
data class PlatformOverviewDto(
    val companies: PlatformOverviewCompaniesDto = PlatformOverviewCompaniesDto(),
    val upcomingExpiries: List<UpcomingExpiryDto> = emptyList(),
    val revenue: PlatformOverviewRevenueDto = PlatformOverviewRevenueDto(),
    val activeBypasses: List<ActiveBypassDto> = emptyList(),
    val generatedAt: String? = null,
)

/**
 * Permissive acknowledgement for the simple "changed something" responses.
 *
 * The backend answers these operations with a small object naming what it touched;
 * decoding only the fields we understand keeps the app compatible with older and
 * newer backends alike.
 */
@Serializable
data class AckDto(
    val tenantId: String? = null,
    val updated: List<String> = emptyList(),
    val orderId: String? = null,
    val fields: List<String> = emptyList(),
    val customerId: String? = null,
    val riderId: String? = null,
    val expenseId: String? = null,
    val daysRebuilt: Int? = null,
    val ordersScanned: Int? = null,
)

// ---------------------------------------------------------------------------
// Callable requests
// ---------------------------------------------------------------------------

// Encoding rules for requests (see FunctionsClient):
//   * a field the backend requires has NO default, so it is always encoded;
//   * everything else defaults to null/empty and is omitted when unset, which is
//     exactly what the backend's optional-field validation expects.
// Fields are therefore ordered required-first, optional-after.

@Serializable
data class CreateCompanyRequest(
    val idempotencyKey: String,
    val companyName: String,
    val contactPhone: String,
    val contactEmail: String,
    val businessAddress: String? = null,
    val serviceAreas: List<String>? = null,
    val currency: String? = null,
    val timezone: String? = null,
    val orderNumberPrefix: String? = null,
    val brandColor: String? = null,
)

@Serializable
data class CreateCompanyResponse(
    val tenantId: String = "",
    val companyName: String = "",
    val pricingConfigId: String? = null,
    val subscriptionPlanId: String? = null,
    val trialEndsAt: String? = null,
    val entitlement: String? = null,
)

@Serializable
data class UpdateCompanySettingsRequest(
    val tenantId: String,
    val companyName: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val businessAddress: String? = null,
    val serviceAreas: List<String>? = null,
    val brandColor: String? = null,
    val logoUrl: String? = null,
    val orderNumberPrefix: String? = null,
    val publicTracking: PublicTrackingSettingsDto? = null,
    val onboardingSteps: List<String>? = null,
    val onboardingComplete: Boolean? = null,
)

@Serializable
data class InviteMemberRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val email: String,
    val role: String,
    val displayName: String? = null,
    val grantedPermissions: List<String>? = null,
    val revokedPermissions: List<String>? = null,
)

@Serializable
data class InviteMemberResponse(
    val invitationId: String = "",
    val email: String = "",
    val role: String = "",
    val expiresAt: String? = null,
)

@Serializable
data class AcceptInvitationRequest(val invitationId: String, val displayName: String? = null)

@Serializable
data class AcceptInvitationResponse(val tenantId: String = "", val role: String? = null, val membershipId: String? = null)

@Serializable
data class UpdateMemberRoleRequest(
    val tenantId: String,
    val userId: String,
    val role: String,
    val grantedPermissions: List<String>? = null,
    val revokedPermissions: List<String>? = null,
    val reason: String? = null,
)

@Serializable
data class UpdateMemberRoleResponse(
    val userId: String = "",
    val role: String = "",
    val permissions: List<String> = emptyList(),
)

@Serializable
data class SetMemberStatusRequest(
    val tenantId: String,
    val userId: String,
    /** active | disabled */
    val status: String,
    val reason: String? = null,
)

@Serializable
data class SetMemberStatusResponse(val userId: String = "", val status: String = "")

@Serializable
data class CreateCustomerRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val name: String,
    val customerType: String,
    val phone: String,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
)

@Serializable
data class CreateCustomerResponse(val customerId: String = "")

@Serializable
data class UpdateCustomerRequest(
    val tenantId: String,
    val customerId: String,
    val name: String? = null,
    val phone: String? = null,
    val customerType: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val status: String? = null,
)

@Serializable
data class CreateRiderRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    /** motorcycle | bicycle | car | van | truck | other */
    val vehicleType: String? = null,
    val internalNotes: String? = null,
)

@Serializable
data class CreateRiderResponse(val riderId: String = "", val status: String? = null)

@Serializable
data class UpdateRiderRequest(
    val tenantId: String,
    val riderId: String,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val vehicleType: String? = null,
    /** active | inactive | suspended */
    val status: String? = null,
    val internalNotes: String? = null,
)

@Serializable
data class RiderInvitationRequest(val tenantId: String, val riderId: String, val email: String)

@Serializable
data class RiderInvitationResponse(
    val invitationId: String? = null,
    val token: String = "",
    val expiresAt: String? = null,
    val inviteUrl: String? = null,
)

@Serializable
data class AcceptRiderInvitationRequest(val token: String)

@Serializable
data class AcceptRiderInvitationResponse(val tenantId: String? = null, val riderId: String? = null, val role: String? = null)

@Serializable
data class UpdatePricingRequest(
    val tenantId: String,
    val settings: PricingConfigDto,
    val configId: String? = null,
)

@Serializable
data class UpdatePricingResponse(val configId: String = "", val version: Int = 1, val method: String? = null)

@Serializable
data class CreateOrderRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val pickup: QuoteLocationDto,
    val destination: QuoteLocationDto,
    val serviceLevel: String,
    val description: String,
    val deliveryType: String? = null,
    val itemCategory: String? = null,
    val customerId: String? = null,
    val customerSnapshot: CustomerSnapshotDto? = null,
    val newCustomer: NewCustomerDto? = null,
    val additionalCosts: List<AdditionalCostDto>? = null,
    val riderCostOverride: AmountWithReasonDto? = null,
    val customerPriceOverride: PriceOverrideDto? = null,
    val targetProfitOverride: TargetProfitOverrideDto? = null,
    val assignedRiderId: String? = null,
    val scheduledAt: String? = null,
    val internalNotes: String? = null,
    val acknowledgedWarningCodes: List<String>? = null,
)

@Serializable
data class NewCustomerDto(
    val name: String,
    val phone: String,
    val customerType: String? = null,
    val email: String? = null,
)

@Serializable
data class CreateOrderResponse(
    val orderId: String,
    val orderNumber: String = "",
    val trackingReference: String = "",
    val status: String = "pending",
    val trackingUrl: String? = null,
    val requiresVerification: Boolean = false,
    val customerChargeMinor: Long = 0,
    val estimatedRiderCostMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
    val estimatedMarginBps: Int = 0,
    val warnings: List<QuoteWarningDto> = emptyList(),
    val quoteId: String? = null,
    val distanceSource: String? = null,
)

@Serializable
data class UpdateOrderRequest(
    val tenantId: String,
    val orderId: String,
    val description: String? = null,
    val itemCategory: String? = null,
    val deliveryType: String? = null,
    val serviceLevel: String? = null,
    val pickup: QuoteLocationDto? = null,
    val destination: QuoteLocationDto? = null,
    val additionalCosts: List<AdditionalCostDto>? = null,
    val internalNotes: String? = null,
    val scheduledAt: String? = null,
)

@Serializable
data class AssignRiderRequest(
    val tenantId: String,
    val orderId: String,
    val riderId: String? = null,
    val reason: String? = null,
)

@Serializable
data class AssignRiderResponse(val orderId: String = "", val riderId: String? = null, val status: String = "assigned")

@Serializable
data class UpdateDeliveryStatusRequest(
    val tenantId: String,
    val orderId: String,
    val status: String,
    val notes: String? = null,
    val failureReason: String? = null,
    val reason: String? = null,
)

@Serializable
data class UpdateDeliveryStatusResponse(
    val orderId: String = "",
    val status: String = "",
    val previousStatus: String? = null,
    val eventId: String? = null,
    val allowedNextStatuses: List<String> = emptyList(),
)

@Serializable
data class RecordActualsRequest(
    val tenantId: String,
    val orderId: String,
    val finalCustomerChargeMinor: Long,
    val actualRiderCostMinor: Long,
    val actualAdditionalCosts: List<AdditionalCostDto>,
    val notes: String? = null,
)

@Serializable
data class RecordActualsResponse(
    val orderId: String = "",
    val actualCustomerChargeMinor: Long = 0,
    val actualRiderCostMinor: Long = 0,
    val actualAdditionalCostsMinor: Long = 0,
    val actualProfitMinor: Long = 0,
    val estimatedProfitMinor: Long = 0,
    val varianceMinor: Long = 0,
)

@Serializable
data class CreateExpenseRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val category: String,
    val amountMinor: Long,
    val expenseDate: String,
    val label: String? = null,
    val description: String? = null,
    val orderId: String? = null,
    /** delivery | operating — delivery expenses must carry an orderId. */
    val kind: String? = null,
)

@Serializable
data class CreateExpenseResponse(
    val expenseId: String = "",
    val kind: String? = null,
    val amountMinor: Long = 0,
    val expenseDate: String = "",
)

@Serializable
data class DeleteExpenseRequest(val tenantId: String, val expenseId: String, val reason: String)

@Serializable
data class TrackingVerificationDto(val lastFourPhoneDigits: String)

@Serializable
data class TrackingRequest(val reference: String, val verification: TrackingVerificationDto? = null)

@Serializable
data class DashboardMetricsRequest(
    val tenantId: String,
    val fromDateKey: String? = null,
    val toDateKey: String? = null,
    val trendDays: Int? = null,
)

@Serializable
data class ExportReportRequest(
    val tenantId: String,
    val reportType: String,
    val fromDateKey: String,
    val toDateKey: String,
    /** Opaque cursor from a previous page; the panel downloads until it is null. */
    val cursor: String? = null,
)

@Serializable
data class RebuildRollupsRequest(
    val tenantId: String,
    val fromDateKey: String,
    val toDateKey: String,
)

@Serializable
data class SubscriptionRequest(val tenantId: String)

@Serializable
data class RecordOfflinePaymentRequest(
    val tenantId: String,
    val idempotencyKey: String,
    val amountMinor: Long,
    val currency: String,
    val method: String,
    val externalReference: String,
    val receivedAt: String,
    val periodStart: String,
    val periodEnd: String,
    val reason: String,
    val evidencePath: String? = null,
    val internalNotes: String? = null,
    val planId: String? = null,
)

@Serializable
data class RecordOfflinePaymentResponse(
    val tenantId: String = "",
    val ledgerEntryId: String? = null,
    val entitlement: String = "",
    val entitlementEffectiveUntil: String? = null,
    val periodEnd: String? = null,
)

@Serializable
data class BillingBypassRequest(
    val tenantId: String,
    val idempotencyKey: String,
    /** Must be one of the platform's bypass reasons. */
    val reason: String,
    /** ISO-8601 UTC instant the bypass stops being effective. */
    val until: String,
    val internalNotes: String? = null,
    val replacesExisting: Boolean? = null,
)

@Serializable
data class BillingBypassResponse(
    val tenantId: String = "",
    val ledgerEntryId: String? = null,
    val entitlement: String = "",
    val activeUntil: String? = null,
    val durationDays: Int? = null,
)

@Serializable
data class RevokeBypassRequest(
    val tenantId: String,
    val reason: String,
    val suspendAccount: Boolean? = null,
)

@Serializable
data class RevokeBypassResponse(val tenantId: String = "", val entitlement: String = "")

@Serializable
data class ManageSubscriptionRequest(
    val tenantId: String,
    val action: String,
    val reason: String,
    val planId: String? = null,
    val extendDays: Int? = null,
    val trialEndsAt: String? = null,
)

@Serializable
data class ManageSubscriptionResponse(
    val tenantId: String = "",
    val status: String = "",
    val entitlement: String = "",
    val entitlementEffectiveUntil: String? = null,
)

@Serializable
data class ListCompaniesRequest(
    val search: String? = null,
    /** trialing | active | expired | suspended */
    val status: String? = null,
    val limit: Int? = null,
)

@Serializable
data class ListCompaniesResponse(val companies: List<PlatformCompanyDto> = emptyList())

@Serializable
data class UpdatePlatformSettingsRequest(
    val plans: List<PlanDto>? = null,
    val entitlementPolicy: EntitlementPolicyDto? = null,
)

@Serializable
data class UpdatePlatformSettingsResponse(val updated: List<String> = emptyList())

@Serializable
data class RegisterDeviceTokenRequest(val token: String, val platform: String, val appVersion: String? = null)

@Serializable
data class RegisterDeviceTokenResponse(val registered: Boolean = false, val tokenCount: Int = 0)
