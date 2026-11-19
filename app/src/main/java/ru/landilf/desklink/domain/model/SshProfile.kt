package ru.landilf.desklink.domain.model

data class SshProfile(
    val id: String = "",
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val tmux: Boolean = false,
    val tmuxName: String = "codex",
    val vncHost: String = "",
    val vncPort: Int = 5900,
    val vncUsername: String = "",
    val vncFingerprint: String = ""
)

