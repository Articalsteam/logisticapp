package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.isoInstant
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.shiftDateKey
import com.articals.logistics.domain.ExpenseCategories
import com.articals.logistics.domain.ExpenseKind
import com.articals.logistics.domain.ReportTypes
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.data.api.ExpensesRepository
import com.articals.logistics.data.api.InsightsRepository
import com.articals.logistics.data.api.TrackingRepository
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CreateExpenseRequest
import com.articals.logistics.data.dto.CreateExpenseResponse
import com.articals.logistics.data.dto.DashboardCountsDto
import com.articals.logistics.data.dto.DashboardDataQualityDto
import com.articals.logistics.data.dto.DashboardFinancialsDto
import com.articals.logistics.data.dto.DashboardMetricsDto
import com.articals.logistics.data.dto.DashboardRangeDto
import com.articals.logistics.data.dto.DashboardTrendPointDto
import com.articals.logistics.data.dto.DeleteExpenseRequest
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.data.dto.ExportReportRequest
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.ReportExportDto
import com.articals.logistics.data.dto.TrackingHistoryDto
import com.articals.logistics.data.dto.TrackingResultDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Expenses in demo mode: overheads only, with delivery costs living on the delivery. */
class DemoExpensesRepository(
    private val store: DemoStore,
) : ExpensesRepository {

    override fun observeExpenses(tenantId: String, limit: Int): Flow<List<ExpenseDto>> =
        store.expenses.map { expenses ->
            expenses.values
                .filter { it.tenantId == tenantId }
                .sortedByDescending { it.expenseDate }
                .take(limit)
        }

    override suspend fun create(request: CreateExpenseRequest): AppResult<CreateExpenseResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.ExpensesManage)
            requireValid(
                request.category in ExpenseCategories.all,
                "category",
                "Choose a category.",
                "expense.invalid_category",
            )
            requireValid(request.amountMinor > 0, "amountMinor", "Enter an amount above zero.", "expense.invalid_amount")
            requireValid(
                request.expenseDate.length == 10,
                "expenseDate",
                "Choose the date the money was spent.",
                "expense.invalid_date",
            )

            // A delivery expense must name a delivery, and cannot be added to one that has
            // already been closed with its final amounts — that is what recordActuals is for.
            val kind = request.kind ?: if (request.orderId != null) ExpenseKind.Delivery else ExpenseKind.Operating
            if (kind == ExpenseKind.Delivery) {
                val orderId = request.orderId ?: throw DemoFailure(
                    AppError.InvalidInput(
                        detail = "expense.order_required",
                        message = "A delivery expense has to say which delivery it belongs to.",
                        fields = mapOf("orderId" to "Choose the delivery"),
                    ),
                )
                val order = store.orders.value[orderId]
                if (order == null || order.tenantId != request.tenantId) {
                    throw DemoFailure(AppError.NotFound(detail = "order_missing", message = "That delivery was not found."))
                }
                if (order.status in OrderStatus.terminal && order.actualsRecordedAt != null) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "expense.order_closed_use_actuals",
                            message = "This delivery already has its final amounts recorded. " +
                                "Correct them there instead of adding a separate expense.",
                        ),
                    )
                }
            }

            store.idempotent("createExpense:${request.tenantId}:${request.idempotencyKey}") {
                val id = store.newId("expense")
                store.expenses.value = store.expenses.value + (
                    id to ExpenseDto(
                        id = id,
                        tenantId = request.tenantId,
                        category = request.category,
                        kind = kind,
                        amountMinor = request.amountMinor,
                        currency = "NGN",
                        expenseDate = request.expenseDate,
                        businessDate = request.expenseDate,
                        label = request.label,
                        notes = request.description,
                        orderId = request.orderId,
                        createdBy = store.currentUid(),
                        createdAt = store.isoNow(),
                    )
                    )
                store.audit(
                    action = "expense.created",
                    tenantId = request.tenantId,
                    resourceType = "expense",
                    resourceId = id,
                    metadata = mapOf(
                        "category" to request.category,
                        "kind" to kind,
                        "amountMinor" to request.amountMinor.toString(),
                    ),
                )
                CreateExpenseResponse(
                    expenseId = id,
                    kind = kind,
                    amountMinor = request.amountMinor,
                    expenseDate = request.expenseDate,
                )
            }
        }

    override suspend fun delete(request: DeleteExpenseRequest): AppResult<AckDto> = store.demoCall {
        store.requireWrite(request.tenantId, Permissions.ExpensesManage)
        val existing = store.expenses.value[request.expenseId]
            ?: throw DemoFailure(AppError.NotFound(detail = "expense_missing"))
        if (existing.tenantId != request.tenantId) throw DemoFailure(AppError.NotFound(detail = "expense_missing"))
        requireValid(
            request.reason.trim().length >= 3,
            "reason",
            "Say why this expense is being removed.",
            "expense.reason_required",
        )
        store.expenses.value = store.expenses.value - request.expenseId
        store.audit(
            action = "expense.deleted",
            tenantId = request.tenantId,
            resourceType = "expense",
            resourceId = request.expenseId,
            reason = request.reason,
            metadata = mapOf("amountMinor" to existing.amountMinor.toString()),
        )
        AckDto(tenantId = request.tenantId, expenseId = request.expenseId)
    }
}

