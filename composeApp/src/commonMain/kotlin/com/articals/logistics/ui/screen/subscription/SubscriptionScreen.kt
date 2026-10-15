package com.articals.logistics.ui.screen.subscription

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
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.formatDateKeyLong
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.SubscriptionViewDto
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The subscription.
 *
 * There is nothing to buy here. The app shows the state, the date it changes, how the
 * last payment was recorded and how to reach the studio — and, when the state stops
 * writes, says that plainly instead of hiding the buttons and letting people wonder.
 */
@Composable
fun SubscriptionScreen(
    tenantId: String,
    currency: String,
    timeZoneId: String,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) {
        viewModel.load(tenantId)
        viewModel.loadPlans()
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = ArticalsStrings.Subscription.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing) {
                Text(
                    if (state.refreshing) {
                        ArticalsStrings.Common.loading
                    } else {
                        ArticalsStrings.Common.refresh
                    },
                )
            }
        }

        when {
            state.loading -> {
                Spacer(Modifier.height(Spacing.md))
                SkeletonList(rows = 3)
                return@Column
            }

            state.subscription == null -> {
                Spacer(Modifier.height(Spacing.md))
                ErrorState(
                    error = state.error
                        ?: com.articals.logistics.core.AppError.NotFound(
                            message = ArticalsStrings.Subscription.unavailable,
                        ),
                    onRetry = { viewModel.refresh(showLoading = true) },
                )
                return@Column
            }
        }

        val subscription = state.subscription ?: return@Column

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = it.userMessage, tone = BannerTone.Danger)
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Settings.subscriptionStatus) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = ArticalsStrings.Subscription.labelFor(subscription.entitlement),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MetaChip(text = subscription.billingMode)
            }
            Spacer(Modifier.height(Spacing.sm))
            LabelValueRow(
                label = ArticalsStrings.Subscription.plan,
                value = viewModel.planNameFor(subscription),
            )
            LabelValueRow(
                label = ArticalsStrings.Subscription.price,
                value = formatMinor(subscription.planPriceMinor, subscription.planCurrency.ifBlank { currency }),
            )
            subscription.trialEndsAt?.let { trial ->
                LabelValueRow(
                    label = ArticalsStrings.Subscription.trialEndsOn,
                    value = formatDateKeyLong(businessDateKey(parseIsoInstantOrNull(trial) ?: 0L, timeZoneId)),
                )
            }
            subscription.currentPeriodEnd?.let { periodEnd ->
                LabelValueRow(
                    label = ArticalsStrings.Subscription.periodEnds,
                    value = formatDateKeyLong(businessDateKey(parseIsoInstantOrNull(periodEnd) ?: 0L, timeZoneId)),
                )
            }
            subscription.daysRemaining?.let { days ->
                LabelValueRow(
                    label = ArticalsStrings.Subscription.daysLeft,
                    value = days.toString(),
                )
            }
            subscription.billingNotice?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SubscriptionNotices(subscription = subscription)

        subscription.lastOfflinePayment?.let { payment ->
            Spacer(Modifier.height(Spacing.md))
            SectionCard(title = ArticalsStrings.Subscription.lastPayment) {
                LabelValueRow(
                    label = ArticalsStrings.Settings.expenseAmount,
                    value = formatMinor(payment.amountMinor, payment.currency),
                    emphasised = true,
                )
                LabelValueRow(
                    label = ArticalsStrings.Subscription.receivedOn,
                    value = payment.receivedAt?.let {
                        formatDateKeyLong(businessDateKey(parseIsoInstantOrNull(it) ?: 0L, timeZoneId))
                    } ?: ArticalsStrings.Common.notAvailable,
                )
                Text(
                    text = ArticalsStrings.Subscription.paidOffline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        subscription.bypass?.takeIf { it.active }?.let { bypass ->
            Spacer(Modifier.height(Spacing.md))
            SectionCard(title = ArticalsStrings.Subscription.bypassActive) {
                bypass.until?.let {
                    LabelValueRow(
                        label = ArticalsStrings.Subscription.periodEnds,
                        value = formatDateKeyLong(businessDateKey(parseIsoInstantOrNull(it) ?: 0L, timeZoneId)),
                    )
                }
                bypass.reason?.let {
                    LabelValueRow(label = ArticalsStrings.Subscription.reason, value = it)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Subscription.renewTitle) {
            Text(
                text = ArticalsStrings.Subscription.renewBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Subscription.contactUs,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        if (state.plans.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            SectionCard(title = ArticalsStrings.Subscription.planCatalogue) {
                state.plans.forEach { plan: PlanDto ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = plan.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatMinor(plan.priceMinor, plan.currency),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (plan.description.isNotBlank()) {
                            Text(
                                text = plan.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = ArticalsStrings.Subscription.plansNotForSale,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** Whatever the current state means for the person reading it, said out loud. */
@Composable
private fun SubscriptionNotices(subscription: SubscriptionViewDto) {
    val notice = EntitlementStates.notice(subscription.entitlement, subscription.daysRemaining)
    val tone = when (subscription.entitlement) {
        EntitlementStates.Suspended, EntitlementStates.Limited -> BannerTone.Danger
        EntitlementStates.Grace -> BannerTone.Warning
        else -> BannerTone.Info
    }

    if (notice != null) {
        Spacer(Modifier.height(Spacing.md))
        Banner(text = notice, tone = tone)
    }

    if (!subscription.writesAllowed) {
        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Subscription.readOnlyTitle) {
            Text(
                text = ArticalsStrings.Subscription.readOnlyBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
