package com.runanywhere.startup_hackathon20

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.runanywhere.startup_hackathon20.ui.theme.Startup_hackathon20Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var orchestrator: AdvancedVoiceOrchestrator
    private lateinit var thermalMonitor: ThermalMonitor

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceAssistant()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orchestrator = AdvancedVoiceOrchestrator(this)
        thermalMonitor = ThermalMonitor(this)
        enableEdgeToEdge()
        setContent {
            Startup_hackathon20Theme {
                MainScreen(
                    orchestrator = orchestrator,
                    thermalMonitor = thermalMonitor,
                    onStartClick = { checkPermissionAndStart() }
                )
            }
        }

        thermalMonitor.startMonitoring()
        lifecycleScope.launch {
            orchestrator.initialize()
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
        thermalMonitor.stopMonitoring()
        orchestrator.release()
    }
}

@Composable
fun MainScreen(
    orchestrator: AdvancedVoiceOrchestrator,
    thermalMonitor: ThermalMonitor,
    onStartClick: () -> Unit
) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            BottomAppBar {
                val items = listOf(Screen.Chat, Screen.Voice)
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = false,
                        onClick = { navController.navigate(screen.route) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController = navController, startDestination = Screen.Chat.route, modifier = Modifier.padding(padding)) {
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Voice.route) {
                VoiceScreen(
                    orchestrator = orchestrator,
                    thermalMonitor = thermalMonitor,
                    onStartClick = onStartClick
                )
            }
        }
    }
}

