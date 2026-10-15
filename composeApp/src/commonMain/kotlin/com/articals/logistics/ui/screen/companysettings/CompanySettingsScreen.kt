package com.articals.logistics.ui.screen.companysettings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.Permissions
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.CompanyLogo
import com.articals.logistics.ui.component.ErrorState
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The company's own details.
 *
 * The order is the order a customer experiences it: the name and logo they see, how to
 * reach the company, where it delivers, how its deliveries are numbered, and finally
 * what the public tracking page is allowed to reveal.
 */
@Composable
fun CompanySettingsScreen(
    tenantId: String,
    permissions: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: CompanySettingsViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.CompanySettingsManage in permissions

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.CompanySettings.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = ArticalsStrings.CompanySettings.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading) {
            Spacer(Modifier.height(Spacing.md))
            SkeletonList(rows = 4)
            return@Column
        }

        val company = state.company
        val draft = state.draft
        if (company == null || draft == null) {
            Spacer(Modifier.height(Spacing.md))
            ErrorState(
                error = state.error
                    ?: com.articals.logistics.core.AppError.NotFound(
                        message = ArticalsStrings.CompanySettings.missing,
                    ),
                onRetry = { viewModel.load(tenantId) },
            )
            return@Column
        }

        val enabled = canManage && !state.saving

        if (!canManage) {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = ArticalsStrings.CompanySettings.readOnly, tone = BannerTone.Info)
        }

        state.notice?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(
                text = it,
                tone = BannerTone.Success,
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.md))
            Banner(text = it.userMessage, tone = BannerTone.Danger)
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Settings.companyDetails) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompanyLogo(
                    name = draft.name,
                    logoUrl = draft.logoUrl.takeIf { it.isNotBlank() },
                    brandColorHex = draft.brandColorHex.takeIf { it.isNotBlank() },
                )
                Spacer(Modifier.height(Spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = draft.name.ifBlank { company.name },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MetaChip(text = company.status)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.name,
                onValueChange = { viewModel.updateDraft(draft.copy(name = it)) },
                label = ArticalsStrings.Settings.company,
                enabled = enabled,
                isError = draft.name.isNotEmpty() && !draft.nameValid,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.phone,
                onValueChange = { viewModel.updateDraft(draft.copy(phone = it)) },
                label = ArticalsStrings.Settings.customerPhone,
                enabled = enabled,
                isError = draft.phone.isNotEmpty() && !draft.phoneValid,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.email,
                onValueChange = { viewModel.updateDraft(draft.copy(email = it)) },
                label = ArticalsStrings.Settings.customerEmail,
                enabled = enabled,
                isError = draft.email.isNotEmpty() && !draft.emailValid,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.address,
                onValueChange = { viewModel.updateDraft(draft.copy(address = it)) },
                label = ArticalsStrings.Pricing.address,
                enabled = enabled,
                singleLine = false,
                minLines = 2,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.CompanySettings.logo) {
            Text(
                text = ArticalsStrings.CompanySettings.logoExplain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = viewModel::captureLogo,
                enabled = enabled && !state.uploadingLogo,
            ) {
                Text(
                    if (state.uploadingLogo) {
                        ArticalsStrings.CompanySettings.logoUploading
                    } else {
                        ArticalsStrings.CompanySettings.logoTakePhoto
                    },
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.logoUrl,
                onValueChange = { viewModel.updateDraft(draft.copy(logoUrl = it)) },
                label = ArticalsStrings.CompanySettings.logoUrl,
                enabled = enabled,
            )
            Spacer(Modifier.height(Spacing.sm))
            ArticalsTextField(
                value = draft.brandColorHex,
                onValueChange = { viewModel.updateDraft(draft.copy(brandColorHex = it)) },
                label = ArticalsStrings.CompanySettings.brandColour,
                enabled = enabled,
                helper = ArticalsStrings.CompanySettings.brandColourExplain,
                error = if (draft.brandColorHex.isNotEmpty() && !draft.brandColourValid) {
                    ArticalsStrings.CompanySettings.brandColourInvalid
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.CompanySettings.serviceAreas,
            subtitle = ArticalsStrings.CompanySettings.serviceAreasExplain,
        ) {
            if (draft.serviceAreas.isEmpty()) {
                Text(
                    text = ArticalsStrings.CompanySettings.noAreas,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                draft.serviceAreas.forEach { area ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = area, style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { viewModel.removeArea(area) },
                            enabled = enabled,
                        ) { Text(ArticalsStrings.Common.delete) }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ArticalsTextField(
                    value = draft.areaInput,
                    onValueChange = { viewModel.updateDraft(draft.copy(areaInput = it)) },
                    label = ArticalsStrings.CompanySettings.addArea,
                    placeholder = ArticalsStrings.CompanySettings.areaHint,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::addArea, enabled = enabled && draft.areaInput.isNotBlank()) {
                    Text(ArticalsStrings.Common.save)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.CompanySettings.orderNumberPrefix) {
            ArticalsTextField(
                value = draft.orderNumberPrefix,
                onValueChange = { viewModel.updateDraft(draft.copy(orderNumberPrefix = it)) },
                label = ArticalsStrings.CompanySettings.orderNumberPrefix,
                enabled = enabled,
                helper = ArticalsStrings.CompanySettings.orderNumberPrefixExplain,
                error = if (draft.orderNumberPrefix.isNotEmpty() && !draft.prefixValid) {
                    ArticalsStrings.CompanySettings.prefixInvalid
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(
            title = ArticalsStrings.CompanySettings.trackingTitle,
            subtitle = ArticalsStrings.CompanySettings.trackingExplain,
        ) {
            ChoiceChips(
                options = listOf(true, false),
                selected = draft.shareAreasOnTracking,
                optionLabel = { if (it) ArticalsStrings.Common.yes else ArticalsStrings.Common.no },
                onSelect = { viewModel.updateDraft(draft.copy(shareAreasOnTracking = it)) },
                label = ArticalsStrings.CompanySettings.shareAreas,
            )
            Spacer(Modifier.height(Spacing.sm))
            ChoiceChips(
                options = listOf(true, false),
                selected = draft.requireVerificationOnTracking,
                optionLabel = { if (it) ArticalsStrings.Common.yes else ArticalsStrings.Common.no },
                onSelect = { viewModel.updateDraft(draft.copy(requireVerificationOnTracking = it)) },
                label = ArticalsStrings.CompanySettings.requireVerification,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.CompanySettings.requireVerificationExplain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = viewModel::save,
                enabled = canManage && draft.valid && state.dirty && !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.saving) ArticalsStrings.Common.saving else ArticalsStrings.Common.save)
            }
            if (state.dirty) {
                TextButton(onClick = viewModel::discardChanges, enabled = !state.saving) {
                    Text(ArticalsStrings.PricingSettings.discard)
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}
