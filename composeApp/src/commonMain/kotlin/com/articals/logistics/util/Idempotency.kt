package com.articals.logistics.util

import com.articals.logistics.core.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Idempotency keys for the operations that create something.
 *
 * Creating a delivery twice is not a duplicate that can be cleaned up later: it is a
 * second rider sent to the same address, and a second charge. Every create therefore
 * carries a key, and the backend promises that the same key produces the same record
 * — including when the phone loses the network *after* sending and the app retries.
 *
 * The key is generated once per form submission (and kept until that submission
 * succeeds), not per call, which is what makes the retry safe.
 */
object Idempotency {

    private val counter = MutableStateFlow(0L)

    /**
     * A key unique to this device and this moment.
     *
     * Not a cryptographic value and not meant to be: it only has to be unique among
     * the keys this device sends. The backend treats it as an opaque string and scopes
     * it to the operation and the tenant, so no two companies can collide.
     */
    fun newKey(scope: String): String {
        val sequence = counter.updateAndGet { it + 1 }
        return "$scope-${currentTimeMillis().toString(36)}-${sequence.toString(36)}"
    }
}
