package com.example.locall.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

private const val TAG = "VolumeController"

/**
 * Thin wrapper over [AudioManager] for one-step volume nudges. Raising/lowering the
 * media and ringer streams within their audible range needs no special permission;
 * the one case that can throw — lowering the ringer into vibrate/silent without
 * Do-Not-Disturb policy access — is caught and reported as a failure, since we
 * deliberately don't request ACCESS_NOTIFICATION_POLICY.
 */
object VolumeController {

    /**
     * Whether audio is actively playing right now. NOTE: the voice flow pauses media
     * before a command runs (see [com.example.locall.voice.music.MediaPlaybackGate]),
     * so by dispatch time this is false even if the user *was* listening — callers on
     * that path must use the "was playing" signal captured before the pause instead.
     */
    fun isMediaPlaying(context: Context): Boolean = audio(context).isMusicActive

    /** Nudge the media (STREAM_MUSIC) volume, popping the system volume slider. */
    fun adjustMedia(context: Context, up: Boolean): Boolean =
        adjust(context, AudioManager.STREAM_MUSIC, up)

    /** Nudge the ringer (STREAM_RING) volume. Returns false if the OS blocks it. */
    fun adjustRing(context: Context, up: Boolean): Boolean =
        adjust(context, AudioManager.STREAM_RING, up)

    private fun adjust(context: Context, stream: Int, up: Boolean): Boolean {
        val am = audio(context)
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
            Log.i(TAG, "adjustStreamVolume(stream=$stream, up=$up) ok")
            true
        } catch (t: Throwable) {
            // Lowering the ringer into vibrate/silent throws SecurityException without
            // ACCESS_NOTIFICATION_POLICY, which we don't hold.
            Log.w(TAG, "adjustStreamVolume(stream=$stream, up=$up) failed: ${t.message}")
            false
        }
    }

    private fun audio(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
