package ru.landilf.desklink.presentation

import androidx.lifecycle.ViewModel
import ru.landilf.desklink.data.ssh.SshSessionManager
import ru.landilf.desklink.domain.model.SshProfile

class TerminalViewModel(private val manager: SshSessionManager) : ViewModel() {
    val state = manager.state
    fun save(profile: SshProfile, password: String, key: String = "", passphrase: String = "") =
        manager.save(profile, password, key, passphrase)

    fun remove(profile: SshProfile) = manager.remove(profile)
    fun connect(profile: SshProfile) = manager.connect(profile)
    suspend fun startWayvnc(profile: SshProfile, sessionName: String) = manager.startWayvnc(profile, sessionName)
    fun stopWayvnc(profile: SshProfile, sessionName: String) { manager.stopWayvnc(profile, sessionName) }
    fun trust(allow: Boolean) = manager.trust(allow)
    fun write(text: String) = manager.write(text)
    fun resize(cols: Int, rows: Int) = manager.resize(cols, rows)
    fun disconnect() = manager.disconnect()
    override fun onCleared() {
        manager.close()
    }
}
