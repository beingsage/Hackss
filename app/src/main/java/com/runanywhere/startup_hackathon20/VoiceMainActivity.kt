package com.runanywhere.startup_hackathon20

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Import your orchestrator and UI state
import com.runanywhere.startup_hackathon20.VoiceOrchestrator
import com.runanywhere.startup_hackathon20.ui.theme.Startup_hackathon20Theme
// You should define VoiceUIState and VoiceState as in EchoZero or adapt as needed

class VoiceMainActivity : ComponentActivity() {
    private lateinit var orchestrator: VoiceOrchestrator

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceAssistant()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orchestrator = VoiceOrchestrator(this)

        // CRITICAL: Force UI ready IMMEDIATELY before setContent to avoid race condition
        // This ensures Compose renders with isModelLoaded = true from the start
        orchestrator.forceUIReady()

        setContent {
            Startup_hackathon20Theme {
                val uiState by orchestrator.uiState.collectAsState()
                VoiceApp(
                    uiState = uiState,
                    onStartClick = { checkPermissionAndStart() },
                    onStopClick = { orchestrator.stop() }
                )
            }
        }

        // Now do full initialization in background
        lifecycleScope.launch {
            try {
                orchestrator.initialize()
                android.util.Log.d("VoiceMainActivity", "Orchestrator initialized")
            } catch (e: Exception) {
                android.util.Log.e("VoiceMainActivity", "Failed to initialize orchestrator", e)
            }
        }
    }

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startVoiceAssistant()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceAssistant() {
        orchestrator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.release()
    }
}
