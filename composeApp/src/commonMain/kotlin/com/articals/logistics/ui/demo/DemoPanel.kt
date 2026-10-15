package com.articals.logistics.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.articals.logistics.core.AppError
import com.articals.logistics.data.api.DemoNetworkMode
import com.articals.logistics.data.api.DiagnosticsState
import com.articals.logistics.domain.EntitlementStates
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.ChoiceChips
import com.articals.logistics.ui.component.ConfirmDialog
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import com.articals.logistics.di.SupportLocator

/**
 * The demo control panel.
 *
 * Only ever composed when [DemoActions.active] is true. Its job is to make every
 * state the brief asks for reachable on purpose — loading, empty, error, offline and
 * success — instead of hoping to be lucky with a real network.
 *
 * Everything it does is honest: "slow" really does delay the call, "offline" really
 * does fail it with the offline error a phone would produce, and the reset really
 * does rebuild the seeded world.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoControlsSheet(
    state: DiagnosticsState,
    actions: DemoActions,
    onNetworkChange: (DemoNetworkMode) -> Unit,
    onLatencyChange: (Long) -> Unit,
    onErrorChange: (AppError?) -> Unit,
    onEntitlementChange: (String?) -> Unit,
    onLoadEmptyCompany: () -> Unit,
    onReset: () -> Unit,
    onDarkModeChange: (Boolean?) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xxl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(ArticalsStrings.Diagnostics.title, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = ArticalsStrings.Common.close)
                }
            }
            Text(
                text = ArticalsStrings.Diagnostics.intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))

            // --- network -----------------------------------------------------
            SectionCard(title = ArticalsStrings.Diagnostics.network) {
                ChoiceChips(
                    options = DemoNetworkMode.entries.toList(),
                    selected = state.network,
                    optionLabel = { mode ->
                        when (mode) {
                            DemoNetworkMode.NORMAL -> ArticalsStrings.Diagnostics.networkNormal
                            DemoNetworkMode.SLOW -> ArticalsStrings.Diagnostics.networkSlow
                            DemoNetworkMode.OFFLINE -> ArticalsStrings.Diagnostics.networkOffline
                            DemoNetworkMode.FLAKY -> ArticalsStrings.Diagnostics.networkFlaky
                        }
                    },
                    onSelect = onNetworkChange,
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "${ArticalsStrings.Diagnostics.latency} ${state.latencyMillis} ms",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.latencyMillis.toFloat(),
                    onValueChange = { onLatencyChange(it.toLong()) },
                    valueRange = 0f..2_000f,
                    steps = 19,
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // --- forced errors ------------------------------------------------
            SectionCard(title = ArticalsStrings.Diagnostics.forceError) {
                ChoiceChips(
                    options = FORCED_ERRORS,
                    selected = state.forcedError,
                    optionLabel = ::forcedErrorLabel,
                    onSelect = onErrorChange,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Diagnostics.forcedErrorHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // --- entitlement ---------------------------------------------------
            SectionCard(
                title = ArticalsStrings.Diagnostics.entitlement,
                subtitle = "Every subscription state, including the read-only ones.",
            ) {
                ChoiceChips(
                    options = listOf(ENTITLEMENT_DERIVED) + EntitlementStates.all,
                    selected = state.entitlementOverride ?: ENTITLEMENT_DERIVED,
                    optionLabel = { value ->
                        if (value == ENTITLEMENT_DERIVED) {
                            ArticalsStrings.Diagnostics.entitlementDerived
                        } else {
                            EntitlementStates.label(value)
                        }
                    },
                    onSelect = { value -> onEntitlementChange(value.takeIf { it != ENTITLEMENT_DERIVED }) },
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // --- data ----------------------------------------------------------
            SectionCard(title = "Data") {
                Button(
                    onClick = onLoadEmptyCompany,
                    modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                ) { Text(ArticalsStrings.Diagnostics.emptyCompany) }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Diagnostics.emptyCompanyBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                TextButton(onClick = { confirmReset = true }) {
                    Text(
                        text = ArticalsStrings.Diagnostics.reset,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // --- appearance -----------------------------------------------------
            SectionCard(title = ArticalsStrings.Diagnostics.appearance) {
                ChoiceChips(
                    options = listOf<Boolean?>(null, false, true),
                    selected = state.darkMode,
                    optionLabel = { value ->
                        when (value) {
                            null -> ArticalsStrings.Diagnostics.darkModeSystem
                            true -> ArticalsStrings.Diagnostics.darkModeOn
                            false -> ArticalsStrings.Diagnostics.darkModeOff
                        }
                    },
                    onSelect = onDarkModeChange,
                )
                Spacer(Modifier.height(Spacing.md))
                Text(ArticalsStrings.Diagnostics.fontScale, style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = FONT_SCALES,
                    selected = state.fontScale,
                    optionLabel = ::fontScaleLabel,
                    onSelect = onFontScaleChange,
                )
            }

            Spacer(Modifier.height(Spacing.md))
            BuildFooterCard()

            if (confirmReset) {
                ConfirmDialog(
                    title = ArticalsStrings.Diagnostics.resetConfirmTitle,
                    body = ArticalsStrings.Diagnostics.resetConfirmBody,
                    confirmLabel = ArticalsStrings.Diagnostics.reset,
                    destructive = true,
                    onConfirm = {
                        confirmReset = false
                        onReset()
                        onDismiss()
                    },
                    onDismiss = { confirmReset = false },
                )
            }
        }
    }
}

/** Who is signed in, which workspace, and what this build is — the support footer. */
@Composable
fun BuildFooterCard(
    signedInAs: String? = null,
    workspace: String? = null,
    entitlement: String? = null,
) {
    val config = SupportLocator.config()
    SectionCard(title = ArticalsStrings.Diagnostics.buildFooter) {
        FooterLine("Mode", if (config.mode.isDemo) "DEMO" else "FIREBASE")
        FooterLine("Firebase configured", if (config.firebaseConfigured) "yes" else "no")
        FooterLine("Admin console", if (config.adminFlavor) "in this build" else "not in this build")
        FooterLine("Diagnostics", if (config.allowDiagnostics) "available" else "hidden")
        FooterLine("Platform", com.articals.logistics.core.platformName())
        FooterLine("Version", com.articals.logistics.core.appVersionName())
        if (signedInAs != null) FooterLine("Signed in", signedInAs)
        if (workspace != null) FooterLine("Workspace", workspace)
        if (entitlement != null) FooterLine(ArticalsStrings.Diagnostics.entitlementNow, entitlement)
        if (config.mode.isDemo) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = ArticalsStrings.Diagnostics.noFirebase,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FooterLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** The strip along the top of the app in a demo build. */
@Composable
fun DemoStrip(modifier: Modifier = Modifier, compact: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Spacing.md, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (compact) ArticalsStrings.Demo.badge else ArticalsStrings.Demo.badgeLong,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** The one-line explanation under the DEMO strip, on sign-in and in settings. */
@Composable
fun DemoNotice(modifier: Modifier = Modifier) {
    Banner(
        text = ArticalsStrings.Demo.bannerBody,
        title = ArticalsStrings.Demo.bannerTitle,
        tone = BannerTone.Warning,
        modifier = modifier,
    )
}

private val FORCED_ERRORS: List<AppError?> = listOf(
    null,
    AppError.Offline(cause = null),
    AppError.PermissionDenied(detail = "demo_forced_permission"),
    AppError.Server(detail = "demo_forced_server"),
    AppError.Rejected(detail = "demo_forced_rejected", message = "This delivery is already finished and cannot be edited."),
    AppError.InvalidInput(
        detail = "demo_forced_validation",
        message = "Some of the details you entered need correcting.",
        fields = mapOf("pickup.address" to "Enter the pickup address"),
    ),
)

private fun forcedErrorLabel(error: AppError?): String = when (error) {
    null -> ArticalsStrings.Diagnostics.forceErrorNone
    is AppError.Offline -> ArticalsStrings.Diagnostics.forceOffline
    is AppError.PermissionDenied -> ArticalsStrings.Diagnostics.forcePermission
    is AppError.Server -> ArticalsStrings.Diagnostics.forceServer
    is AppError.Rejected -> ArticalsStrings.Diagnostics.forceRejected
    is AppError.InvalidInput -> ArticalsStrings.Diagnostics.forceValidation
    else -> ArticalsStrings.Errors.unexpectedTitle
}

internal val FONT_SCALES: List<Float> = listOf(0.85f, 1f, 1.25f, 1.5f)

internal fun fontScaleLabel(scale: Float): String = when {
    scale <= 0.9f -> ArticalsStrings.Diagnostics.fontScaleSmall
    scale <= 1.05f -> ArticalsStrings.Diagnostics.fontScaleNormal
    scale <= 1.3f -> ArticalsStrings.Diagnostics.fontScaleLarge
    else -> ArticalsStrings.Diagnostics.fontScaleHuge
}
/**
 * The pseudo-value for "let the data decide".
 *
 * A sentinel rather than null because the chip row is typed on `String`: the demo
 * panel is allowed to be this pragmatic, and it stays in one place.
 */
private const val ENTITLEMENT_DERIVED = "__derived__"
