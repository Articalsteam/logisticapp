package com.articals.logistics.ui.screen.team

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
import com.articals.logistics.core.Roles
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.ui.component.ArticalsDropdown
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.ConfirmDialog
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.component.SkeletonList
import com.articals.logistics.ui.component.StateHost
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Staff and what they may do.
 *
 * Two things the screen is careful about: the owner's row explains that the owner keeps
 * everything (instead of offering switches that would do nothing), and every change to
 * somebody's role or permissions asks for a reason first.
 */
@Composable
fun TeamScreen(
    tenantId: String,
    currentUserId: String?,
    permissions: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: TeamViewModel = koinViewModel(),
) {
    LaunchedEffect(tenantId) { viewModel.load(tenantId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val canManage = Permissions.StaffManage in permissions
    val canView = Permissions.StaffView in permissions || canManage

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        Column(modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) {
            Text(
                text = ArticalsStrings.Settings.team,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Team.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!canView) {
            Banner(
                text = ArticalsStrings.Team.needsPermission,
                tone = BannerTone.Info,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
            return@Column
        }

        state.notice?.let {
            Banner(
                text = it,
                tone = BannerTone.Success,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                action = { TextButton(onClick = viewModel::clearNotice) { Text(ArticalsStrings.Common.close) } },
            )
        }
        state.error?.let {
            Banner(
                text = it.userMessage,
                tone = BannerTone.Danger,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                action = { TextButton(onClick = viewModel::dismissError) { Text(ArticalsStrings.Common.close) } },
            )
        }

        if (canManage) {
            Row(modifier = Modifier.padding(horizontal = Spacing.screen)) {
                TextButton(onClick = viewModel::openInvite) {
                    Text(ArticalsStrings.Settings.invite)
                }
            }
        }

        StateHost(
            state = state.listState,
            modifier = Modifier.fillMaxSize(),
            loading = { SkeletonList(rows = 4) },
            onRetry = { viewModel.load(tenantId) },
            emptyTitle = ArticalsStrings.Team.empty,
            emptyMessage = ArticalsStrings.Team.emptyBody,
        ) { list ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items = list, key = { it.id }) { member ->
                    MemberRow(
                        member = member,
                        isYou = member.userId == currentUserId,
                        canManage = canManage,
                        onEdit = { viewModel.openEditor(member) },
                        onDisable = { viewModel.askDisable(member) },
                        onEnable = { viewModel.enable(member) },
                    )
                }
            }
        }
    }

    state.invite?.let { draft ->
        InviteDialog(
            draft = draft,
            saving = state.saving,
            onChange = viewModel::updateInvite,
            onSend = viewModel::sendInvite,
            onDismiss = viewModel::closeInvite,
        )
    }

    state.editor?.let { draft ->
        MemberEditorDialog(
            draft = draft,
            groups = viewModel.permissionGroups(),
            saving = state.saving,
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
        )
    }

    state.disabling?.let { member ->
        ConfirmDialog(
            title = ArticalsStrings.Team.disableTitle(member.displayName ?: member.email ?: member.userId),
            body = ArticalsStrings.Team.disableBody,
            confirmLabel = ArticalsStrings.Settings.disable,
            destructive = true,
            reasonLabel = ArticalsStrings.Team.reason,
            reason = state.disableReason,
            onReasonChange = viewModel::setDisableReason,
            reasonRequired = true,
            busy = state.saving,
            onConfirm = viewModel::confirmDisable,
            onDismiss = viewModel::cancelDisable,
        )
    }
}

@Composable
private fun MemberRow(
    member: MembershipDto,
    isYou: Boolean,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
) {
    val overrideCount = member.grantedPermissions.size + member.revokedPermissions.size
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.displayName ?: member.email ?: member.userId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isYou) {
                        Spacer(Modifier.height(Spacing.xs))
                        MetaChip(text = ArticalsStrings.Team.youLabel)
                    }
                }
                member.email?.takeIf { it.isNotBlank() }?.let { email ->
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MetaChip(text = Roles.label(member.role))
                if (member.status != "active") {
                    Spacer(Modifier.height(Spacing.xs))
                    MetaChip(text = ArticalsStrings.Team.disabled)
                }
            }
        }

        if (overrideCount > 0) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Team.overrides(overrideCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (member.role == Roles.Owner) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Team.ownerKeepsEverything,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (canManage) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = onEdit) { Text(ArticalsStrings.Settings.changeRole) }
                if (member.status == "active") {
                    TextButton(onClick = onDisable, enabled = !isYou) {
                        Text(ArticalsStrings.Settings.disable)
                    }
                } else {
                    TextButton(onClick = onEnable) { Text(ArticalsStrings.Settings.enable) }
                }
            }
        }
    }
}

