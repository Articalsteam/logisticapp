package com.articals.logistics.ui.screen.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.isDateKey
import com.articals.logistics.core.shiftDateKey
import com.articals.logistics.core.todayKey
import com.articals.logistics.data.api.InsightsRepository
import com.articals.logistics.data.dto.ExportReportRequest
import com.articals.logistics.data.dto.ReportExportDto
import com.articals.logistics.domain.ReportTypes
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The date ranges people actually ask for, in the company's own days. */
enum class ReportRange { Today, LastSevenDays, LastThirtyDays, ThisMonth, Custom }

data class ReportsState(
    val reportType: String = ReportTypes.Orders,
    val range: ReportRange = ReportRange.LastThirtyDays,
    val fromDateKey: String = "",
    val toDateKey: String = "",
    val exporting: Boolean = false,
    val result: ReportExportDto? = null,
    val error: AppError? = null,
) {
    val rangeValid: Boolean get() = isDateKey(fromDateKey) && isDateKey(toDateKey) && fromDateKey <= toDateKey

    val canExport: Boolean get() = rangeValid && !exporting
}

/**
 * CSV reports.
 *
 * The range is always a pair of *business* dates in the company's time zone, never
 * "the last 24 hours": a report that mixes two days because of the phone's clock is a
 * report somebody will reconcile wrongly.
 *
 * The export is capped by the backend at 5 000 rows and the cap is shown rather than
 * hidden — a truncated report that does not say so is worse than no report.
 */
class ReportsViewModel(
    private val insights: InsightsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = mutableState.asStateFlow()

    private var tenantId: String? = null

    fun load(tenantId: String, zoneId: String) {
        this.tenantId = tenantId
        if (mutableState.value.fromDateKey.isEmpty()) {
            applyRange(ReportRange.LastThirtyDays, zoneId)
        }
    }

    fun setType(type: String) = mutableState.update { it.copy(result = null, reportType = type) }

    fun setRange(range: ReportRange, zoneId: String) {
        applyRange(range, zoneId)
    }

    private fun applyRange(range: ReportRange, zoneId: String) {
        val today = todayKey(zoneId)
        val (from, to) = when (range) {
            ReportRange.Today -> today to today
            ReportRange.LastSevenDays -> shiftDateKey(today, -6, zoneId) to today
            ReportRange.LastThirtyDays -> shiftDateKey(today, -29, zoneId) to today
            ReportRange.ThisMonth -> today.substring(0, 8) + "01" to today
            ReportRange.Custom -> mutableState.value.fromDateKey to mutableState.value.toDateKey
        }
        mutableState.update {
            it.copy(range = range, fromDateKey = from, toDateKey = to, result = null, error = null)
        }
    }

    fun setFromDateKey(value: String) = mutableState.update {
        it.copy(fromDateKey = value, range = ReportRange.Custom, result = null)
    }

    fun setToDateKey(value: String) = mutableState.update {
        it.copy(toDateKey = value, range = ReportRange.Custom, result = null)
    }

    fun clearResult() = mutableState.update { it.copy(result = null, error = null) }

    fun export() {
        val tenantId = tenantId ?: return
        val current = mutableState.value
        if (!current.canExport) return

        mutableState.update { it.copy(exporting = true, error = null, result = null) }
        viewModelScope.launch {
            val request = ExportReportRequest(
                tenantId = tenantId,
                reportType = current.reportType,
                fromDateKey = current.fromDateKey,
                toDateKey = current.toDateKey,
            )
            when (val result = insights.exportReport(request)) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(exporting = false, error = result.error)
                }

                is AppResult.Success -> mutableState.update {
                    it.copy(exporting = false, result = result.value, error = null)
                }
            }
        }
    }
}
