package com.articals.logistics.ui.screen.workspace

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.Roles
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.ui.component.ArticalsLogo
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.CompanyLogo
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * "You are signed in, but you are not part of a company yet."
 *
 * Two ways forward, and both are real: create a company (which the backend turns into
 * a workspace, an owner membership and a starter rate card), or accept an invitation
 * somebody sent you. There is no third option, and no way to skip into an empty app —
 * every screen in this app needs a tenant.
 */
@Composable
fun NoCompanyScreen(
    onCompanyCreated: (tenantId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanySetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val invitation by viewModel.invitation.collectAsStateWithLifecycle()
    var showInvitation by remember { mutableStateOf(false) }

    LaunchedEffect(state.created?.tenantId) {
        state.created?.tenantId?.takeIf { it.isNotBlank() }?.let(onCompanyCreated)
    }
    LaunchedEffect(invitation.accepted?.tenantId) {
        invitation.accepted?.tenantId?.takeIf { it.isNotBlank() }?.let(onCompanyCreated)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        DemoStrip()
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Spacer(Modifier.height(Spacing.lg))
            ArticalsLogo()
            Spacer(Modifier.height(Spacing.xl))
            Text(ArticalsStrings.Workspace.createCompany, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Workspace.noneBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (showInvitation) {
                SectionCard(title = ArticalsStrings.Workspace.haveInvitation) {
                    ArticalsTextField(
                        value = invitation.invitationId,
                        onValueChange = viewModel::onInvitationIdChange,
                        label = ArticalsStrings.Workspace.invitationId,
                        helper = ArticalsStrings.Workspace.invitationIdHint,
                        error = invitation.error?.userMessage,
                        enabled = !invitation.busy,
                        imeAction = ImeAction.Done,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = viewModel::acceptInvitation,
                        modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                        enabled = invitation.invitationId.isNotBlank() && !invitation.busy,
                    ) { Text(ArticalsStrings.Common.confirm) }
                    TextButton(onClick = { showInvitation = false }) {
                        Text(ArticalsStrings.Workspace.createCompany)
                    }
                }
            } else {
                SectionCard(title = ArticalsStrings.Onboarding.stepCompany) {
                    ArticalsTextField(
                        value = state.companyName,
                        onValueChange = viewModel::onCompanyName,
                        label = ArticalsStrings.Workspace.companyName,
                        error = state.error?.fieldErrors?.get("companyName"),
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    ArticalsTextField(
                        value = state.contactPhone,
                        onValueChange = viewModel::onPhone,
                        label = ArticalsStrings.Settings.customerPhone,
                        error = state.error?.fieldErrors?.get("contactPhone"),
                        enabled = !state.busy,
                        keyboardType = KeyboardType.Phone,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    ArticalsTextField(
                        value = state.contactEmail,
                        onValueChange = viewModel::onEmail,
                        label = ArticalsStrings.Auth.email,
                        error = state.error?.fieldErrors?.get("contactEmail"),
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
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ArticalsTextField(
                            value = state.orderNumberPrefix,
                            onValueChange = viewModel::onPrefix,
                            label = ArticalsStrings.Workspace.orderPrefix,
                            helper = ArticalsStrings.Workspace.orderPrefixHint,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = viewModel::create,
                        modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                        enabled = state.canSubmit,
                    ) {
                        Text(
                            if (state.busy) {
                                ArticalsStrings.Common.saving
                            } else {
                                ArticalsStrings.Workspace.createCompany
                            },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = { showInvitation = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(ArticalsStrings.Workspace.haveInvitation)
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * Choosing between workspaces.
 *
 * A person may work for two companies — a dispatcher who also helps a relative's
 * shop, an accountant across two businesses — and the app keeps them apart completely:
 * separate data, separate role, separate reports. Switching is a deliberate act.
 */
@Composable
fun WorkspacesScreen(
    memberships: List<MembershipDto>,
    activeTenantId: String?,
    companyNames: Map<String, String>,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DemoStrip()
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Spacer(Modifier.height(Spacing.lg))
            Text(ArticalsStrings.Workspace.chooseTitle, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Workspace.chooseBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))

            memberships.forEach { membership ->
                val name = companyNames[membership.tenantId] ?: membership.tenantId
                val isActive = membership.tenantId == activeTenantId
                SectionCard(modifier = Modifier.padding(bottom = Spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompanyLogo(name = name, logoUrl = null, brandColorHex = null)
                        Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${Roles.label(membership.role)}, ${membership.email ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isActive) {
                            Text(
                                text = ArticalsStrings.Workspace.active,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Button(onClick = { onSelect(membership.tenantId) }) {
                                Text(ArticalsStrings.Workspace.switch)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onCreateNew) { Text(ArticalsStrings.Workspace.createCompany) }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
