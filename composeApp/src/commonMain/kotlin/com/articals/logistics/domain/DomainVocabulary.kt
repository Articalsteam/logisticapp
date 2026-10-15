package com.articals.logistics.domain

import com.articals.logistics.domain.order.OrderStatus

/**
 * The words the product uses, in one place.
 *
 * Every enum here mirrors the backend so that copy, validation and error messages
 * are identical in both modes — and so that a second language has a single list to
 * translate (`docs/COMPILE_CHECKLIST.md` records that the strings themselves still
 * live in the UI layer).
 */

object ExpenseCategories {
    val all: List<String> = listOf(
        "fuel", "rider_advance", "parking", "toll", "waiting_fee",
        "maintenance", "airtime", "rent", "salaries", "other",
    )

    fun label(category: String): String = when (category) {
        "fuel" -> "Fuel"
        "rider_advance" -> "Rider advance"
        "parking" -> "Parking"
        "toll" -> "Toll"
        "waiting_fee" -> "Waiting fee"
        "maintenance" -> "Maintenance"
        "airtime" -> "Airtime"
        "rent" -> "Rent"
        "salaries" -> "Salaries"
        else -> "Other"
    }
}

object ExpenseKind {
    const val Delivery = "delivery"
    const val Operating = "operating"
}

object BypassReasons {
    val all: List<String> = listOf(
        "offline_payment_pending",
        "complimentary_pilot",
        "partner_access",
        "custom_commercial_terms",
        "support_goodwill",
    )

    fun label(reason: String): String = when (reason) {
        "offline_payment_pending" -> "Offline payment pending"
        "complimentary_pilot" -> "Complimentary pilot"
        "partner_access" -> "Partner access"
        "custom_commercial_terms" -> "Custom commercial terms"
        "support_goodwill" -> "Support goodwill"
        else -> reason
    }
}

object OfflinePaymentMethods {
    val all: List<String> = listOf("bank_transfer", "cash", "pos", "other")

    fun label(method: String): String = when (method) {
        "bank_transfer" -> "Bank transfer"
        "cash" -> "Cash"
        "pos" -> "POS terminal"
        else -> "Other"
    }
}

object ReportTypes {
    const val Orders = "orders"
    const val FinancialSummary = "financial_summary"
    const val Riders = "riders"
    const val Customers = "customers"

    val all: List<String> = listOf(Orders, FinancialSummary, Riders, Customers)

    fun label(type: String): String = when (type) {
        Orders -> "Deliveries"
        FinancialSummary -> "Financial summary"
        Riders -> "Riders"
        Customers -> "Customers"
        else -> type
    }
}

/** Pricing methods a company can choose. */
object PricingMethods {
    const val BasePlusDistance = "base_plus_distance"
    const val DistanceBands = "distance_bands"
    const val ZoneToZone = "zone_to_zone"
    const val FlatRate = "flat_rate"
    const val Hybrid = "hybrid"

    val all: List<String> = listOf(BasePlusDistance, DistanceBands, ZoneToZone, FlatRate, Hybrid)

    fun label(method: String): String = when (method) {
        BasePlusDistance -> "Base charge + distance"
        DistanceBands -> "Distance bands"
        ZoneToZone -> "Zone to zone"
        FlatRate -> "Flat rate"
        Hybrid -> "Hybrid (base + distance + bands)"
        else -> method
    }

    fun help(method: String): String = when (method) {
        BasePlusDistance -> "A fixed base charge plus a rate per kilometre."
        DistanceBands -> "A fixed charge per distance range, e.g. 0–3 km, 3–7 km, 7 km and beyond."
        ZoneToZone -> "A charge per pickup→destination zone pair, for companies that price by area."
        FlatRate -> "One charge regardless of distance."
        Hybrid -> "Base charge, per-kilometre rate and distance bands combined."
        else -> ""
    }
}

/** Rider-cost methods a company can choose. */
object RiderCostMethods {
    const val DistanceRate = "distance_rate"
    const val DistanceBands = "distance_bands"
    const val ZoneBased = "zone_based"
    const val Fixed = "fixed"
    const val HistoricalAverage = "historical_average"
    const val Manual = "manual"

    val all: List<String> = listOf(DistanceRate, DistanceBands, ZoneBased, Fixed, HistoricalAverage, Manual)

    fun label(method: String): String = when (method) {
        DistanceRate -> "Rate per kilometre"
        DistanceBands -> "Distance bands"
        ZoneBased -> "Zone based"
        Fixed -> "Fixed fee per delivery"
        HistoricalAverage -> "Average of recent payments"
        Manual -> "Entered each time"
        else -> method
    }
}

object RoundingModes {
    val all: List<String> = listOf("nearest", "up", "down")

    fun label(mode: String): String = when (mode) {
        "nearest" -> "Nearest"
        "up" -> "Always up"
        "down" -> "Always down"
        else -> mode
    }
}