/**
 * Dashboards and exports in demo mode.
 *
 * The numbers are computed from the same order documents the screens read, using the
 * same definitions as `docs/FINANCIAL_DEFINITIONS.md`, and the financial block is
 * omitted entirely for a role that may not see money — the demo refuses it exactly
 * like the backend rather than filtering on the device.
 */
class DemoInsightsRepository(
    private val store: DemoStore,
) : InsightsRepository {

    override suspend fun dashboard(
        tenantId: String,
        fromDateKey: String?,
        toDateKey: String?,
        trendDays: Int?,
    ): AppResult<DashboardMetricsDto> = store.demoCall {
        val membership = store.requireMembership(tenantId, Permissions.ReportsViewOperational)
        val permissions = Permissions.effective(membership.role, membership.grantedPermissions, membership.revokedPermissions)
        val canSeeFinancials = Permissions.ReportsViewFinancial in permissions

        val timeZone = store.timeZoneOf(tenantId)
        val today = businessDateKey(store.now(), timeZone)
        val to = toDateKey ?: today
        val from = fromDateKey ?: shiftDateKey(to, -((trendDays ?: 14) - 1), timeZone)
        if (from > to) {
            throw DemoFailure(
                AppError.InvalidInput(
                    detail = "reports.invalid_range",
                    message = "The start of the range is after its end.",
                    fields = mapOf("fromDateKey" to "Choose an earlier date"),
                ),
            )
        }

        val orders = store.orders.value.values.filter { it.tenantId == tenantId }
        val inRange = orders.filter { it.businessDate in from..to }
        val closedToday = orders.filter { it.businessDate == today && it.status == OrderStatus.Delivered }

        val delivered = inRange.filter { it.status == OrderStatus.Delivered }
        val revenue = delivered.sumOf { it.actualCustomerChargeMinor ?: it.customerChargeMinor }
        val riderPayments = delivered.sumOf { it.actualRiderCostMinor ?: it.estimatedRiderCostMinor }
        val additional = delivered.sumOf { it.actualAdditionalCostsMinor ?: it.estimatedAdditionalCostsMinor }
        val actualProfit = delivered.sumOf { it.actualProfitMinor ?: 0L }
        val estimatedProfit = delivered.sumOf { it.estimatedProfitMinor }
        val withFinalAmounts = delivered.count { it.actualsRecordedAt != null }

        val operatingExpenses = store.expenses.value.values
            .filter { it.tenantId == tenantId && it.kind == ExpenseKind.Operating && it.expenseDate in from..to }
            .sumOf { it.amountMinor }

        val trends = buildTrends(from, to, timeZone, orders, canSeeFinancials)
        val daysWithOrders = inRange.map { it.businessDate }.toSet()

        DashboardMetricsDto(
            range = DashboardRangeDto(
                fromDateKey = from,
                toDateKey = to,
                timezone = timeZone,
                currency = store.companies.value[tenantId]?.currency ?: "NGN",
            ),
            counts = DashboardCountsDto(
                deliveriesToday = closedToday.size,
                ordersInRange = inRange.size,
                pending = orders.count { it.status == OrderStatus.Pending },
                awaitingAssignment = orders.count { it.status in setOf(OrderStatus.Pending, OrderStatus.Confirmed) },
                inProgress = orders.count { it.status in OrderStatus.active },
                completed = inRange.count { it.status == OrderStatus.Delivered },
                failed = inRange.count { it.status == OrderStatus.Failed },
                cancelled = inRange.count { it.status == OrderStatus.Cancelled },
            ),
            financials = if (!canSeeFinancials) {
                null
            } else {
                DashboardFinancialsDto(
                    revenueMinor = revenue,
                    riderPaymentsMinor = riderPayments,
                    operatingExpensesMinor = operatingExpenses,
                    estimatedProfitMinor = estimatedProfit,
                    actualProfitMinor = actualProfit,
                    averageProfitPerDeliveryMinor = if (withFinalAmounts > 0) actualProfit / withFinalAmounts else 0,
                    deliveriesWithFinalAmounts = withFinalAmounts,
                    deliveriesAwaitingFinalAmounts = delivered.size - withFinalAmounts,
                )
            },
            trends = trends,
            dataQuality = DashboardDataQualityDto(
                // Days in the range with no deliveries at all, so an empty chart is never
                // mistaken for a broken one.
                missingDays = enumerateDates(from, to, timeZone).filter { it !in daysWithOrders },
                hasUnfinalizedRevenue = delivered.any { it.actualsRecordedAt == null },
                pricingVersionChanged = inRange.mapNotNull { it.pricingSnapshot?.pricingConfigVersion }
                    .distinct()
                    .size > 1,
            ),
            generatedAt = store.isoNow(),
        )
    }

    override suspend fun exportReport(request: ExportReportRequest): AppResult<ReportExportDto> =
        store.demoCall {
            store.requireMembership(request.tenantId, Permissions.ReportsExport)
            requireValid(
                request.reportType in ReportTypes.all,
                "reportType",
                "Choose a report.",
                "reports.invalid_type",
            )
            val membership = store.requireMembership(request.tenantId)
            val permissions = Permissions.effective(membership.role, membership.grantedPermissions, membership.revokedPermissions)
            if (request.reportType == ReportTypes.FinancialSummary && Permissions.ReportsViewFinancial !in permissions) {
                throw DemoFailure(AppError.PermissionDenied(detail = "reports.financial_not_permitted"))
            }

            val csv = buildCsv(request.tenantId, request.reportType, request.fromDateKey, request.toDateKey)
            val rows = csv.lines().size - 1
            val fileName = "articals-${request.reportType}-${request.fromDateKey}_${request.toDateKey}.csv"
            store.audit(
                action = "report.exported",
                tenantId = request.tenantId,
                resourceType = "report",
                resourceId = fileName,
                metadata = mapOf(
                    "reportType" to request.reportType,
                    "rowCount" to rows.toString(),
                    "fromDateKey" to request.fromDateKey,
                    "toDateKey" to request.toDateKey,
                ),
            )
            ReportExportDto(
                fileName = fileName,
                // No Cloud Storage in demo mode, so the CSV travels inline and the share
                // sheet gets real content instead of a dead link.
                storagePath = null,
                downloadUrl = null,
                rowCount = rows,
                truncated = false,
                generatedAt = store.isoNow(),
                inlineCsv = csv,
            )
        }

    override suspend fun rebuildRollups(tenantId: String, fromDateKey: String, toDateKey: String): AppResult<AckDto> =
        store.demoCall {
            store.requireWrite(tenantId, Permissions.ReportsExport)
            val days = enumerateDates(fromDateKey, toDateKey, store.timeZoneOf(tenantId)).size
            val scanned = store.orders.value.values.count { it.tenantId == tenantId && it.businessDate in fromDateKey..toDateKey }
            store.audit(
                action = "reports.rollups_rebuilt",
                tenantId = tenantId,
                resourceType = "report",
                resourceId = "$fromDateKey..$toDateKey",
                metadata = mapOf("days" to days.toString(), "ordersScanned" to scanned.toString()),
            )
            // Demo roll-ups are derived on read, so nothing needs storing; the numbers are
            // reported honestly rather than invented.
            AckDto(tenantId = tenantId, daysRebuilt = days, ordersScanned = scanned)
        }

    // -----------------------------------------------------------------------

    private fun buildTrends(
        from: String,
        to: String,
        timeZone: String,
        orders: List<OrderDto>,
        includeFinancials: Boolean,
    ): List<DashboardTrendPointDto> = enumerateDates(from, to, timeZone).map { date ->
        val delivered = orders.filter { it.businessDate == date && it.status == OrderStatus.Delivered }
        DashboardTrendPointDto(
            date = date,
            deliveriesCompleted = delivered.size,
            revenueMinor = if (includeFinancials) delivered.sumOf { it.actualCustomerChargeMinor ?: it.customerChargeMinor } else 0,
            actualProfitMinor = if (includeFinancials) delivered.sumOf { it.actualProfitMinor ?: 0L } else 0,
            estimatedProfitMinor = if (includeFinancials) delivered.sumOf { it.estimatedProfitMinor } else 0,
        )
    }

    private fun enumerateDates(from: String, to: String, timeZone: String, cap: Int = 60): List<String> {
        val dates = mutableListOf<String>()
        var cursor = from
        while (cursor <= to && dates.size < cap) {
            dates += cursor
            cursor = shiftDateKey(cursor, 1, timeZone)
        }
        return dates
    }

    private fun buildCsv(tenantId: String, reportType: String, from: String, to: String): String {
        val orders = store.orders.value.values
            .filter { it.tenantId == tenantId && it.businessDate in from..to }
            .sortedBy { it.businessDate }

        fun escape(value: String?): String {
            val text = value ?: ""
            val safe = if (text.isNotEmpty() && text.first() in listOf('=', '+', '-', '@')) "'$text" else text
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }

        fun money(amount: Long): String = "${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"

        return when (reportType) {
            ReportTypes.Orders -> buildString {
                appendLine("order_number,business_date,status,customer,pickup_area,destination_area,rider,charge,rider_cost,additional_costs,estimated_profit,actual_profit,recorded_at")
                orders.forEach { order ->
                    appendLine(
                        listOf(
                            escape(order.orderNumber),
                            escape(order.businessDate),
                            escape(order.status),
                            escape(order.customerSnapshot?.name),
                            escape(order.pickup.area),
                            escape(order.destination.area),
                            escape(order.riderSnapshot?.name),
                            escape(money(order.customerChargeMinor)),
                            escape(money(order.actualRiderCostMinor ?: order.estimatedRiderCostMinor)),
                            escape(money(order.actualAdditionalCostsMinor ?: order.estimatedAdditionalCostsMinor)),
                            escape(money(order.estimatedProfitMinor)),
                            escape(order.actualProfitMinor?.let(::money)),
                            escape(order.actualsRecordedAt),
                        ).joinToString(","),
                    )
                }
            }

            ReportTypes.FinancialSummary -> buildString {
                appendLine("business_date,deliveries,revenue,rider_payments,additional_costs,estimated_profit,actual_profit")
                enumerateDates(from, to, store.timeZoneOf(tenantId)).forEach { date ->
                    val delivered = orders.filter { it.businessDate == date && it.status == OrderStatus.Delivered }
                    appendLine(
                        listOf(
                            escape(date),
                            delivered.size.toString(),
                            money(delivered.sumOf { it.actualCustomerChargeMinor ?: it.customerChargeMinor }),
                            money(delivered.sumOf { it.actualRiderCostMinor ?: it.estimatedRiderCostMinor }),
                            money(delivered.sumOf { it.actualAdditionalCostsMinor ?: it.estimatedAdditionalCostsMinor }),
                            money(delivered.sumOf { it.estimatedProfitMinor }),
                            money(delivered.sumOf { it.actualProfitMinor ?: 0L }),
                        ).joinToString(","),
                    )
                }
            }

            ReportTypes.Riders -> buildString {
                appendLine("rider,phone,status,deliveries_completed,deliveries_failed,rider_cost")
                val riders = store.riders.value.values.filter { it.tenantId == tenantId }
                riders.forEach { rider ->
                    val assigned = orders.filter { it.assignedRiderId == rider.id }
                    appendLine(
                        listOf(
                            escape(rider.name),
                            escape(rider.phone),
                            escape(rider.status),
                            assigned.count { it.status == OrderStatus.Delivered }.toString(),
                            assigned.count { it.status == OrderStatus.Failed }.toString(),
                            money(assigned.filter { it.status == OrderStatus.Delivered }
                                .sumOf { it.actualRiderCostMinor ?: it.estimatedRiderCostMinor }),
                        ).joinToString(","),
                    )
                }
            }

            else -> buildString {
                appendLine("customer,type,phone,deliveries,revenue")
                val customers = store.customers.value.values.filter { it.tenantId == tenantId }
                customers.forEach { customer ->
                    val own = orders.filter { it.customerId == customer.id && it.status == OrderStatus.Delivered }
                    appendLine(
                        listOf(
                            escape(customer.name),
                            escape(customer.customerType),
                            escape(customer.phone),
                            own.size.toString(),
                            money(own.sumOf { it.actualCustomerChargeMinor ?: it.customerChargeMinor }),
                        ).joinToString(","),
                    )
                }
            }
        }
    }
}

