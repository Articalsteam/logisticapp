package com.articals.logistics.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.articals.logistics.core.formatBpsAsPercent
import com.articals.logistics.core.formatMinor
import com.articals.logistics.ui.theme.Spacing

/**
 * The small parts every screen is built from.
 *
 * They exist so that a dispatcher's delivery list, an owner's pricing screen and a
 * rider's job card all behave the same way when tapped, and so that "make the button
 * bigger" is one edit rather than forty.
 */

// ---------------------------------------------------------------------------
// Text input
// ---------------------------------------------------------------------------

/**
 * A labelled text field.
 *
 * Riders work in sunlight, one-handed, sometimes with gloves; the field therefore
 * defaults to a comfortable height and, when [keyboardType] is numeric, to the digits
 * keypad rather than the full keyboard.
 */
@Composable
fun ArticalsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    isError: Boolean = error != null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = isError,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leading,
            trailingIcon = trailing,
            supportingText = (error ?: helper)?.let { text ->
                {
                    Text(
                        text = text,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        )
    }
}

/** An amount field: digits only, with the currency as a prefix so the unit is obvious. */
@Composable
fun ArticalsAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    currency: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    ArticalsTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        helper = helper,
        error = error,
        enabled = enabled,
        keyboardType = KeyboardType.Decimal,
        leading = {
            Text(
                text = com.articals.logistics.core.currencySymbol(currency),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** A password field with a show/hide control. */
@Composable
fun ArticalsPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        isError = error != null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        supportingText = error?.let { text -> { Text(text, color = MaterialTheme.colorScheme.error) } },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide the password" else "Show the password",
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Choosing one thing from a short list
// ---------------------------------------------------------------------------

/**
 * A dropdown that shows what is currently chosen in the field itself.
 *
 * Used wherever the options are fixed and short — a role, a status, a plan, a
 * category — because a picker that lists three options is slower than a tap.
 */
@Composable
fun <T> ArticalsDropdown(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    placeholder: String = "Choose…",
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Box {
            OutlinedTextField(
                value = selected?.let(optionLabel) ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                isError = error != null,
                supportingText = (error ?: helper)?.let { text ->
                    {
                        Text(
                            text = text,
                            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            // The field is disabled so it cannot be typed into, which means the taps have
            // to be caught here. Bigger than the field on purpose: the whole row is the
            // target, not just the arrow.
            if (enabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { expanded = true },
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

/** A row of single-choice chips: service levels, filters, roles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

/** A read-only chip for metadata: a category, an area, a count. */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/**
 * A titled block of content.
 *
 * Cards are used sparingly in this app: one per meaningful object (a delivery, a
 * rider, a rate card), never a card inside a card.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(Spacing.sm))
                        trailing()
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}

/** A section heading with an optional action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A money row: label on the left, exact amount on the right.
 *
 * Amounts are never abbreviated here — a delivery price is a figure somebody has to
 * agree with a customer about, so it is always shown in full with its currency.
 */
@Composable
fun MoneyRow(
    label: String,
    amountMinor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    tone: Color? = null,
    hint: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = formatMinor(amountMinor, currency),
            style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.SemiBold else null,
            color = tone ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * An estimated-versus-actual pair.
 *
 * Shown wherever money is involved on a finished delivery: the estimate is what was
 * quoted, the actual is what happened, and the difference is the number an owner
 * actually wants. Negative variances are coloured, never hidden.
 */
@Composable
fun EstimateVsActualRow(
    label: String,
    estimatedMinor: Long,
    actualMinor: Long?,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val variance = actualMinor?.let { it - estimatedMinor }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = if (actualMinor == null) {
                    formatMinor(estimatedMinor, currency)
                } else {
                    "${formatMinor(estimatedMinor, currency)} → ${formatMinor(actualMinor, currency)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (variance != null) {
            Text(
                text = (if (variance >= 0) "+" else "") + formatMinor(variance, currency),
                style = MaterialTheme.typography.bodySmall,
                color = if (variance >= 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/** A margin, as basis points rendered for a human. */
@Composable
fun MarginText(bps: Int?, modifier: Modifier = Modifier) {
    Text(
        text = if (bps == null) "—" else formatBpsAsPercent(bps),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

/** A determinate progress bar with a percentage, used for uploads. */
@Composable
fun ProgressRow(progress: Float, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "$label ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An outlined action used next to a filled one (secondary choice). */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = Spacing.touchTarget), enabled = enabled) {
        Text(label)
    }
}

/** An inline "we could not do that" message, for actions rather than whole screens. */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    Banner(text = message, modifier = modifier, tone = BannerTone.Danger)
}

/**
 * The sort chooser.
 *
 * A menu rather than a chip row: sorting is infrequent and there are four options, so
 * it earns one line of the header instead of a permanent row of the screen.
 */
@Composable
fun <T> SortMenu(
    current: T,
    label: (T) -> String,
    options: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text("$SORT_PREFIX ${label(current)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (option == current) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

/** "Sort: …"; the word before the choice, so the control explains itself. */
private const val SORT_PREFIX = "Sort:"
