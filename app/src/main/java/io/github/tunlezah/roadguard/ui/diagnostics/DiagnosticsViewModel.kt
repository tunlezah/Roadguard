package io.github.tunlezah.roadguard.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.diagnostics.DiagnosticsSnapshot
import io.github.tunlezah.roadguard.thermal.SimulatedThermalSource
import io.github.tunlezah.roadguard.thermal.ThermalScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Collects diagnostics, and drives the thermal test harness.
 *
 * The harness lives here rather than in a separate debug screen because the thing it exercises --
 * the mitigation ladder -- is only observable through diagnostics. It is available in debug builds
 * only, and everything it produces is tagged as simulated all the way through to the exported
 * report, so a simulated reading can never be read as a measurement.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)

    private val _snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)
    val snapshot: StateFlow<DiagnosticsSnapshot?> = _snapshot.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _simulating = MutableStateFlow(false)
    val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

    val harnessAvailable: Boolean = io.github.tunlezah.roadguard.BuildConfig.DEBUG

    init {
        refresh()
    }

    fun refresh() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { container.diagnosticsCollector.collect() }
                .onSuccess { _snapshot.value = it }
                .onFailure { _message.value = "Diagnostics could not be collected: ${it.message}" }
            _busy.value = false
        }
    }

    fun export(onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching { container.diagnosticsCollector.export() }
                .onSuccess(onReady)
                .onFailure { _message.value = "The report could not be written: ${it.message}" }
        }
    }

    /**
     * Feeds a simulated thermal scenario into the running thermal policy.
     *
     * This is how the mitigation ladder is exercised without a hot car: the recorder reacts exactly
     * as it would to a real reading, and Diagnostics shows every value it drove marked SIMULATED.
     */
    fun simulate(scenario: ThermalScenario) {
        if (!harnessAvailable) return
        val source = (container.thermalSource as? SimulatedThermalSource)
            ?: SimulatedThermalSource().also {
                container.useSimulatedThermalSource(it)
                _simulating.value = true
            }
        source.emitScenario(scenario, android.os.SystemClock.elapsedRealtime())
        _message.value = "Simulating ${scenario.label}. Values shown are not measurements."
        refresh()
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return DiagnosticsViewModel(application) as T
            }
        }
    }
}
