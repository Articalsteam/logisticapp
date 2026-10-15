package com.articals.logistics.ui.screen.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.formatDateKeyLong
import com.articals.logistics.core.formatMinor
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.LedgerEntryDto
import com.articals.logistics.data.dto.PlatformCompanyDto
import com.articals.logistics.data.dto.PlatformOverviewCompaniesDto
import com.articals.logistics.data.dto.UpcomingExpiryDto
import com.articals.logistics.domain.BypassReasons
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.domain.OfflinePaymentMethods
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.ConfirmDialog
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.LabelValueRow
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.component.StateHost
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The platform console.
 *
 * Articals Studio's own screen. It is compiled into the administrator flavour only, and
 * even there it needs the platform claim in the token: a company owner cannot reach it
 * by knowing the route.
 *
 * What it shows is deliberately narrow. Counts, expiries and revenue come from recorded
 * ledger entries rather than from anything the app calculates, and the audit log is
 * read-only — the console can *write* audited actions and read what they left behind,
 * and it can do nothing else.
 */
@Composable
fun AdminOverviewScreen(
    timeZoneId: String,
    onOpenCompany: (String) -> Unit,
    onOpenCompanies: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdminOverviewViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        Text(
            text = ArticalsStrings.Admin.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ArticalsStrings.Admin.overviewBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 4)
            return@Column
        }

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            ErrorState(error = it, onRetry = viewModel::load)
            return@Column
        }

        val overview = state.overview ?: return@Column

        Spacer(Modifier.height(Spacing.md))
        CompaniesCard(counts = overview.companies, onOpenCompanies = onOpenCompanies)

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.revenueAutomated,
            subtitle = ArticalsStrings.Admin.revenueNote,
        ) {
            LabelValueRow(
                label = ArticalsStrings.Admin.revenueAutomated,
                value = formatMinor(overview.revenue.automated.toLong(), "NGN"),
            )
            LabelValueRow(
                label = ArticalsStrings.Admin.revenueOffline,
                value = formatMinor(overview.revenue.offline.toLong(), "NGN"),
            )
            LabelValueRow(
                label = ArticalsStrings.Admin.nonRevenue,
                value = formatMinor(overview.revenue.nonRevenue.toLong(), "NGN"),
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Admin.upcomingExpiries) {
            if (overview.upcomingExpiries.isEmpty()) {
                Text(
                    text = ArticalsStrings.Admin.noExpiries,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                overview.upcomingExpiries.forEach { expiry: UpcomingExpiryDto ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCompany(expiry.tenantId) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = expiry.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = ArticalsStrings.entitlementLabel(expiry.entitlement),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        expiry.daysRemaining?.let { days ->
                            MetaChip(text = "$days ${DaysLeft.of(days)}")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Admin.activeBypasses) {
            if (overview.activeBypasses.isEmpty()) {
                Text(
                    text = ArticalsStrings.Admin.noBypasses,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                overview.activeBypasses.forEach { bypass ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCompany(bypass.tenantId) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = bypass.tenantId, style = MaterialTheme.typography.bodyMedium)
                            bypass.reason?.let { reason ->
                                Text(
                                    text = BypassReasons.label(reason),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        bypass.until?.let { until ->
                            Text(
                                text = formatDateKeyLong(
                                    businessDateKey(parseIsoInstantOrNull(until) ?: 0L, timeZoneId),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = onOpenSettings) { Text(ArticalsStrings.Admin.settings) }
            TextButton(onClick = onOpenAudit) { Text(ArticalsStrings.Admin.audit) }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** "day left"/"days left", in one place so a count always reads properly. */
private object DaysLeft {
    fun of(days: Int) = if (days == 1) ArticalsStrings.Subscription.dayLeft else ArticalsStrings.Subscription.daysLeft
}

@Composable
private fun CompaniesCard(counts: PlatformOverviewCompaniesDto, onOpenCompanies: () -> Unit) {
    SectionCard(
        title = ArticalsStrings.Admin.companies,
        subtitle = ArticalsStrings.Admin.total + ": " + counts.total,
        trailing = {
            TextButton(onClick = onOpenCompanies) { Text(ArticalsStrings.Common.seeAll) }
        },
    ) {
        LabelValueRow(
            ArticalsStrings.Subscription.trialing,
            counts.trialing.toString(),
        )
        LabelValueRow(ArticalsStrings.Subscription.activePaid, counts.activePaid.toString())
        LabelValueRow(ArticalsStrings.Subscription.activeOffline, counts.activeOffline.toString())
        LabelValueRow(ArticalsStrings.Subscription.activeBypass, counts.activeBypass.toString())
        LabelValueRow(ArticalsStrings.Subscription.grace, counts.grace.toString())
        LabelValueRow(ArticalsStrings.Subscription.limited, counts.limited.toString())
        LabelValueRow(ArticalsStrings.Subscription.suspended, counts.suspended.toString())
        LabelValueRow(
            label = ArticalsStrings.Admin.onboardingIncomplete,
            value = counts.onboardingIncomplete.toString(),
        )
    }
}

@Composable
fun AdminCompaniesScreen(
    onOpenCompany: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdminCompaniesViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(ArticalsStrings.Common.back) }
            Text(
                text = ArticalsStrings.Admin.companiesTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        ArticalsTextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            label = ArticalsStrings.Admin.searchHint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen),
        )

        Row(
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ChoiceChips(
                options = listOf<String?>(null) + AdminAccess.entitlementFilters(),
                selected = state.status,
                optionLabel = { it?.let { code -> ArticalsStrings.entitlementLabel(code) } ?: ArticalsStrings.Common.clear },
                onSelect = viewModel::setStatus,
            )
        }

        StateHost(
            state = state.listState,
            modifier = Modifier.fillMaxSize(),
            loading = { SkeletonList(rows = 6) },
            onRetry = viewModel::load,
            emptyTitle = ArticalsStrings.Admin.noCompanies,
            emptyMessage = ArticalsStrings.Admin.noCompaniesBody,
        ) { companies ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items = companies, key = { it.tenantId }) { company ->
                    CompanyRow(company = company, onOpen = { onOpenCompany(company.tenantId) })
                }
            }
        }
    }
}

@Composable
private fun CompanyRow(company: PlatformCompanyDto, onOpen: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = company.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = company.tenantId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MetaChip(text = ArticalsStrings.entitlementLabel(company.entitlement))
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            MetaChip(text = company.status)
            if (company.bypassActive) MetaChip(text = ArticalsStrings.Admin.bypassElevated)
            if (!company.onboardingComplete) MetaChip(text = ArticalsStrings.Admin.onboardingIncomplete)
        }
    }
}

/**
 * One company: its state, and the three things the console can do to it.
 *
 * Payments, bypasses and subscription changes are separate dialogs rather than a row of
 * buttons, because each of them needs a reason and some of them need a date. The ledger
 * and the audit entries underneath are what those actions left behind — the same screen
 * is the receipt for what an administrator just did.
 */
@Composable
fun AdminCompanyScreen(
    tenantId: String,
    company: PlatformCompanyDto?,
    currency: String,
    timeZoneId: String,
    onBack: () -> Unit,
    focusPayments: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: AdminCompanyViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId = tenantId, company = company, zoneId = timeZoneId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(ArticalsStrings.Common.back) }
            Text(
                text = state.company?.name ?: tenantId,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 4)
            return@Column
        }

        state.notice?.let {
            Spacer(Modifier.height(Spacing.sm))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.sm))
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                action = { TextButton(onClick = viewModel::dismissError) { Text(ArticalsStrings.Common.close) } },
            )
        }

        state.company?.let { admin ->
            Spacer(Modifier.height(Spacing.md))
            SectionCard(title = ArticalsStrings.Admin.companiesTitle) {
                LabelValueRow(ArticalsStrings.Settings.company, admin.name)
                LabelValueRow(ArticalsStrings.Admin.entryType, admin.status)
                LabelValueRow(
                    label = ArticalsStrings.Settings.subscriptionStatus,
                    value = ArticalsStrings.entitlementLabel(admin.entitlement),
                )
                admin.planId.takeIf { it != "none" }?.let {
                    LabelValueRow(ArticalsStrings.Subscription.plan, it)
                }
                admin.entitlementEffectiveUntil?.let {
                    LabelValueRow(
                        label = ArticalsStrings.Admin.periodEnd,
                        value = formatDateKeyLong(businessDateKey(parseIsoInstantOrNull(it) ?: 0L, timeZoneId)),
                    )
                }
                LabelValueRow(
                    label = ArticalsStrings.Admin.onboardingIncomplete,
                    value = if (admin.onboardingComplete) {
                        ArticalsStrings.Common.no
                    } else {
                        ArticalsStrings.Common.yes
                    },
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.recordOfflinePayment,
            subtitle = ArticalsStrings.Admin.offlinePaymentExplain,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = { viewModel.openPayment(timeZoneId) },
                    enabled = !state.busy,
                ) { Text(ArticalsStrings.Admin.recordPayment) }
                TextButton(
                    onClick = { viewModel.openSubscription() },
                    enabled = !state.busy,
                ) { Text(ArticalsStrings.Admin.manageSubscription) }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.grantBypass,
            subtitle = ArticalsStrings.Admin.bypassExplain,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = { viewModel.openBypass(timeZoneId) },
                    enabled = !state.busy,
                ) { Text(ArticalsStrings.Admin.grantBypass) }
                if (state.company?.bypassActive == true) {
                    TextButton(onClick = viewModel::askRevoke, enabled = !state.busy) {
                        Text(ArticalsStrings.Admin.revokeBypass)
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.ledger,
            subtitle = ArticalsStrings.Admin.ledgerExplain,
        ) {
            if (state.ledger.isEmpty()) {
                Text(
                    text = ArticalsStrings.Admin.ledgerEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.ledger.forEach { entry: LedgerEntryDto ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = entry.type, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = formatMinor(entry.amountMinor, entry.currency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        entry.reason?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        entry.createdAt?.let {
                            Text(
                                text = relativeTime(
                                    fromMillis = parseIsoInstantOrNull(it) ?: 0L,
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

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.auditTitle,
            subtitle = ArticalsStrings.Admin.auditExplain,
        ) {
            AuditList(
                entries = state.audit,
                timeZoneId = timeZoneId,
                emptyText = ArticalsStrings.Admin.auditEmpty,
            )
        }

        if (focusPayments) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = ArticalsStrings.Admin.offlinePaymentExplain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
    }

    state.payment?.let { draft ->
        OfflinePaymentDialog(
            draft = draft,
            currency = currency,
            saving = state.busy,
            onChange = viewModel::updatePayment,
            onRecord = { viewModel.recordPayment(timeZoneId, currency) },
            onDismiss = viewModel::closePayment,
        )
    }

    state.bypass?.let { draft ->
        BypassDialog(
            draft = draft,
            saving = state.busy,
            onChange = viewModel::updateBypass,
            onGrant = { viewModel.grantBypass(timeZoneId) },
            onDismiss = viewModel::closeBypass,
        )
    }

    state.revoke?.let { draft ->
        ConfirmDialog(
            title = ArticalsStrings.Admin.revokeBypass,
            body = ArticalsStrings.Admin.revokeExplain,
            confirmLabel = ArticalsStrings.Admin.revokeBypass,
            destructive = true,
            reasonLabel = ArticalsStrings.Admin.revokeReason,
            reason = draft.reason,
            onReasonChange = { viewModel.updateRevoke(draft.copy(reason = it)) },
            reasonRequired = true,
            busy = state.busy,
            onConfirm = { viewModel.revokeBypass(timeZoneId) },
            onDismiss = viewModel::cancelRevoke,
        )
    }

    state.subscription?.let { draft ->
        SubscriptionActionDialog(
            draft = draft,
            saving = state.busy,
            onChange = viewModel::updateSubscription,
            onApply = { viewModel.applySubscription(timeZoneId) },
            onDismiss = viewModel::closeSubscription,
        )
    }
}

/** Recording money that arrived outside the app. */
@Composable
private fun OfflinePaymentDialog(
    draft: OfflinePaymentDraft,
    currency: String,
    saving: Boolean,
    onChange: (OfflinePaymentDraft) -> Unit,
    onRecord: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Admin.recordPayment) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = ArticalsStrings.Admin.offlinePaymentExplain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                com.articals.logistics.ui.component.ArticalsAmountField(
                    value = draft.amount,
                    onValueChange = { onChange(draft.copy(amount = it)) },
                    label = ArticalsStrings.Admin.amount,
                    currency = currency,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ChoiceChips(
                    options = OfflinePaymentMethods.all,
                    selected = draft.method,
                    optionLabel = { OfflinePaymentMethods.label(it) },
                    onSelect = { onChange(draft.copy(method = it)) },
                    label = ArticalsStrings.Admin.method,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.reference,
                    onValueChange = { onChange(draft.copy(reference = it)) },
                    label = ArticalsStrings.Admin.externalReference,
                    helper = ArticalsStrings.Admin.referenceExplain,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.receivedDateKey,
                    onValueChange = { onChange(draft.copy(receivedDateKey = it)) },
                    label = ArticalsStrings.Admin.receivedOn,
                    placeholder = ArticalsStrings.Expenses.dateFormat,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.periodStartDateKey,
                    onValueChange = { onChange(draft.copy(periodStartDateKey = it)) },
                    label = ArticalsStrings.Admin.periodStart,
                    placeholder = ArticalsStrings.Expenses.dateFormat,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.periodEndDateKey,
                    onValueChange = { onChange(draft.copy(periodEndDateKey = it)) },
                    label = ArticalsStrings.Admin.periodEnd,
                    placeholder = ArticalsStrings.Expenses.dateFormat,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.reason,
                    onValueChange = { onChange(draft.copy(reason = it)) },
                    label = ArticalsStrings.Admin.reasonForRecord,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.internalNotes,
                    onValueChange = { onChange(draft.copy(internalNotes = it)) },
                    label = ArticalsStrings.Admin.internalNotes,
                    enabled = !saving,
                    singleLine = false,
                    minLines = 2,
                )
                if (!draft.datesValid && (draft.periodStartDateKey.isNotBlank() || draft.periodEndDateKey.isNotBlank())) {
                    Spacer(Modifier.height(Spacing.sm))
                    Banner(text = ArticalsStrings.Expenses.dateInvalid, tone = BannerTone.Warning)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRecord, enabled = draft.valid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Admin.recordPayment)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** Granting access without payment: a reason and an end date, both required. */
@Composable
private fun BypassDialog(
    draft: BypassDraft,
    saving: Boolean,
    onChange: (BypassDraft) -> Unit,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Admin.grantBypass) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = ArticalsStrings.Admin.bypassExplain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Admin.bypassReason,
                    selected = draft.reason,
                    options = AdminAccess.bypassReasons(),
                    optionLabel = { BypassReasons.label(it) },
                    onSelect = { onChange(draft.copy(reason = it)) },
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.untilDateKey,
                    onValueChange = { onChange(draft.copy(untilDateKey = it)) },
                    label = ArticalsStrings.Admin.until,
                    placeholder = ArticalsStrings.Expenses.dateFormat,
                    helper = ArticalsStrings.Admin.bypassMaxDays,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ChoiceChips(
                    options = listOf(false, true),
                    selected = draft.replacesExisting,
                    optionLabel = { if (it) ArticalsStrings.Common.yes else ArticalsStrings.Common.no },
                    onSelect = { onChange(draft.copy(replacesExisting = it)) },
                    label = ArticalsStrings.Admin.replacesExisting,
                )
                Spacer(Modifier.height(Spacing.sm))
                Banner(text = ArticalsStrings.Admin.bypassElevated, tone = BannerTone.Warning)
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.internalNotes,
                    onValueChange = { onChange(draft.copy(internalNotes = it)) },
                    label = ArticalsStrings.Admin.internalNotes,
                    enabled = !saving,
                    singleLine = false,
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onGrant, enabled = draft.valid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Admin.grantBypass)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** One subscription action, with the reason the backend requires. */
@Composable
private fun SubscriptionActionDialog(
    draft: SubscriptionActionDraft,
    saving: Boolean,
    onChange: (SubscriptionActionDraft) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Admin.manageSubscription) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = ArticalsStrings.Admin.actionExplain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Admin.manageSubscription,
                    selected = draft.action,
                    options = SubscriptionActions.Names,
                    optionLabel = { SubscriptionActions.label(it) },
                    onSelect = { onChange(draft.copy(action = it)) },
                    enabled = !saving,
                )
                if (draft.needsPlan) {
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = draft.planId,
                        onValueChange = { onChange(draft.copy(planId = it)) },
                        label = ArticalsStrings.Admin.choosePlan,
                        enabled = !saving,
                    )
                }
                if (draft.needsDays) {
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = draft.extendDays,
                        onValueChange = { onChange(draft.copy(extendDays = it)) },
                        label = ArticalsStrings.Admin.daysToExtend,
                        enabled = !saving,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                }
                if (draft.needsTrialDate) {
                    Spacer(Modifier.height(Spacing.sm))
                    ArticalsTextField(
                        value = draft.trialEndDateKey,
                        onValueChange = { onChange(draft.copy(trialEndDateKey = it)) },
                        label = ArticalsStrings.Admin.trialEnds,
                        placeholder = ArticalsStrings.Expenses.dateFormat,
                        enabled = !saving,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.reason,
                    onValueChange = { onChange(draft.copy(reason = it)) },
                    label = ArticalsStrings.Admin.reasonForRecord,
                    enabled = !saving,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = draft.valid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Admin.applyChange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** The six subscription actions, named once. */
private object SubscriptionActions {
    val Names: List<String> = listOf(
        "activate",
        "suspend",
        "reinstate",
        "extend_period",
        "change_plan",
        "set_trial_end",
    )

    fun label(action: String) = when (action) {
        "activate" -> ArticalsStrings.Admin.actionActivate
        "suspend" -> ArticalsStrings.Admin.actionSuspend
        "reinstate" -> ArticalsStrings.Admin.actionReinstate
        "extend_period" -> ArticalsStrings.Admin.actionExtend
        "change_plan" -> ArticalsStrings.Admin.actionChangePlan
        "set_trial_end" -> ArticalsStrings.Admin.actionSetTrialEnd
        else -> action
    }
}

@Composable
fun AdminSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdminSettingsViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(ArticalsStrings.Common.back) }
            Text(
                text = ArticalsStrings.Admin.settings,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 3)
            return@Column
        }

        state.notice?.let {
            Spacer(Modifier.height(Spacing.sm))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.sm))
            Banner(text = it.userMessage, tone = BannerTone.Danger)
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.entitlementPolicy,
            subtitle = ArticalsStrings.Admin.policyExplain,
        ) {
            ArticalsTextField(
                value = state.policy.trialDays.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { days ->
                        viewModel.setPolicy(state.policy.copy(trialDays = days))
                    }
                },
                label = ArticalsStrings.Admin.trialDays,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = state.policy.graceDays.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { days ->
                        viewModel.setPolicy(state.policy.copy(graceDays = days))
                    }
                },
                label = ArticalsStrings.Admin.graceDays,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = state.policy.bypassMaxDays.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { days ->
                        viewModel.setPolicy(state.policy.copy(bypassMaxDays = days))
                    }
                },
                label = ArticalsStrings.Admin.bypassMaxDays,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = state.policy.standardPeriodDays.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { days ->
                        viewModel.setPolicy(state.policy.copy(standardPeriodDays = days))
                    }
                },
                label = ArticalsStrings.Admin.periodDays,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.Admin.planCatalogue,
            subtitle = ArticalsStrings.Admin.plansExplain,
        ) {
            state.plans.forEach { plan ->
                LabelValueRow(
                    label = plan.name.ifBlank { plan.id },
                    value = formatMinor(plan.priceMinor, plan.currency),
                )
            }
            if (state.plans.isEmpty()) {
                Text(
                    text = ArticalsStrings.Admin.planCatalogueBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = viewModel::save, enabled = state.dirty && !state.saving) {
                Text(if (state.saving) ArticalsStrings.Common.saving else ArticalsStrings.Admin.savePlans)
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
fun AdminAuditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdminAuditViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(ArticalsStrings.Common.back) }
            Text(
                text = ArticalsStrings.Admin.audit,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = ArticalsStrings.Admin.auditExplain,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ArticalsTextField(
                value = state.tenantFilter,
                onValueChange = viewModel::setFilter,
                label = ArticalsStrings.Admin.filterByCompany,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::applyFilter) { Text(ArticalsStrings.Common.search) }
            if (state.tenantId != null) {
                TextButton(onClick = viewModel::clearFilter) { Text(ArticalsStrings.Common.clear) }
            }
        }

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 6)
            return@Column
        }

        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            ErrorState(error = it, onRetry = viewModel::load)
            return@Column
        }

        Spacer(Modifier.height(Spacing.md))
        AuditList(
            entries = state.entries,
            timeZoneId = "UTC",
            emptyText = ArticalsStrings.Admin.auditEmpty,
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** Audit entries, newest first, with who did what to which resource. */
@Composable
private fun AuditList(
    entries: List<AuditEntryDto>,
    timeZoneId: String,
    emptyText: String,
) {
    if (entries.isEmpty()) {
        EmptyState(title = emptyText)
        return
    }
    entries.forEach { entry ->
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = entry.action,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                entry.timestamp?.let { at ->
                    Text(
                        text = relativeTime(
                            fromMillis = parseIsoInstantOrNull(at) ?: 0L,
                            nowMillis = currentTimeMillis(),
                            timeZoneId = timeZoneId,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            entry.reason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                entry.actorRole?.let { MetaChip(text = it) }
                entry.resourceType?.let { MetaChip(text = it) }
                entry.tenantId?.let { MetaChip(text = it) }
            }
        }
    }
}
