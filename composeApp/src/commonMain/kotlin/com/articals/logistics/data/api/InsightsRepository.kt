package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.DashboardMetricsDto
import com.articals.logistics.data.dto.ExportReportRequest
import com.articals.logistics.data.dto.ReportExportDto

/**
 * Dashboards and exports.
 *
 * Both are computed where the money is: the numbers a manager sees must match the
 * numbers used for billing, and financial sections come back empty for roles
 * without the financial permission rather than being filtered on the device.
 */
interface InsightsRepository {
    suspend fun dashboard(
        tenantId: String,
        fromDateKey: String? = null,
        toDateKey: String? = null,
        trendDays: Int? = null,
    ): AppResult<DashboardMetricsDto>

    suspend fun exportReport(request: ExportReportRequest): AppResult<ReportExportDto>

    suspend fun rebuildRollups(tenantId: String, fromDateKey: String, toDateKey: String): AppResult<AckDto>
}
