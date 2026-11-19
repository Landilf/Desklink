package ru.landilf.desklink.presentation

import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.presentation.terminal.TerminalState

data class UiState(
    val profiles: List<SshProfile> = emptyList(),
    val active: SshProfile? = null,
    val connected: Boolean = false,
    val status: String = "",
    val terminal: TerminalState? = null,
    val hostPrompt: String? = null
)

