package com.example.locall.voice.music

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

object MediaPlaybackGate {

    private const val TAG = "MediaPlaybackGate"

    fun pauseForCommand(context: Context): Boolean {
        val am = audio(context)
        if (!am.isMusicActive) return false
        val ok = dispatch(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
        Log.i(TAG, "paused media for command (ok=$ok)")
        return ok
    }

    fun resume(context: Context) {
        val ok = dispatch(audio(context), KeyEvent.KEYCODE_MEDIA_PLAY)
        Log.i(TAG, "resumed media after command (ok=$ok)")
    }

    private fun audio(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun dispatch(am: AudioManager, keyCode: Int): Boolean = try {
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "media key $keyCode failed: ${t.message}")
        false
    }
}
