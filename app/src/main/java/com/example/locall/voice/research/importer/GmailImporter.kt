package com.example.locall.voice.research.importer

import android.content.Context
import android.util.Log
import com.example.locall.voice.email.EmailSummarizer
import com.example.locall.voice.email.GmailApi
import com.example.locall.voice.email.GmailAuth
import com.example.locall.voice.email.cache.EmailRepository
import com.example.locall.voice.research.data.SourceType

private const val TAG = "GmailImporter"

/**
 * Pulls recent / matching Gmail messages into the knowledge base, reusing the existing
 * [GmailApi] + [EmailRepository] plumbing (so bodies are cached and offline-readable). Each message
 * becomes one [RawDocument]; threading is left to retrieval-time reasoning.
 */
class GmailImporter(context: Context) {

    private val app = context.applicationContext
    private val repo = EmailRepository(app)

    sealed interface Result {
        data class Ok(val documents: List<RawDocument>) : Result
        data object NeedsConsent : Result
        data class Error(val message: String) : Result
    }

    /**
     * Import up to [max] messages matching [query] (a Gmail search, default recent inbox).
     * Reports progress as "fetched i/n" so the UI can show the network phase before analysis starts.
     */
    suspend fun import(
        query: String = "in:inbox",
        max: Int = 50,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val token = when (val r = GmailAuth.acquire(app)) {
            is GmailAuth.Result.Token -> r.accessToken
            is GmailAuth.Result.NeedsConsent -> return Result.NeedsConsent
            is GmailAuth.Result.Error -> return Result.Error(r.message)
        }
        return try {
            val ids = GmailApi.list(token, query, max = max.coerceIn(1, 100))
            if (ids.isEmpty()) return Result.Ok(emptyList())
            val docs = ArrayList<RawDocument>(ids.size)
            var skipped = 0
            ids.forEachIndexed { i, id ->
                try {
                    val m = repo.metadata(token, id)
                    val body = repo.body(token, id)
                    val who = EmailSummarizer.cleanFrom(m.from)
                    val subject = m.subject.ifBlank { "(no subject)" }
                    docs += RawDocument(
                        id = "gmail:$id",
                        title = subject,
                        sourceType = SourceType.GMAIL,
                        sourceRef = id,
                        author = who,
                        createdAtMs = m.internalDateMs,
                        text = "From $who\nSubject: $subject\n\n${body.ifBlank { m.snippet }}",
                    )
                } catch (e: GmailApi.Unauthorized) {
                    throw e // auth failures are fatal — propagate so we can prompt a reconnect
                } catch (t: Throwable) {
                    skipped++
                    Log.w(TAG, "skipping message $id: ${t.javaClass.simpleName}: ${t.message}", t)
                }
                onProgress(i + 1, ids.size)
            }
            if (docs.isEmpty() && skipped > 0) {
                return Result.Error("All $skipped message(s) failed to fetch — check the network and try again")
            }
            if (skipped > 0) Log.w(TAG, "imported ${docs.size}, skipped $skipped message(s)")
            Result.Ok(docs)
        } catch (e: GmailApi.Unauthorized) {
            GmailAuth.invalidate()
            Result.Error("Gmail access expired — reconnect and try again")
        } catch (t: Throwable) {
            Log.w(TAG, "import failed", t)
            Result.Error("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
        }
    }
}
