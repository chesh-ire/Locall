package com.example.locall.voice.music

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SpotifyController"

class SpotifyController(private val appContext: Context) {

    private val audio by lazy { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isSpotifyInstalled(): Boolean = try {
        appContext.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG) != null
    } catch (t: Throwable) {
        false
    }

    fun playUri(uri: String): Boolean {
        if (!openSpotify(Uri.parse(uri))) return false
        scope.launch {
            for (waitMs in longArrayOf(1500, 1500, 2000)) {
                delay(waitMs)
                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
        return true
    }

    fun search(query: String): Boolean =
        openSpotify(Uri.parse("spotify:search:" + Uri.encode(query.trim())))

    private fun openSpotify(data: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, data).apply {
            setPackage(SPOTIFY_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            Log.i(TAG, "opened $data")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not open $data: ${t.message}")
            false
        }
    }

    fun pause() = sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun resume() = sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun skip() = sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun replay() = sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun sendKey(keyCode: Int): Boolean = try {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        Log.i(TAG, "dispatched media key $keyCode")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "media key $keyCode failed: ${t.message}")
        false
    }

    private companion object {
        const val SPOTIFY_PKG = "com.spotify.music"
    }
}
