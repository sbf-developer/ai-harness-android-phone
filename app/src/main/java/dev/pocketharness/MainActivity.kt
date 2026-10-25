package dev.pocketharness

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import dev.pocketharness.ui.HarnessApp

class MainActivity : ComponentActivity() {
    private var showAgent by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showAgent = intent.getBooleanExtra("show_agent", false)
        setContent {
            val vm: HarnessViewModel = viewModel()
            LaunchedEffect(showAgent) { if (showAgent) { vm.page(Page.Agent); showAgent = false } }
            HarnessApp(vm)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); showAgent = intent.getBooleanExtra("show_agent", false) }
}
