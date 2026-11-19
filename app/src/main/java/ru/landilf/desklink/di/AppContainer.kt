package ru.landilf.desklink.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ru.landilf.desklink.data.ssh.SshSessionManager
import ru.landilf.desklink.data.storage.ProfileStore
import ru.landilf.desklink.presentation.TerminalViewModel

class AppContainer(context: Context) {
    private val profiles = ProfileStore(context.applicationContext)
    val viewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TerminalViewModel::class.java))
            return TerminalViewModel(SshSessionManager(profiles, context.applicationContext)) as T
        }
    }
}
