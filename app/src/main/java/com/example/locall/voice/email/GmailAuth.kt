package com.example.locall.voice.email

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "GmailAuth"

object GmailAuth {

    private const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.modify"

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result
        data class Error(val message: String) : Result
    }

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUntilElapsedMs: Long = 0L

    val isConnected: Boolean get() = cachedToken != null && SystemClock.elapsedRealtime() < cachedUntilElapsedMs

    private fun cache(token: String) {
        cachedToken = token
        cachedUntilElapsedMs = SystemClock.elapsedRealtime() + 50 * 60 * 1000L
    }

    fun invalidate() {
        cachedToken = null
        cachedUntilElapsedMs = 0L
    }

    suspend fun acquire(context: Context): Result {
        cachedToken?.let { if (SystemClock.elapsedRealtime() < cachedUntilElapsedMs) return Result.Token(it) }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_SCOPE)))
            .build()

        return try {
            val authResult = authorize(context, request)
            val token = authResult.accessToken
            when {
                authResult.hasResolution() -> {
                    val pi = authResult.pendingIntent
                    if (pi == null) Result.Error("consent required but no pending intent")
                    else Result.NeedsConsent(pi)
                }
                token != null -> { cache(token); Result.Token(token) }
                else -> Result.Error("no access token and no resolution")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquire failed", t)
            Result.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun resultFromConsent(context: Context, data: Intent?): Result = try {
        val authResult: AuthorizationResult =
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        val token = authResult.accessToken
        if (token != null) Result.Token(token).also { cache(token) }
        else Result.Error("consent completed but no access token")
    } catch (t: Throwable) {
        Log.w(TAG, "resultFromConsent failed", t)
        Result.Error(t.message ?: t.javaClass.simpleName)
    }

    private suspend fun authorize(
        context: Context,
        request: AuthorizationRequest,
    ): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
