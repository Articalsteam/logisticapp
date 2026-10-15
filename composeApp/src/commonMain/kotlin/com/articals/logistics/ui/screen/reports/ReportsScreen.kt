package com.articals.logistics.ui.screen.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.copyToClipboard
import com.articals.logistics.core.sharePlainText
import com.articals.logistics.data.dto.ReportExportDto
import com.articals.logistics.domain.ReportTypes
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Reports, as CSV.
 *
 * Four reports, a date range and one button. The result is a file the person downloads
 * or shares; where Cloud Storage is not available (demo, or a backend without it) the
 * app hands over the same rows inline instead of pretending a file exists.
 *
 * Who may export is the backend's call — `reports.export` is a permission, so a
 * dispatcher sees the button only if the office granted it.
 */
@Composable
fun ReportsScreen(
    tenantId: String,
    zoneId: String,
    permissions: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId, zoneId) { viewModel.load(tenantId, zoneId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canExport = Permissions.ReportsExport in permissions

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.Settings.reports,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ArticalsStrings.Settings.reportsBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Reports.what) {
            ChoiceChips(
                options = ReportTypes.all,
                selected = state.reportType,
                optionLabel = { ReportTypes.label(it) },
                onSelect = viewModel::setType,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (state.reportType == ReportTypes.FinancialSummary) {
                    ArticalsStrings.Reports.financialNote
                } else {
                    ArticalsStrings.Reports.columnsNote
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Reports.when) {
            ChoiceChips(
                options = listOf(
                    ReportRange.Today,
                    ReportRange.LastSevenDays,
                    ReportRange.LastThirtyDays,
                    ReportRange.ThisMonth,
                    ReportRange.Custom,
                ),
                selected = state.range,
                optionLabel = { ReportsRangeLabels.of(it) },
                onSelect = { viewModel.setRange(it, zoneId) },
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ArticalsTextField(
                    value = state.fromDateKey,
                    onValueChange = viewModel::setFromDateKey,
                    label = ArticalsStrings.Reports.from,
                    enabled = state.range == ReportRange.Custom,
                    modifier = Modifier.weight(1f),
                )
                ArticalsTextField(
                    value = state.toDateKey,
                    onValueChange = viewModel::setToDateKey,
                    label = ArticalsStrings.Reports.to,
                    enabled = state.range == ReportRange.Custom,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!state.rangeValid) {
                Spacer(Modifier.height(Spacing.xs))
                Banner(text = ArticalsStrings.Reports.rangeInvalid, tone = BannerTone.Warning)
            }
        }

        if (canExport) {
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = viewModel::export,
                enabled = state.canExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.exporting) {
                        ArticalsStrings.Settings.exporting
                    } else {
                        ArticalsStrings.Settings.exportReport
                    },
                )
            }
        } else {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = ArticalsStrings.Reports.needsPermission, tone = BannerTone.Info)
        }

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                action = { TextButton(onClick = viewModel::clearResult) { Text(ArticalsStrings.Common.close) } },
            )
        }

        state.result?.let { result ->
            Spacer(Modifier.height(Spacing.md))
            ExportResult(result = result, onDone = viewModel::clearResult)
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

private object ReportsRangeLabels {
    fun of(range: ReportRange) = when (range) {
        ReportRange.Today -> ArticalsStrings.Reports.today
        ReportRange.LastSevenDays -> ArticalsStrings.Reports.lastSevenDays
        ReportRange.LastThirtyDays -> ArticalsStrings.Reports.lastThirtyDays
        ReportRange.ThisMonth -> ArticalsStrings.Reports.thisMonth
        ReportRange.Custom -> ArticalsStrings.Reports.custom
    }
}

/** What came back: how many rows, whether it was capped, and how to get the file. */
@Composable
private fun ExportResult(result: ReportExportDto, onDone: () -> Unit) {
    SectionCard(title = ArticalsStrings.Settings.exported) {
        LabelValueRow(ArticalsStrings.Reports.file, result.fileName)
        LabelValueRow(
            label = ArticalsStrings.Reports.rows,
            value = "${result.rowCount}",
            emphasised = true,
        )

        if (result.truncated) {
            Spacer(Modifier.height(Spacing.xs))
            Banner(text = ArticalsStrings.Settings.exportTruncated, tone = BannerTone.Warning)
        }

        val csv = result.inlineCsv
        val url = result.downloadUrl
        Spacer(Modifier.height(Spacing.sm))

        when {
            url != null -> {
                Text(
                    text = ArticalsStrings.Reports.storageReady,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(
                        onClick = {
                            copyToClipboard(text = url, label = ArticalsStrings.Reports.file)
                        },
                    ) { Text(ArticalsStrings.Common.copy) }
                    TextButton(
                        onClick = {
                            sharePlainText(
                                subject = ArticalsStrings.Settings.reports,
                                text = url,
                            )
                        },
                    ) { Text(ArticalsStrings.Common.share) }
                }
            }

            csv != null -> {
                Text(
                    text = ArticalsStrings.Reports.inlineNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { copyToClipboard(text = csv, label = result.fileName) }) {
                        Text(ArticalsStrings.Common.copy)
                    }
                    TextButton(
                        onClick = {
                            sharePlainText(subject = result.fileName, text = csv)
                        },
                    ) { Text(ArticalsStrings.Common.share) }
                }
            }

            else -> Text(
                text = ArticalsStrings.Reports.emptyFile,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        TextButton(onClick = onDone) { Text(ArticalsStrings.Common.done) }
    }
}
