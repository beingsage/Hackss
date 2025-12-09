package com.runanywhere.startup_hackathon20

import android.app.Application

class EchoZeroApplication : Application() {

    lateinit var thermalMonitor: ThermalMonitor
        private set

    private val predictiveWake = PredictiveWakeScheduler()

    override fun onCreate() {
        super.onCreate()

        // Initialize thermal monitoring for quantization switching
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startMonitoring()

        // Check if we should pre-warm models based on usage patterns
        if (predictiveWake.shouldPrewarm()) {
            // Schedule model pre-warming
            // This happens before user opens the app
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        thermalMonitor.stopMonitoring()
        LatencyTracker.reset()
    }

    /**
     * Get recommended configuration based on thermal state
     */
    fun getRecommendedConfig(): ConfigMode {
        return when {
            thermalMonitor.shouldUseLowQualityModel() -> ConfigMode.LOW_LATENCY
            thermalMonitor.thermalState.value == ThermalMonitor.ThermalState.COLD -> ConfigMode.HIGH_QUALITY
            else -> ConfigMode.BALANCED
        }
    }

    enum class ConfigMode {
        LOW_LATENCY,
        BALANCED,
        HIGH_QUALITY
    }
}