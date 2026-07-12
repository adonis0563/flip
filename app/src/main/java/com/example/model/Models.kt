package com.example.model

enum class TransferStatus {
    QUEUED,
    SENDING,
    PAUSED,
    COMPLETE,
    FAILED,
    CANCELLED
}

data class Device(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val deviceType: String = "Android",
    val lastSeen: Long = System.currentTimeMillis()
)

data class TransferItem(
    val id: String, // X-Transfer-Id
    val fileName: String,
    val fileUri: String? = null,
    val filePath: String? = null,
    val fileSize: Long,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val isIncoming: Boolean = false,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (fileSize > 0) bytesTransferred.toFloat() / fileSize else 0f
}
