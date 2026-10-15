package com.articals.logistics.ui.screen.notifications

import androidx.lifecycle.ViewModel
import com.articals.logistics.data.api.InAppNotifications
import com.articals.logistics.data.dto.AppNotificationDto
import kotlinx.coroutines.flow.StateFlow

/**
 * The notices this app knows about.
 *
 * Deliberately thin: the list belongs to the notification source (the demo store in
 * demo mode, FCM in a real deployment), and the screen renders whatever that source
 * holds. Nothing here can invent a notification, which is what keeps "simulated" an
 * honest label rather than a decoration.
 */
class NotificationsViewModel(
    private val notifications: InAppNotifications,
) : ViewModel() {

    val items: StateFlow<List<AppNotificationDto>> = notifications.notifications

    fun dismiss(id: String) = notifications.dismiss(id)
}
