package com.articals.logistics.ui.screen.tracking

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.data.dto.TrackingHistoryDto
import com.articals.logistics.data.dto.TrackingResultDto
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.CompanyLogo
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.LoadingState
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Tracking, as the customer sees it.
 *
 * The screen explains that once and then behaves exactly like a customer's phone would:
 * a reference, optionally the last four digits of the phone number, and whatever the
 * server is willing to show. There is no staff branch and no extra detail, because a
 * tracking page that behaves differently for the office is a tracking page nobody has
 * actually tested.
 */
@Composable
fun TrackingScreen(
    timeZoneId: String,
    modifier: Modifier = Modifier,
    viewModel: TrackingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.Tracking.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(Spacing.md))
        Banner(text = ArticalsStrings.Tracking.inAppBody, tone = BannerTone.Info)

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Tracking.title) {
            ArticalsTextField(
                value = state.reference,
                onValueChange = viewModel::setReference,
                label = ArticalsStrings.Tracking.reference,
                placeholder = ArticalsStrings.Tracking.referenceHint,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = state.digits,
                onValueChange = viewModel::setDigits,
                label = ArticalsStrings.Tracking.lastFourDigits,
                helper = ArticalsStrings.Tracking.lastFourExplain,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = viewModel::search,
                enabled = state.canSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ArticalsStrings.Tracking.track)
            }
        }

        val resultState = state.resultState
        when {
            resultState == null -> Unit

            state.loading -> {
                Spacer(Modifier.height(Spacing.md))
                LoadingState()
            }

            state.error != null -> {
                Spacer(Modifier.height(Spacing.md))
                ErrorState(error = state.error ?: return@Column, onRetry = viewModel::search)
            }

            else -> state.result?.let { result ->
                Spacer(Modifier.height(Spacing.md))
                TrackingResult(result = result, timeZoneId = timeZoneId)
            } ?: run {
                Spacer(Modifier.height(Spacing.md))
                EmptyState(
                    title = ArticalsStrings.Tracking.notFoundTitle,
                    message = ArticalsStrings.Tracking.notFoundBody,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** What the server was willing to show, laid out the way a customer would read it. */
@Composable
private fun TrackingResult(
    result: TrackingResultDto,
    timeZoneId: String,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompanyLogo(
                name = result.companyName ?: ArticalsStrings.Common.appName,
                logoUrl = result.companyLogoUrl,
                brandColorHex = result.brandColor,
            )
            Spacer(Modifier.height(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.companyName ?: ArticalsStrings.Common.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = result.reference,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MetaChip(text = result.statusLabel.ifBlank { result.status })
        }

        if (result.requiresVerification && !result.verified) {
            Spacer(Modifier.height(Spacing.sm))
            Banner(text = ArticalsStrings.Tracking.lastFourExplain, tone = BannerTone.Warning)
        }
    }

    Spacer(Modifier.height(Spacing.sm))
    SectionCard(title = ArticalsStrings.Tracking.currentStatus) {
        Text(
            text = result.statusLabel.ifBlank { result.status },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        result.lastUpdated?.let { updated ->
            Text(
                text = ArticalsStrings.Tracking.lastUpdated + " · " + relativeTime(
                    fromMillis = parseIsoInstantOrNull(updated) ?: 0L,
                    nowMillis = currentTimeMillis(),
                    timeZoneId = timeZoneId,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.pickupArea?.let {
            LabelValueRow(ArticalsStrings.Pricing.pickup, it)
        }
        result.destinationArea?.let {
            LabelValueRow(ArticalsStrings.Pricing.destination, it)
        }
        result.supportPhone?.takeIf { it.isNotBlank() }?.let {
            LabelValueRow(ArticalsStrings.Subscription.contactUs, it)
        }
    }

    if (result.history.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.sm))
        SectionCard(title = ArticalsStrings.Tracking.history) {
            result.history.forEach { entry: TrackingHistoryDto ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = entry.label.ifBlank { entry.status },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = relativeTime(
                            fromMillis = parseIsoInstantOrNull(entry.at) ?: 0L,
                            nowMillis = currentTimeMillis(),
                            timeZoneId = timeZoneId,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
