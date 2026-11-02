package com.example.locall.voice

object NativeThreads {
    init {
        System.loadLibrary("locall")
    }

    fun pinToEfficiencyCores(): Boolean = nativePinToEfficiencyCores()

    private external fun nativePinToEfficiencyCores(): Boolean
}
