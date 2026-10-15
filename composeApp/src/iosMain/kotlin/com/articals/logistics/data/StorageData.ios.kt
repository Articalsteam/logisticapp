package com.articals.logistics.data

import dev.gitlive.firebase.storage.Data
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Converts a Kotlin byte array into the `NSData` that the iOS storage SDK needs.
 *
 * The bytes are pinned for the duration of the copy, which is the standard way to
 * hand Kotlin memory to an Objective-C API without leaving a dangling pointer.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun ByteArray.toStorageData(): Data {
    if (isEmpty()) return Data(NSData())
    val nsData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return Data(nsData)
}