/**
 * Public tracking in demo mode.
 *
 * Built from the order's own history, but only the fields the public projection
 * carries: no addresses, no rider identity, no money, no internal notes. A reference
 * that does not exist and one belonging to another company give the same answer.
 */
class DemoTrackingRepository(
    private val store: DemoStore,
) : TrackingRepository {

    override suspend fun track(reference: String, lastFourPhoneDigits: String?): AppResult<TrackingResultDto> =
        store.demoCall {
            val clean = reference.trim().uppercase()
            val order = store.orders.value.values.firstOrNull { it.trackingReference.uppercase() == clean }
                ?: throw DemoFailure(
                    AppError.NotFound(
                        detail = "tracking_not_found",
                        message = "We could not find a delivery with that reference.",
                    ),
                )

            val company = store.companies.value[order.tenantId]
            val requireVerification = company?.publicTracking?.requireVerification == true
            if (requireVerification) {
                val expected = order.customerSnapshot?.phone?.filter { it.isDigit() }?.takeLast(4)
                val supplied = lastFourPhoneDigits?.filter { it.isDigit() }
                if (supplied == null) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "verification_required",
                            message = "For privacy, this company asks for the last four digits of the phone number used for the delivery.",
                        ),
                    )
                }
                if (supplied != expected) {
                    throw DemoFailure(
                        AppError.PermissionDenied(
                            detail = "verification_failed",
                            message = "Those digits do not match this delivery.",
                        ),
                    )
                }
            }

            val history = store.events.value[order.id].orEmpty()
                .sortedBy { it.createdAt ?: "" }
                .map { event ->
                    TrackingHistoryDto(
                        status = event.status,
                        label = OrderStatus.label(event.status),
                        at = event.createdAt ?: store.isoNow(),
                    )
                }

            val shareAreas = company?.publicTracking?.shareAreas != false
            TrackingResultDto(
                reference = order.trackingReference,
                status = order.status,
                statusLabel = OrderStatus.label(order.status),
                companyName = company?.name,
                companyLogoUrl = company?.logoUrl,
                brandColor = company?.brandColor,
                supportPhone = company?.phone,
                pickupArea = if (shareAreas) order.pickup.area else null,
                destinationArea = if (shareAreas) order.destination.area else null,
                history = history.takeLast(8).reversed(),
                lastUpdated = order.updatedAt,
                requiresVerification = requireVerification,
                verified = requireVerification,
            )
        }
}

