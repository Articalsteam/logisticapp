package com.articals.logistics.data.repository

import com.articals.logistics.data.api.InsightsRepository
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.DashboardMetricsDto
import com.articals.logistics.data.dto.DashboardMetricsRequest
import com.articals.logistics.data.dto.ExportReportRequest
import com.articals.logistics.data.dto.ReportExportDto

/**
 * Dashboards and exports.
 *
 * Both are backend operations rather than client-side aggregation: the numbers a
 * manager sees must match the numbers used for billing, and a report the owner
 * downloads must be the same one an accountant could reproduce. Financial sections
 * come back empty for roles without the financial permission, instead of being
 * filtered on the device.
 */
class FirebaseInsightsRepository(
    private val caller: FunctionsCaller,
) : InsightsRepository {
    override suspend fun dashboard(
        tenantId: String,
        fromDateKey: String?,
        toDateKey: String?,
        trendDays: Int?,
    ): AppResult<DashboardMetricsDto> = caller.call(
        BackendFunctions.getCompanyDashboardMetrics,
        DashboardMetricsRequest(tenantId, fromDateKey, toDateKey, trendDays),
    )

    /** Builds a CSV on the server and returns where it can be downloaded from. */
    override suspend fun exportReport(request: ExportReportRequest): AppResult<ReportExportDto> = caller.call(
        BackendFunctions.exportCompanyReport,
        request,
        timeoutMillis = FunctionsCaller.REPORT_TIMEOUT_MILLIS,
    )

    /** Recomputes stored daily roll-ups after a back-dated correction. */
    override suspend fun rebuildRollups(tenantId: String, fromDateKey: String, toDateKey: String): AppResult<AckDto> =
        caller.call(
            BackendFunctions.rebuildDailyRollups,
            com.articals.logistics.data.dto.RebuildRollupsRequest(tenantId, fromDateKey, toDateKey),
            timeoutMillis = FunctionsCaller.REPORT_TIMEOUT_MILLIS,
        )
}