@Composable
fun VoiceScreen(
    orchestrator: AdvancedVoiceOrchestrator,
    thermalMonitor: ThermalMonitor,
    onStartClick: () -> Unit
) {
    val uiState by orchestrator.uiState.collectAsState()
    val latencyMetrics by LatencyTracker.metrics.collectAsState()
    val microProgress by LatencyIllusions.microProgress.collectAsState()
    val predictedAction by LatencyIllusions.predictedAction.collectAsState()
    val thermalState by thermalMonitor.thermalState.collectAsState()

    EchoZeroTheme {
        EchoZeroApp(
            uiState = uiState,
            latencyMetrics = latencyMetrics,
            microProgress = microProgress,
            predictedAction = predictedAction,
            isThrottling = thermalState >= ThermalMonitor.ThermalState.HOT,
            onStartClick = onStartClick,
            onStopClick = { orchestrator.stop() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentModelId by viewModel.currentModelId.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showModelSelector by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Chat") },
                actions = {
                    TextButton(onClick = { showModelSelector = !showModelSelector }) {
                        Text("Models")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    downloadProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                }
            }

            // Model selector (collapsible)
            if (showModelSelector) {
                ModelSelector(
                    models = availableModels,
                    currentModelId = currentModelId,
                    onDownload = { modelId -> viewModel.downloadModel(modelId) },
                    onLoad = { modelId -> viewModel.loadModel(modelId) },
                    onRefresh = { viewModel.refreshModels() }
                )
            }

            // Messages List
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
            }

            // Auto-scroll to bottom when new messages arrive
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            // Input Field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    enabled = !isLoading && currentModelId != null
                )

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    enabled = !isLoading && inputText.isNotBlank() && currentModelId != null
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (message.isUser) "You" else "AI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ModelSelector(
    models: List<com.runanywhere.sdk.models.ModelInfo>,
    currentModelId: String?,
    onDownload: (String) -> Unit,
    onLoad: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Available Models",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (models.isEmpty()) {
                Text(
                    text = "No models available. Initializing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { model ->
                        ModelItem(
                            model = model,
                            isLoaded = model.id == currentModelId,
                            onDownload = { onDownload(model.id) },
                            onLoad = { onLoad(model.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelItem(
    model: com.runanywhere.sdk.models.ModelInfo,
    isLoaded: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleSmall
            )

            if (isLoaded) {
                Text(
                    text = "✓ Currently Loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        enabled = !model.isDownloaded
                    ) {
                        Text(if (model.isDownloaded) "Downloaded" else "Download")
                    }

                    Button(
                        onClick = onLoad,
                        modifier = Modifier.weight(1f),
                        enabled = model.isDownloaded
                    ) {
                        Text("Load")
                    }
                }
            }
        }
    }
}

@Composable
fun EchoZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1),
            secondary = Color(0xFF818CF8),
            background = Color(0xFF0F0F23),
            surface = Color(0xFF1A1A2E),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun EchoZeroApp(
    uiState: VoiceUIState,
    latencyMetrics: LatencyMetrics,
    microProgress: MicroProgressState,
    predictedAction: String?,
    isThrottling: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0F23),
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Spacer(modifier = Modifier.height(48.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EchoZero",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (isThrottling) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = "Device is hot",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Text(
                text = "On-Device Voice AI",
                fontSize = 14.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 4.dp)
            )

            MicroProgressIndicator(microProgress = microProgress)

            Spacer(modifier = Modifier.weight(1f))

            // Voice Orb with predicted action
            VoiceOrb(
                state = uiState.state,
                audioLevel = uiState.audioLevel,
                isModelLoaded = uiState.isModelLoaded,
                microProgress = microProgress
            )

            Spacer(modifier = Modifier.height(16.dp))

            PredictedActionBanner(predictedAction = predictedAction)

            Spacer(modifier = Modifier.height(16.dp))

            // Status Text
            StatusText(uiState = uiState)

            Spacer(modifier = Modifier.height(24.dp))

            // Transcript Display
            TranscriptCard(uiState = uiState)

            Spacer(modifier = Modifier.height(16.dp))

            LatencyMetricsCard(metrics = latencyMetrics)

            Spacer(modifier = Modifier.weight(1f))

            // Control Button
            ControlButton(
                state = uiState.state,
                isModelLoaded = uiState.isModelLoaded,
                onStartClick = onStartClick,
                onStopClick = onStopClick
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun MicroProgressIndicator(microProgress: MicroProgressState) {
    val stages = listOf(
        ProcessingStage.LISTENING to "Listen",
        ProcessingStage.RECOGNIZING to "Recognize",
        ProcessingStage.THINKING to "Think",
        ProcessingStage.RESPONDING to "Respond"
    )

    val currentIndex = stages.indexOfFirst { it.first == microProgress.stage }

    AnimatedVisibility(
        visible = microProgress.stage != ProcessingStage.LISTENING || microProgress.progress > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            stages.forEachIndexed { index, (stage, label) ->
                val isActive = index == currentIndex
                val isComplete = index < currentIndex

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isComplete -> Color(0xFF22C55E)
                                    isActive -> Color(0xFF6366F1)
                                    else -> Color(0xFF374151)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        color = if (isActive || isComplete) Color.White else Color(0xFF6B7280)
                    )
                }

                if (index < stages.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(
                                if (isComplete) Color(0xFF22C55E) else Color(0xFF374151)
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun PredictedActionBanner(predictedAction: String?) {
    AnimatedVisibility(
        visible = predictedAction != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
    ) {
        predictedAction?.let { action ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E3A5F)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF60A5FA)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = action,
                        fontSize = 14.sp,
                        color = Color(0xFFBFDBFE)
                    )
                }
            }
        }
    }
}

@Composable
fun LatencyMetricsCard(metrics: LatencyMetrics) {
    AnimatedVisibility(
        visible = metrics.utteranceCount > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A2E).copy(alpha = 0.8f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Latency Metrics",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(
                        label = "E2E",
                        value = "${metrics.avgE2eLatencyMs}ms",
                        isGood = metrics.avgE2eLatencyMs < 500
                    )
                    MetricItem(
                        label = "STT",
                        value = "${metrics.avgSttChunkMs}ms",
                        isGood = metrics.avgSttChunkMs < 150
                    )
                    MetricItem(
                        label = "LLM",
                        value = "${metrics.avgLlmFirstTokenMs}ms",
                        isGood = metrics.avgLlmFirstTokenMs < 300
                    )
                    MetricItem(
                        label = "TPS",
                        value = "%.1f".format(metrics.avgLlmTokensPerSec),
                        isGood = metrics.avgLlmTokensPerSec > 10
                    )
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, isGood: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isGood) Color(0xFF22C55E) else Color(0xFFF59E0B)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF6B7280)
        )
    }
}

