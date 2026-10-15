package com.articals.logistics.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.articals.logistics.core.AppError
import com.articals.logistics.core.UiState
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing

/**
 * The states every screen has to be able to show, in one place.
 *
 * A screen that renders [StateHost] cannot forget its empty or offline state: it
 * either passes a `UiState` or it does not compile. That is deliberate — the brief
 * requires every screen to handle loading, empty, error, offline and success, and the
 * cheapest way to guarantee it is to make the four ways of not being in the success
 * state somebody else's job.
 */

/** Renders [state], falling back to [content] only when there is data. */
@Composable
fun <T> StateHost(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { LoadingState(modifier) },
    emptyTitle: String? = null,
    emptyMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (state) {
            is UiState.Loading -> loading()
            is UiState.Content -> content(state.data)
            is UiState.Empty -> EmptyState(
                title = emptyTitle ?: state.title,
                message = emptyMessage ?: state.message,
                actionLabel = state.actionLabel,
                onAction = onRetry,
            )

            is UiState.Error -> ErrorState(error = state.error, onRetry = onRetry)
        }
    }
}

/** First load: a spinner with words, never a bare one and never a blank screen. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String = ArticalsStrings.Common.loading) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Grey placeholder blocks, shaped like the content that is coming. */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    height: Dp = 68.dp,
) {
    Column(modifier = modifier.fillMaxWidth().padding(Spacing.screen)) {
        repeat(rows) { index ->
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
            )
            if (index < rows - 1) Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ),
    )
}

/** A thin line that says "this is happening now" without taking the screen over. */
@Composable
fun InlineProgress(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        if (label != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Loaded successfully, and there is nothing here yet" — a normal state, not a fault. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A failure the user can act on.
 *
 * The message comes from [AppError], which the error mapper has already made safe to
 * display; the title is ours so that every offline problem looks the same wherever it
 * happens. The technical code is available, collapsed, for a support call.
 */
@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    val accent = when {
        error is AppError.Offline -> MaterialTheme.colorScheme.tertiary
        error.isEntitlementBlock -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (error is AppError.Offline) Icons.Default.Refresh else Icons.Default.Warning,
            contentDescription = null,
            tint = accent,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = ArticalsStrings.Errors.titleFor(error),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = error.userMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        error.fieldErrors.entries.take(4).forEach { (field, message) ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "$field: $message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (error.isRetryable && onRetry != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onRetry) { Text(ArticalsStrings.Common.retry) }
        }

        if (error.detail != null) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) ArticalsStrings.Errors.hideDetails else ArticalsStrings.Errors.showDetails,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = "${ArticalsStrings.Errors.errorCode}: ${error.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A message that belongs above the content: an explanation, a warning, or a refusal. */
enum class BannerTone { Info, Success, Warning, Danger }

@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val container = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.tertiaryContainer
        BannerTone.Success -> MaterialTheme.colorScheme.primaryContainer
        BannerTone.Warning -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.Danger -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onTertiaryContainer
        BannerTone.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        BannerTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.xs))
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            if (action != null) {
                Spacer(Modifier.height(Spacing.sm))
                action()
            }
        }
    }
}

/**
 * The DEMO marker.
 *
 * It appears on every screen in a demo build — including sign-in — because the one
 * thing that must never happen is somebody mistaking fictional deliveries for their
 * own. In a Firebase build it renders nothing at all.
 */
@Composable
fun DemoBadge(
    modifier: Modifier = Modifier,
    long: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = if (long) ArticalsStrings.Demo.badgeLong else ArticalsStrings.Demo.badge,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * A confirmation that states the consequence, not just "are you sure?".
 *
 * Destructive actions name what will happen and, where a reason is required, ask for
 * it — because the audit log is only useful if it says why.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    reasonLabel: String? = null,
    reason: String = "",
    onReasonChange: ((String) -> Unit)? = null,
    reasonRequired: Boolean = false,
    busy: Boolean = false,
) {
    val reasonTooShort = reasonRequired && reason.trim().length < 3

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (reasonLabel != null && onReasonChange != null) {
                    Spacer(Modifier.height(Spacing.md))
                    ArticalsTextField(
                        value = reason,
                        onValueChange = onReasonChange,
                        label = reasonLabel,
                        singleLine = false,
                        minLines = 2,
                        enabled = !busy,
                        isError = reasonTooShort && reason.isNotEmpty(),
                    )
                    if (reasonTooShort && reason.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = ArticalsStrings.Pricing.reasonRequired,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy && !reasonTooShort,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(ArticalsStrings.Common.cancel) }
        },
    )
}

/** A labelled value, used on every detail screen so they all read the same way. */
@Composable
fun LabelValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    tone: Color? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = value,
            style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.SemiBold else null,
            color = tone ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
