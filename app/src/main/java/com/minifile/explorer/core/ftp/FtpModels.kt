package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.VfsPath

sealed interface FtpAuth {
    data object Anonymous : FtpAuth
    data class Credentials(
        val username: String,
        val password: String,
    ) : FtpAuth
}

data class FtpConfig(
    val port: Int = 2121,
    val bindAddress: String = "0.0.0.0",
    val root: VfsPath,
    val readOnly: Boolean = false,
    val auth: FtpAuth = FtpAuth.Anonymous,
    val passivePorts: IntRange = 50000..50100,
    val idleTimeoutMs: Long = 300_000L,
    val maxClients: Int = 8,
    val serverName: String = "MiniFile FTP",
    val allowForeignDataPeer: Boolean = false,
)

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

sealed interface FtpEvent {
    data class Started(val port: Int) : FtpEvent
    data object Stopped : FtpEvent
    data class ClientConnected(val clientIp: String) : FtpEvent
    data class ClientDisconnected(val clientIp: String) : FtpEvent
    data class Command(val clientIp: String, val verb: String, val arg: String) : FtpEvent
    data class Transfer(
        val clientIp: String,
        val direction: TransferDirection,
        val path: String,
        val bytes: Long,
        val durationMs: Long,
    ) : FtpEvent

    data class Error(val message: String) : FtpEvent
}
