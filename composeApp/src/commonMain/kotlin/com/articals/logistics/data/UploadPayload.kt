package com.articals.logistics.data

/** Bytes plus the metadata Storage needs to accept an upload. */
class UploadPayload(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is UploadPayload &&
                fileName == other.fileName &&
                contentType == other.contentType &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + contentType.hashCode()) + fileName.hashCode()
}
