package com.articals.logistics.demo

/**
 * The bytes demo mode puts where a real photograph would go.
 *
 * A proof of delivery in demo mode is a valid, 76-byte PNG held in memory — not a
 * file, not a download, not a camera. It exists so the upload, preview and "marked
 * as proof" states can be exercised without a camera permission, and every screen
 * that shows it says *simulated*.
 *
 * The bytes are written out as hex rather than read from a resource so the same
 * code compiles on Android and iOS with no asset pipeline. They are a plain
 * light-grey 8×8 PNG, which is obviously not a photograph of anybody's parcel.
 */
object DemoAssets {

    /** Decoded once; immutable, so sharing the array between callers is safe. */
    val proofPhoto: ByteArray by lazy { decode(PROOF_PHOTO_PNG_HEX) }

    /** A second, visually different placeholder for billing evidence. */
    val billingEvidence: ByteArray by lazy { decode(BILLING_EVIDENCE_PNG_HEX) }

    private fun decode(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = clean[index * 2].digitToInt(16)
            val low = clean[index * 2 + 1].digitToInt(16)
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** 8×8 light grey PNG. */
    private const val PROOF_PHOTO_PNG_HEX: String =
        "89504e470d0a1a0a0000000d4948445200000008000000080806000000c40fbe8b000000134944415478da6378f4e2c3" +
            "7f7c986164280000d54eee41ea91b6c90000000049454e44ae426082"

    /** 12×12 pale blue PNG: a different picture, so no two demo uploads look alike. */
    private const val BILLING_EVIDENCE_PNG_HEX: String =
        "89504e470d0a1a0a0000000d494844520000000c0000000c080600000056755ce7000000164944415478da63387df5e1" +
            "7f5230c3a886510dd83100c4eaf81062d8c6120000000049454e44ae426082"
}
