package ru.landilf.desklink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.landilf.desklink.di.AppContainer
import ru.landilf.desklink.presentation.RemoteTerminal
import ru.landilf.desklink.presentation.TerminalViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            val vm: TerminalViewModel = viewModel(factory = container.viewModelFactory)
            RemoteTerminal(vm)
        } }
    }
}