@Composable
private fun InviteDialog(
    draft: InviteDraft,
    saving: Boolean,
    onChange: (InviteDraft) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ArticalsStrings.Team.inviteTitle) },
        text = {
            Column {
                Text(
                    text = ArticalsStrings.Team.inviteBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.email,
                    onValueChange = { onChange(draft.copy(email = it)) },
                    label = ArticalsStrings.Settings.inviteEmail,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.displayName,
                    onValueChange = { onChange(draft.copy(displayName = it)) },
                    label = ArticalsStrings.Team.displayName,
                    enabled = !saving,
                )
                Spacer(Modifier.height(Spacing.sm))
                ArticalsDropdown(
                    label = ArticalsStrings.Team.role,
                    selected = draft.role,
                    options = listOf(Roles.Manager, Roles.Dispatcher, Roles.Rider),
                    optionLabel = { Roles.label(it) },
                    onSelect = { onChange(draft.copy(role = it)) },
                    enabled = !saving,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSend, enabled = draft.emailValid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Settings.inviteSend)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/**
 * Role and permission overrides for one person.
 *
 * The permission list is the backend's catalogue, grouped; each permission is one of
 * three answers, and "as the role says" is the default so an override is always a
 * visible decision rather than a side effect of saving a form.
 */
@Composable
private fun MemberEditorDialog(
    draft: MemberDraft,
    groups: List<Pair<String, List<String>>>,
    saving: Boolean,
    onChange: (MemberDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isOwner = draft.role == Roles.Owner
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(draft.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = listOf(Roles.Owner, Roles.Manager, Roles.Dispatcher, Roles.Rider),
                    selected = draft.role,
                    optionLabel = { Roles.label(it) },
                    onSelect = { onChange(draft.copy(role = it)) },
                    label = ArticalsStrings.Team.role,
                )

                if (isOwner) {
                    Spacer(Modifier.height(Spacing.sm))
                    Banner(text = ArticalsStrings.Team.ownerKeepsEverything, tone = BannerTone.Info)
                } else {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = ArticalsStrings.Team.permissionBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    groups.forEach { (group, permissions) ->
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        permissions.forEach { permission ->
                            ChoiceChips(
                                options = listOf(
                                    PermissionChoice.Default,
                                    PermissionChoice.Allow,
                                    PermissionChoice.Deny,
                                ),
                                selected = draft.overrides[permission] ?: PermissionChoice.Default,
                                optionLabel = {
                                    when (it) {
                                        PermissionChoice.Default -> ArticalsStrings.Team.defaultChoice
                                        PermissionChoice.Allow -> ArticalsStrings.Team.allowChoice
                                        PermissionChoice.Deny -> ArticalsStrings.Team.denyChoice
                                    }
                                },
                                onSelect = { choice ->
                                    val overrides = draft.overrides.toMutableMap()
                                    if (choice == PermissionChoice.Default) {
                                        overrides.remove(permission)
                                    } else {
                                        overrides[permission] = choice
                                    }
                                    onChange(draft.copy(overrides = overrides))
                                },
                                label = ArticalsStrings.permissionLabel(permission),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                ArticalsTextField(
                    value = draft.reason,
                    onValueChange = { onChange(draft.copy(reason = it)) },
                    label = ArticalsStrings.Team.reason,
                    helper = ArticalsStrings.Team.reasonExplain,
                    enabled = !saving,
                    isError = draft.changed && !draft.reasonValid,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.reasonValid && !saving) {
                Text(if (saving) ArticalsStrings.Common.saving else ArticalsStrings.Common.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}