@Composable
fun VoiceOrb(
    state: VoiceState,
    audioLevel: Float,
    isModelLoaded: Boolean,
    microProgress: MicroProgressState
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val orbColor = when (microProgress.stage) {
        ProcessingStage.LISTENING -> Color(0xFF22C55E)
        ProcessingStage.RECOGNIZING -> Color(0xFF3B82F6)
        ProcessingStage.THINKING -> Color(0xFFF59E0B)
        ProcessingStage.RESPONDING -> Color(0xFF8B5CF6)
        ProcessingStage.COMPLETE -> Color(0xFF6366F1)
    }.let { baseColor ->
        when (state) {
            VoiceState.IDLE -> Color(0xFF6366F1)
            VoiceState.ERROR -> Color(0xFFEF4444)
            else -> baseColor
        }
    }

    val scale = when (state) {
        VoiceState.LISTENING -> 1f + (audioLevel * 0.3f)
        VoiceState.PROCESSING -> pulseScale
        else -> 1f
    }

    Box(
        modifier = Modifier
            .size(180.dp)
            .scale(scale),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = 0.2f))
        )

        // Middle ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = 0.4f))
        )

        // Inner orb
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            orbColor,
                            orbColor.copy(alpha = 0.8f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (state) {
                    VoiceState.IDLE -> Icons.Default.Mic
                    VoiceState.LISTENING -> Icons.Default.GraphicEq
                    VoiceState.PROCESSING -> Icons.Default.Psychology
                    VoiceState.RESPONDING -> Icons.Default.VolumeUp
                    VoiceState.ERROR -> Icons.Default.Error
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
fun StatusText(uiState: VoiceUIState) {
    val statusText = when (uiState.state) {
        VoiceState.IDLE -> if (uiState.isInitialized) "Tap to start" else "Loading models..."
        VoiceState.LISTENING -> "Listening..."
        VoiceState.PROCESSING -> "Thinking..."
        VoiceState.RESPONDING -> "Speaking..."
        VoiceState.ERROR -> uiState.errorMessage ?: "An error occurred"
    }

    Text(
        text = statusText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFFE5E7EB),
        textAlign = TextAlign.Center
    )
}

@Composable
fun TranscriptCard(uiState: VoiceUIState) {
    val hasContent = uiState.partialText.isNotBlank() ||
                     uiState.finalText.isNotBlank() ||
                     uiState.responseText.isNotBlank()

    AnimatedVisibility(
        visible = hasContent,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 200.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E3F)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // User speech
                if (uiState.finalText.isNotBlank() || uiState.partialText.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF6366F1)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.finalText.ifBlank { uiState.partialText },
                            fontSize = 14.sp,
                            color = Color(0xFFE5E7EB)
                        )
                    }
                }

                // AI response
                if (uiState.responseText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF22C55E)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.responseText,
                            fontSize = 14.sp,
                            color = Color(0xFFE5E7EB)
                        )
                    }
                }

                // Last action
                if (uiState.lastAction != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Action: ${uiState.lastAction}",
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
            }
        }
    }
}

@Composable
fun ControlButton(
    state: VoiceState,
    isModelLoaded: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val isActive = state != VoiceState.IDLE && state != VoiceState.ERROR

    Button(
        onClick = { if (isActive) onStopClick() else onStartClick() },
        enabled = true,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) Color(0xFFEF4444) else Color(0xFF6366F1),
            disabledContainerColor = Color(0xFF374151)
        )
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isActive) "Stop" else "Start Listening",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private val EaseInOutSine: Easing = Easing { fraction ->
    -(kotlin.math.cos(Math.PI * fraction).toFloat() - 1) / 2
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    Startup_hackathon20Theme {
        ChatScreen()
    }
}
