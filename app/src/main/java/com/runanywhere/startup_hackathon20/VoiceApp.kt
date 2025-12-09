package com.runanywhere.startup_hackathon20


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic

@Composable
fun VoiceApp(
    uiState: VoiceUIState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Voice Assistant",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Show loading state if model is not loaded
        if (!uiState.isModelLoaded) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5C6BC0)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Loading models...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Listening", color = Color.White)
            }
        } else {
            // Microphone level indicator
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when (uiState.state) {
                            VoiceState.LISTENING -> Color(0xFF4CAF50)
                            VoiceState.PROCESSING -> Color(0xFFFFC107)
                            VoiceState.RESPONDING -> Color(0xFF2196F3)
                            VoiceState.ERROR -> Color(0xFFF44336)
                            else -> Color.LightGray
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val level = (uiState.audioLevel * 40).coerceIn(4f, 40f)
                Box(
                    modifier = Modifier
                        .size(level.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.7f))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (uiState.state) {
                    VoiceState.LISTENING -> "Listening..."
                    VoiceState.PROCESSING -> "Processing..."
                    VoiceState.RESPONDING -> "Speaking..."
                    VoiceState.ERROR -> "Error"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Partial STT
            if (uiState.partialText.isNotBlank()) {
                Text(
                    text = "Heard: ${uiState.partialText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            // Final STT
            if (uiState.finalText.isNotBlank()) {
                Text(
                    text = "You said: ${uiState.finalText}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            // LLM Response
            if (uiState.responseText.isNotBlank()) {
                Text(
                    text = "Assistant: ${uiState.responseText}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF1565C0)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            // Error
            if (!uiState.errorMessage.isNullOrBlank()) {
                Text(
                    text = "Error: ${uiState.errorMessage}",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { if (uiState.state == VoiceState.IDLE) onStartClick() else onStopClick() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.state == VoiceState.IDLE) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            ) {
                Text(if (uiState.state == VoiceState.IDLE) "Start Listening" else "Stop")
            }
        }
    }
}
