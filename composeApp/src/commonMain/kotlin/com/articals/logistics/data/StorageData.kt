package com.articals.logistics.data

import dev.gitlive.firebase.storage.Data

/**
 * Bridges plain bytes to the storage SDK's platform data type.
 *
 * The Firebase Storage wrapper takes an `NSData` on Apple platforms and a
 * `ByteArray` on Android, so the conversion — and nothing else — lives in platform
 * code. Cameras hand us bytes from either side of that divide, which is why this is
 * an expect rather than a constructor call at each upload site.
 */
expect fun ByteArray.toStorageData(): Data
