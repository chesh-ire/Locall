package com.example.locall.voice.research.chunkperf

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import com.example.locall.voice.ThermalGovernor
import com.example.locall.voice.battery.DeviceStateCollector

/**
 * Instantaneous device power/thermal snapshot, captured just before and just after each
 * chunk inference so per-chunk CPU, battery, energy, temperature and thermal deltas can be
 * attributed to that chunk. Reuses [DeviceStateCollector] for battery %/temperature/thermal
 * and adds the process CPU clock + BatteryManager charge counter for an energy estimate.
 */
object DevicePowerSampler {

    data class PowerSample(
        val cpuMs: Long,
        val batteryPct: Int,
        val chargeUah: Long,
        val tempC: Float,
        val thermalStatus: Int,
        val headroom: Float,
    )

    fun sample(context: Context): PowerSample {
        val app = context.applicationContext
        val snap = DeviceStateCollector.collect(app)
        return PowerSample(
            cpuMs = Process.getElapsedCpuTime(), // process-wide CPU time across all cores
            batteryPct = snap.batteryPercent,
            chargeUah = chargeCounterUah(app),
            tempC = snap.temperatureC,
            thermalStatus = snap.thermalStatus,
            headroom = ThermalGovernor.headroom(app),
        )
    }

    /** Remaining battery charge in µAh, or 0 if the device doesn't expose the counter. */
    private fun chargeCounterUah(context: Context): Long {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0
        val v = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }
            .getOrDefault(0L)
        // Unsupported properties return Long.MIN_VALUE on some devices.
        return if (v <= 0L) 0L else v
    }
}