/** Storage in demo mode: the bytes never leave the device, and the UI says so. */
class DemoStorageRepository(
    private val store: DemoStore,
) : com.articals.logistics.data.api.StorageRepository {

    override suspend fun uploadProof(tenantId: String, orderId: String, payload: com.articals.logistics.data.UploadPayload): AppResult<String> =
        store.demoCall {
            store.requireWrite(tenantId, Permissions.OrdersRecordProof)
            validateProof(payload)?.let { throw DemoFailure(it) }
            val path = "proof/$tenantId/$orderId/${payload.fileName}"
            store.audit(
                action = "order.proof_recorded",
                tenantId = tenantId,
                resourceType = "order",
                resourceId = orderId,
                metadata = mapOf("sizeBytes" to payload.sizeBytes.toString(), "simulated" to "true"),
            )
            path
        }

    override suspend fun uploadCompanyLogo(tenantId: String, payload: com.articals.logistics.data.UploadPayload): AppResult<String> =
        store.demoCall {
            store.requireWrite(tenantId, Permissions.CompanySettingsManage)
            com.articals.logistics.data.api.StorageRepository::class
            validateProof(payload)?.let { throw DemoFailure(it) }
            // Demo mode has no bucket: the company keeps the download URL the picker
            // produced, which is enough for the branding screens to be verified.
            store.audit("company.logo_uploaded", tenantId, "company", tenantId)
            "demo-logo://$tenantId/${payload.fileName}"
        }

    override suspend fun uploadBillingEvidence(tenantId: String, payload: com.articals.logistics.data.UploadPayload): AppResult<String> =
        store.demoCall {
            store.requirePlatformAdmin()
            validateProof(payload)?.let { throw DemoFailure(it) }
            "billing/$tenantId/${payload.fileName}"
        }

    /**
     * Real progress, simulated transfer: the values come from the demo control
     * panel's latency so the progress bar behaves like a slow upload rather than
     * jumping straight to 100%.
     */
    override fun observeProofUpload(
        tenantId: String,
        orderId: String,
        payload: com.articals.logistics.data.UploadPayload,
    ): Flow<Double> = kotlinx.coroutines.flow.flow {
        for (step in 1..PROGRESS_STEPS) {
            kotlinx.coroutines.delay(store.controls.state.value.latencyMillis / PROGRESS_STEPS)
            emit(step.toDouble() / PROGRESS_STEPS)
        }
    }

    override fun validateProof(payload: com.articals.logistics.data.UploadPayload): AppError? = when {
        payload.bytes.isEmpty() ->
            AppError.InvalidInput(detail = "file_empty", message = "That photo is empty.")
        payload.sizeBytes > MAX_PROOF_BYTES ->
            AppError.InvalidInput(detail = "file_too_large", message = "Proof photos must be smaller than 5 MB.")
        payload.contentType !in ALLOWED_TYPES ->
            AppError.InvalidInput(detail = "file_type", message = "Proof photos must be a JPEG or PNG image.")
        else -> null
    }

    private companion object {
        const val MAX_PROOF_BYTES = 5 * 1024 * 1024
        const val PROGRESS_STEPS = 12
        val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/svg+xml")
    }
}

/** Shared helper so the demo can report "today" the way the company would. */
internal fun DemoStore.today(tenantId: String): String =
    businessDateKey(currentTimeMillis(), timeZoneOf(tenantId))

/** Parses an ISO instant the way the demo store does, tolerating a missing value. */
internal fun DemoStore.instantOrNull(value: String?): Long? = value?.let(::parseIsoInstantOrNull)

internal fun DemoStore.isoFor(millis: Long): String = isoInstant(millis)
