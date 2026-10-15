package com.articals.logistics.ui.screen.onboarding

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.Roles
import com.articals.logistics.ui.component.ArticalsLogo
import com.articals.logistics.ui.component.ArticalsAmountField
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Setting up a new company.
 *
 * Four short steps, each one writing what it collects, each one skippable. The wizard
 * is what turns an empty workspace into one that can price a delivery — which is why
 * the rates step is in the middle and not buried in settings.
 */
@Composable
fun OnboardingScreen(
    tenantId: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DemoStrip()
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Spacer(Modifier.height(Spacing.lg))
            ArticalsLogo(compact = true)
            Spacer(Modifier.height(Spacing.lg))
            Text(ArticalsStrings.Onboarding.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Onboarding.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            LinearProgressIndicator(
                progress = { state.stepNumber.toFloat() / OnboardingStep.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.lg))

            state.error?.let { error ->
                Banner(
                    text = error.userMessage,
                    title = ArticalsStrings.Errors.titleFor(error),
                    tone = BannerTone.Danger,
                )
                Spacer(Modifier.height(Spacing.md))
            }

            when (state.step) {
                OnboardingStep.CompanyDetails -> CompanyDetailsStep(state, viewModel)
                OnboardingStep.Rates -> RatesStep(state, viewModel)
                OnboardingStep.Team -> TeamStep(state, viewModel)
                OnboardingStep.Finished -> FinishedStep(state, viewModel)
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun CompanyDetailsStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    SectionCard(title = ArticalsStrings.Onboarding.stepCompany) {
        ArticalsTextField(
            value = state.companyName,
            onValueChange = viewModel::onCompanyName,
            label = ArticalsStrings.Workspace.companyName,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.contactPhone,
            onValueChange = viewModel::onPhone,
            label = ArticalsStrings.Settings.customerPhone,
            enabled = !state.busy,
            keyboardType = KeyboardType.Phone,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.contactEmail,
            onValueChange = viewModel::onEmail,
            label = ArticalsStrings.Auth.email,
            enabled = !state.busy,
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.businessAddress,
            onValueChange = viewModel::onAddress,
            label = ArticalsStrings.Workspace.businessAddress,
            helper = ArticalsStrings.Common.optional,
            enabled = !state.busy,
            singleLine = false,
            minLines = 2,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.serviceAreas,
            onValueChange = viewModel::onServiceAreas,
            label = ArticalsStrings.Workspace.serviceAreas,
            helper = ArticalsStrings.Workspace.serviceAreasHint,
            enabled = !state.busy,
        )
    }
    Spacer(Modifier.height(Spacing.lg))
    Button(
        onClick = viewModel::saveCompanyDetails,
        modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
        enabled = state.canContinueFromCompany && !state.busy,
    ) { Text(ArticalsStrings.Onboarding.next) }
}

@Composable
private fun RatesStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    SectionCard(
        title = ArticalsStrings.Onboarding.stepPricing,
        subtitle = ArticalsStrings.Pricing.subtitle,
    ) {
        ArticalsAmountField(
            value = state.baseCharge,
            onValueChange = viewModel::onBaseCharge,
            label = ArticalsStrings.Settings.baseCharge,
            currency = state.currency,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsAmountField(
            value = state.perKm,
            onValueChange = viewModel::onPerKm,
            label = ArticalsStrings.Settings.perKm,
            currency = state.currency,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsAmountField(
            value = state.minimumCharge,
            onValueChange = viewModel::onMinimumCharge,
            label = ArticalsStrings.Settings.minimumCharge,
            currency = state.currency,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsAmountField(
            value = state.riderFixedFee,
            onValueChange = viewModel::onRiderFee,
            label = ArticalsStrings.Settings.riderFixedFee,
            currency = state.currency,
            helper = ArticalsStrings.Pricing.riderCostAdjust,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.targetMarginPercent,
            onValueChange = viewModel::onTargetMargin,
            label = ArticalsStrings.Settings.targetMargin,
            helper = ArticalsStrings.Settings.targetMarginExplain,
            enabled = !state.busy,
            keyboardType = KeyboardType.Decimal,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = ArticalsStrings.Onboarding.placeholderRates,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Spacing.lg))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = viewModel::back, enabled = !state.busy) {
            Text(ArticalsStrings.Onboarding.previous)
        }
        Button(
            onClick = viewModel::saveRates,
            modifier = Modifier.weight(1f).height(Spacing.touchTarget),
            enabled = state.canContinueFromRates && !state.busy,
        ) { Text(ArticalsStrings.Onboarding.next) }
    }
}

@Composable
private fun TeamStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    SectionCard(
        title = ArticalsStrings.Onboarding.stepTeam,
        subtitle = ArticalsStrings.Settings.teamEmptyBody,
    ) {
        if (state.inviteSent) {
            Banner(text = ArticalsStrings.Settings.inviteSent, tone = BannerTone.Success)
            Spacer(Modifier.height(Spacing.md))
        }
        ArticalsTextField(
            value = state.inviteEmail,
            onValueChange = viewModel::onInviteEmail,
            label = ArticalsStrings.Settings.inviteEmail,
            enabled = !state.busy,
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsDropdown(
            label = ArticalsStrings.Settings.inviteRole,
            selected = state.inviteRole,
            options = Roles.assignable.filter { it != Roles.Owner },
            optionLabel = ::roleLabel,
            onSelect = viewModel::onInviteRole,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = viewModel::invite,
            modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
            enabled = state.inviteEmail.isNotBlank() && !state.busy,
        ) { Text(ArticalsStrings.Settings.inviteSend) }
    }
    Spacer(Modifier.height(Spacing.lg))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = viewModel::back, enabled = !state.busy) {
            Text(ArticalsStrings.Onboarding.previous)
        }
        Button(
            onClick = viewModel::skipTeamStep,
            modifier = Modifier.weight(1f).height(Spacing.touchTarget),
            enabled = !state.busy,
        ) { Text(ArticalsStrings.Onboarding.skipped) }
    }
}

@Composable
private fun FinishedStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    SectionCard(title = ArticalsStrings.Onboarding.completedTitle, subtitle = ArticalsStrings.Onboarding.completedBody) {
        Text(
            text = "${ArticalsStrings.Settings.riderCost}: ${state.riderFixedFee.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "${ArticalsStrings.Settings.version}: ${state.pricingVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Spacing.lg))
    Button(
        onClick = viewModel::finish,
        modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
        enabled = !state.busy,
    ) { Text(ArticalsStrings.Onboarding.finish) }
}

private fun roleLabel(role: String): String = when (role) {
    Roles.Manager -> ArticalsStrings.Settings.roleManager
    Roles.Dispatcher -> ArticalsStrings.Settings.roleDispatcher
    else -> Roles.label(role)
}
