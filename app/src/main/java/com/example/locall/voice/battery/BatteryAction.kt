package com.example.locall.voice.battery

sealed interface BatteryAction {

    val needsWriteSettings: Boolean

    data class SetBrightness(val percent: Int) : BatteryAction {
        override val needsWriteSettings = true
    }

    data object DisableAutoBrightness : BatteryAction {
        override val needsWriteSettings = true
    }

    data class SetScreenTimeout(val seconds: Int) : BatteryAction {
        override val needsWriteSettings = true
    }

    data object EnableBatterySaver : BatteryAction {
        override val needsWriteSettings = false
    }
}

data class BatteryRecommendation(val action: BatteryAction, val label: String)

data class ExecResult(val label: String, val applied: Boolean, val verified: Boolean, val detail: String)
