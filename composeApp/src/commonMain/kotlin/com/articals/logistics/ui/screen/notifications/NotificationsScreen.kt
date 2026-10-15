package com.articals.logistics.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.currentTimeMillis
import com.articals.logistics.core.parseIsoInstantOrNull
import com.articals.logistics.core.relativeTime
import com.articals.logistics.ui.component.EmptyState
import com.articals.logistics.ui.component.MetaChip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * The notices that arrived while the app was open.
 *
 * In a demo build every entry is labelled simulated, because no push was sent and no
 * inbox was read. In a real deployment this list is filled by FCM and the label is
 * absent — the one thing this screen must never do is imply a message was delivered
 * when nothing was.
 */
@Composable
fun NotificationsScreen(
    timeZoneId: String,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        DemoStrip()

        if (items.isEmpty()) {
            EmptyState(
                title = ArticalsStrings.Settings.notificationsEmpty,
                message = ArticalsStrings.Shell.notificationsBody,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(items = items, key = { it.id }) { notification ->
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = notification.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = notification.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (notification.simulated) {
                            MetaChip(text = ArticalsStrings.Demo.badge)
                        }
                    }

                    notification.at?.let { at ->
                        Spacer(Modifier.height(Spacing.xs))
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

                    if (notification.simulated) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = ArticalsStrings.Demo.simulatedNotification,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(Spacing.xs))
                    TextButton(onClick = { viewModel.dismiss(notification.id) }) {
                        Text(ArticalsStrings.Common.close)
                    }
                }
            }
        }
    }
}