/**
 * Pricing warning codes, mirroring `functions/src/pricing/*`.
 *
 * The app never produces these; it renders them, and uses
 * [reviewRequired] to decide whether a price can be accepted without an
 * acknowledgement.
 */
object QuoteWarningCodes {
    val all: List<String> = listOf(
        "distance_required", "manual_distance", "no_matching_band", "zone_rule_missing",
        "zone_rule_missing_fallback", "zone_adjustment_missing", "invalid_target_margin",
        "zero_customer_charge", "price_below_cost", "rider_cost_unconfirmed",
        "duplicate_additional_cost", "minimum_charge_applied", "minimum_charge_waived",
        "maximum_charge_applied", "rounded", "manual_price_override", "below_target_profit",
    )

    /** Warnings that block accepting a price until a human acknowledges them. */
    val reviewRequired: Set<String> = setOf(
        "distance_required", "manual_distance", "no_matching_band", "zone_rule_missing",
        "invalid_target_margin", "zero_customer_charge", "price_below_cost", "rider_cost_unconfirmed",
    )

    fun label(code: String): String = when (code) {
        "distance_required" -> "Distance needed"
        "manual_distance" -> "Manually entered distance"
        "no_matching_band" -> "No matching distance band"
        "zone_rule_missing" -> "Zone rule missing"
        "zone_rule_missing_fallback" -> "Priced by distance instead"
        "zone_adjustment_missing" -> "Zone adjustment missing"
        "invalid_target_margin" -> "Target margin not usable"
        "zero_customer_charge" -> "Free delivery"
        "price_below_cost" -> "Price below cost"
        "rider_cost_unconfirmed" -> "Rider cost not confirmed"
        "duplicate_additional_cost" -> "Duplicate extra cost"
        "minimum_charge_applied" -> "Minimum charge applied"
        "minimum_charge_waived" -> "Minimum charge waived"
        "maximum_charge_applied" -> "Maximum charge applied"
        "rounded" -> "Rounded"
        "manual_price_override" -> "Price set manually"
        "below_target_profit" -> "Below target profit"
        else -> code.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Entitlement states, mirroring `functions/src/domain/entitlement.ts`. */
object EntitlementStates {
    const val Trialing = "trialing"
    const val ActivePaid = "active_paid"
    const val ActiveOffline = "active_offline"
    const val ActiveBypass = "active_bypass"
    const val Grace = "grace"
    const val Limited = "limited"
    const val Suspended = "suspended"

    val all: List<String> = listOf(Trialing, ActivePaid, ActiveOffline, ActiveBypass, Grace, Limited, Suspended)

    /** States in which a company may still create or change data. */
    val writesAllowed: Set<String> = setOf(Trialing, ActivePaid, ActiveOffline, ActiveBypass, Grace)

    val revenueBearing: Set<String> = setOf(ActivePaid, ActiveOffline)

    fun label(state: String): String = when (state) {
        Trialing -> "Free trial"
        ActivePaid -> "Active"
        ActiveOffline -> "Active (invoice paid)"
        ActiveBypass -> "Active (complimentary)"
        Grace -> "Grace period"
        Limited -> "Limited access"
        Suspended -> "Suspended"
        else -> state
    }

    fun canWrite(state: String): Boolean = state in writesAllowed

    /** Banner copy for a state we do not treat as "normal". */
    fun notice(state: String, daysRemaining: Int?): String? = when (state) {
        Trialing -> daysRemaining?.let { days ->
            if (days <= 7) "Trial ends in $days ${if (days == 1) "day" else "days"}." else null
        }
        Grace -> "The subscription period has ended. You can keep working during the grace period, " +
            "but please settle the invoice."
        Limited -> "This workspace is read-only until billing is settled. You can still view and export your data."
        Suspended -> "This company account is suspended. Contact Articals Studio support."
        ActiveBypass -> "Complimentary access granted by Articals Studio."
        else -> null
    }
}

/** Statuses used by the dashboard's "needs attention" list. */
object AttentionReasons {
    const val Unassigned = "unassigned"
    const val RiderCostUnconfirmed = "rider_cost_unconfirmed"
    const val ManualDistanceReview = "manual_distance_review"
    const val AwaitingFinalAmounts = "awaiting_final_amounts"
    const val UnreportedProblem = "unreported_problem"
    const val DeliveryFailed = "delivery_failed"

    /**
     * Fallback copy. Screens call `ArticalsStrings.attentionLabel` so that the wording
     * lives with the rest of the app's text and can be translated in one place; this is
     * only used by anything that has no access to the strings object.
     */
    fun label(reason: String): String = when (reason) {
        Unassigned -> "No rider assigned"
        RiderCostUnconfirmed -> "Rider cost not confirmed"
        ManualDistanceReview -> "Distance entered by hand"
        AwaitingFinalAmounts -> "Final amounts not recorded"
        UnreportedProblem -> "A rider reported a problem"
        DeliveryFailed -> "Delivery failed"
        else -> reason
    }
}

/** Convenience for screens that show a status chip. */
fun statusLabel(status: String): String = OrderStatus.label(status)
