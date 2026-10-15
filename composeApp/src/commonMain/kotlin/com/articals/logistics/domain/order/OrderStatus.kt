package com.articals.logistics.domain.order

/**
 * Delivery status workflow, mirrored from `functions/src/domain/orderStatus.ts`.
 *
 * This copy exists so buttons can be disabled before the user taps them. It is a
 * UX aid, never a control: whichever repository is in use re-checks the transition
 * and can still refuse it (that is what the demo does, and what the backend does).
 */
object OrderStatus {
    const val Pending = "pending"
    const val Confirmed = "confirmed"
    const val Assigned = "assigned"
    const val PickedUp = "picked_up"
    const val InTransit = "in_transit"
    const val Delivered = "delivered"
    const val Failed = "failed"
    const val Cancelled = "cancelled"

    val all: List<String> = listOf(
        Pending, Confirmed, Assigned, PickedUp, InTransit, Delivered, Failed, Cancelled,
    )

    val terminal: Set<String> = setOf(Delivered, Failed, Cancelled)

    private val standard: Map<String, List<String>> = mapOf(
        Pending to listOf(Confirmed, Assigned, Cancelled),
        Confirmed to listOf(Assigned, Cancelled),
        Assigned to listOf(PickedUp, Failed, Cancelled),
        PickedUp to listOf(InTransit, Delivered, Failed),
        InTransit to listOf(Delivered, Failed),
        Delivered to emptyList(),
        Failed to emptyList(),
        Cancelled to emptyList(),
    )

    private val corrections: Map<String, List<String>> = mapOf(
        Pending to emptyList(),
        Confirmed to listOf(Pending),
        Assigned to listOf(Confirmed, Pending),
        Delivered to listOf(InTransit, PickedUp, Failed),
        Failed to listOf(Delivered, InTransit, Cancelled),
        Cancelled to listOf(Pending, Confirmed),
        PickedUp to listOf(Assigned),
        InTransit to emptyList(),
    )

    private val riderFlow: Map<String, List<String>> = mapOf(
        Assigned to listOf(PickedUp, Failed),
        PickedUp to listOf(InTransit, Delivered, Failed),
        InTransit to listOf(Delivered, Failed),
        Pending to emptyList(),
        Confirmed to emptyList(),
        Delivered to emptyList(),
        Failed to emptyList(),
        Cancelled to emptyList(),
    )

    data class Check(
        val allowed: Boolean,
        val kind: String,
        val reason: String? = null,
        val message: String? = null,
    )

    /** The statuses [from] may move to for the given actor. */
    fun allowedNext(from: String, isAssignedRider: Boolean = false, canCorrectStatus: Boolean = false): List<String> {
        val planned = if (isAssignedRider) riderFlow[from].orEmpty() else standard[from].orEmpty()
        return if (canCorrectStatus && !isAssignedRider) {
            (planned + corrections[from].orEmpty()).distinct()
        } else {
            planned
        }
    }

    /** Mirrors `canTransition`, including the reasons the backend returns. */
    fun canTransition(
        from: String,
        to: String,
        isAssignedRider: Boolean = false,
        canCorrectStatus: Boolean = false,
    ): Check {
        if (from == to) {
            return Check(false, "none", "status_unchanged", "The delivery is already in that state.")
        }
        if (isAssignedRider) {
            return if (to in riderFlow[from].orEmpty()) {
                Check(true, "standard")
            } else {
                Check(
                    false,
                    "none",
                    "rider_transition_not_allowed",
                    "A rider can only move a delivery along the delivery steps.",
                )
            }
        }
        if (to in standard[from].orEmpty()) return Check(true, "standard")
        if (to in corrections[from].orEmpty()) {
            return if (canCorrectStatus) {
                Check(true, "correction")
            } else {
                Check(
                    false,
                    "correction",
                    "correction_not_permitted",
                    "Only an owner or manager can correct a finished delivery, and a reason is required.",
                )
            }
        }
        return Check(
            false,
            "none",
            "transition_not_allowed",
            "A delivery cannot move from ${label(from)} to ${label(to)}.",
        )
    }

    /**
     * Operator-facing labels. "Awaiting confirmation" rather than "pending", because
     * the label is read by a dispatcher, not by a program.
     */
    fun label(status: String): String = when (status) {
        Pending -> "Awaiting confirmation"
        Confirmed -> "Confirmed"
        Assigned -> "Assigned to rider"
        PickedUp -> "Picked up"
        InTransit -> "On the way"
        Delivered -> "Delivered"
        Failed -> "Failed"
        Cancelled -> "Cancelled"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    /** Short labels for chips and filters. */
    fun shortLabel(status: String): String = when (status) {
        Pending -> "Awaiting"
        Confirmed -> "Confirmed"
        Assigned -> "Assigned"
        PickedUp -> "Picked up"
        InTransit -> "In transit"
        Delivered -> "Delivered"
        Failed -> "Failed"
        Cancelled -> "Cancelled"
        else -> status
    }

    /** The statuses that mean "work still to do". */
    val open: Set<String> = setOf(Pending, Confirmed, Assigned, PickedUp, InTransit)

    val active: Set<String> = setOf(PickedUp, InTransit)
}

/** Why a delivery failed. Mirrors the backend enum so the copy matches. */
object FailureReason {
    const val CustomerUnreachable = "customer_unreachable"
    const val WrongAddress = "wrong_address"
    const val RefusedDelivery = "refused_delivery"
    const val ItemDamaged = "item_damaged"
    const val RiderIssue = "rider_issue"
    const val Other = "other"

    val all: List<String> = listOf(
        CustomerUnreachable, WrongAddress, RefusedDelivery, ItemDamaged, RiderIssue, Other,
    )

    fun label(reason: String): String = when (reason) {
        CustomerUnreachable -> "Customer unreachable"
        WrongAddress -> "Wrong address"
        RefusedDelivery -> "Delivery refused"
        ItemDamaged -> "Item damaged"
        RiderIssue -> "Rider problem"
        Other -> "Other"
        else -> reason
    }
}

/** Service levels a dispatcher can choose. */
object ServiceLevel {
    const val Standard = "standard"
    const val Express = "express"
    const val Scheduled = "scheduled"

    val all: List<String> = listOf(Standard, Express, Scheduled)

    fun label(level: String): String = when (level) {
        Standard -> "Standard"
        Express -> "Express"
        Scheduled -> "Scheduled"
        else -> level
    }
}

/** Item categories, mirroring the backend enum. */
object ItemCategory {
    val all: List<String> =
        listOf("food", "medicine", "documents", "parcel", "errand", "furniture", "other")

    fun label(category: String): String = when (category) {
        "food" -> "Food"
        "medicine" -> "Medicine"
        "documents" -> "Documents"
        "parcel" -> "Parcel"
        "errand" -> "Errand"
        "furniture" -> "Furniture"
        else -> "Other"
    }
}
