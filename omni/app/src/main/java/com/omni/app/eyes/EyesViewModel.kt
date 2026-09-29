package com.omni.app.eyes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.vision.VisionHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of the capture pipeline, polled off [VisionHub] on a ~500ms loop.
 * Never renders pixel data — FPS / dims / status only.
 */
data class EyesStatus(
    val flowing: Boolean,
    val fps: Double,
    val width: Int,
    val height: Int,
    val ringSize: Int,
    val ringCapacity: Int,
) {
    companion object {
        fun empty() = EyesStatus(false, 0.0, 0, 0, 0, VisionHub.ring.capacity)
    }
}

class EyesViewModel : ViewModel() {
    var status by mutableStateOf(EyesStatus.empty())
        private set

    init {
        viewModelScope.launch {
            while (isActive) {
                poll()
                delay(500)
            }
        }
    }

    private fun poll() {
        val latest = VisionHub.ring.latest()
        val nowNs = System.nanoTime()
        // Honest "running" check: service flag on, a frame exists, and it is fresh.
        // Stale frames can linger in the ring after the service dies.
        val fresh = latest != null && nowNs - latest.timestampNs < 2_000_000_000L
        val flowing = VisionHub.capturing && fresh
        status = if (flowing && latest != null) {
            EyesStatus(
                flowing = true,
                fps = VisionHub.fps.fps(),
                width = latest.width,
                height = latest.height,
                ringSize = VisionHub.ring.size(),
                ringCapacity = VisionHub.ring.capacity,
            )
        } else {
            EyesStatus(
                flowing = false,
                fps = 0.0,
                width = 0,
                height = 0,
                ringSize = VisionHub.ring.size(),
                ringCapacity = VisionHub.ring.capacity,
            )
        }
    }
}
