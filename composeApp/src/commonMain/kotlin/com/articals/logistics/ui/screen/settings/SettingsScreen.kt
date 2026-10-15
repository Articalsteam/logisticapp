package com.articals.logistics.ui.screen.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.articals.logistics.ui.component.ConfirmDialog
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.BuildFooterCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The "More" tab: who you are, what company you are in, where everything else lives.
 *
 * A row appears only when the screen behind it exists and this role may open it —
 * [onOpenPricing] and its neighbours are nullable for exactly that reason. A menu of
 * buttons that do nothing would be the one thing this app must never ship, so the
 * menu is assembled by the navigation host, which knows both facts.
 */
@Composable
fun SettingsScreen(
    signedInAs: String?,
    companyName: String?,
    roleLabel: String?,
    entitlement: String?,
    canSwitchCompany: Boolean,
    currency: String,
    onOpenNotifications: (() -> Unit)? = null,
    onOpenPricing: (() -> Unit)? = null,
    onOpenTeam: (() -> Unit)? = null,
    onOpenRiders: (() -> Unit)? = null,
    onOpenCustomers: (() -> Unit)? = null,
    onOpenExpenses: (() -> Unit)? = null,
    onOpenReports: (() -> Unit)? = null,
    onOpenCompanySettings: (() -> Unit)? = null,
    onOpenSubscription: (() -> Unit)? = null,
    onOpenTracking: (() -> Unit)? = null,
    onOpenConsole: (() -> Unit)? = null,
    onSwitchCompany: (() -> Unit)? = null,
    onOpenDemoControls: (() -> Unit)? = null,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Shell.company, subtitle = companyName) {
            if (signedInAs != null) {
                LabelRow(ArticalsStrings.Shell.youAre, signedInAs)
            }
            if (roleLabel != null) {
                LabelRow(ArticalsStrings.Settings.inviteRole, roleLabel)
            }
            if (entitlement != null) {
                LabelRow(ArticalsStrings.Subscription.plan, ArticalsStrings.Subscription.labelFor(entitlement))
            }
            LabelRow(ArticalsStrings.Settings.currency, currency)
            if (canSwitchCompany && onSwitchCompany != null) {
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = onSwitchCompany) { Text(ArticalsStrings.Shell.switchCompany) }
                Text(
                    text = ArticalsStrings.Shell.switchCompanyBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MenuSection(
            title = ArticalsStrings.Shell.sectionWork,
            rows = listOf(
                MenuRow(ArticalsStrings.Settings.customers, ArticalsStrings.Settings.customersEmptyBody, onOpenCustomers),
                MenuRow(ArticalsStrings.Settings.riders, ArticalsStrings.Settings.ridersEmptyBody, onOpenRiders),
                MenuRow(ArticalsStrings.Tracking.title, ArticalsStrings.Tracking.lookupBody, onOpenTracking),
                MenuRow(ArticalsStrings.Settings.notifications, ArticalsStrings.Shell.notificationsBody, onOpenNotifications),
            ),
        )

        MenuSection(
            title = ArticalsStrings.Shell.sectionMoney,
            rows = listOf(
                MenuRow(ArticalsStrings.Settings.pricing, ArticalsStrings.Settings.pricingSubtitle, onOpenPricing),
                MenuRow(ArticalsStrings.Settings.expenses, ArticalsStrings.Settings.expensesSubtitle, onOpenExpenses),
                MenuRow(ArticalsStrings.Settings.reports, ArticalsStrings.Settings.reportsSubtitle, onOpenReports),
                MenuRow(ArticalsStrings.Subscription.title, ArticalsStrings.Subscription.renewBody, onOpenSubscription),
            ),
        )

        MenuSection(
            title = ArticalsStrings.Shell.sectionCompany,
            rows = listOf(
                MenuRow(ArticalsStrings.Settings.companyDetails, ArticalsStrings.Settings.companySubtitle, onOpenCompanySettings),
                MenuRow(ArticalsStrings.Settings.team, ArticalsStrings.Settings.teamEmptyBody, onOpenTeam),
            ),
        )

        if (onOpenConsole != null) {
            MenuSection(
                title = ArticalsStrings.Shell.sectionThisBuild,
                rows = listOf(
                    MenuRow(ArticalsStrings.Admin.title, ArticalsStrings.Admin.overviewBody, onOpenConsole),
                ),
            )
        }

        if (onOpenDemoControls != null) {
            Spacer(Modifier.height(Spacing.md))
            SectionCard(
                title = ArticalsStrings.Diagnostics.title,
                subtitle = ArticalsStrings.Diagnostics.intro,
            ) {
                TextButton(onClick = onOpenDemoControls) { Text(ArticalsStrings.Diagnostics.title) }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        BuildFooterCard(
            signedInAs = signedInAs,
            workspace = companyName,
            entitlement = entitlement,
        )

        Spacer(Modifier.height(Spacing.lg))
        TextButton(
            onClick = { confirmSignOut = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(ArticalsStrings.Auth.signOut) }
        Spacer(Modifier.height(Spacing.xxl))
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = ArticalsStrings.Auth.signOutConfirmTitle,
            body = ArticalsStrings.Auth.signOutConfirmBody,
            confirmLabel = ArticalsStrings.Auth.signOut,
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** One destination, or nothing at all when the screen is not there yet. */
private data class MenuRow(
    val title: String,
    val subtitle: String,
    val onClick: (() -> Unit)?,
)

@Composable
private fun MenuSection(title: String, rows: List<MenuRow>) {
    val available = rows.filter { it.onClick != null }
    if (available.isEmpty()) return

    Spacer(Modifier.height(Spacing.md))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(Spacing.sm))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            available.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { row.onClick?.invoke() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetaChip(text = value)
    }
}
