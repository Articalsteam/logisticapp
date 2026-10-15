package com.articals.logistics.data

import dev.gitlive.firebase.storage.Data

/**
 * Android hands Firebase Storage a byte array directly; there is no bridging type
 * to convert, which is why this actual is a one-liner.
 */
actual fun ByteArray.toStorageData(): Data = Data(this)
