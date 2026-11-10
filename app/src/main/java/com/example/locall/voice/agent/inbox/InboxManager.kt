package com.example.locall.voice.agent.inbox

import android.content.Context
import android.util.Log
import com.example.locall.voice.LlmBridge
import com.example.locall.voice.agent.data.AgentDatabase
import com.example.locall.voice.agent.data.InboxItemEntity
import com.example.locall.voice.agent.data.InboxRepository
import com.example.locall.voice.agent.data.InboxSource
import com.example.locall.voice.agent.data.MessageClass
import com.example.locall.voice.agent.data.ReviewStatus
import com.example.locall.voice.email.EmailSummarizer
import com.example.locall.voice.email.GmailApi
import com.example.locall.voice.email.GmailAuth
import com.example.locall.voice.email.cache.EmailRepository

private const val TAG = "InboxManager"

class InboxManager(context: Context) {

    private val app = context.applicationContext
    private val repo = InboxRepository(AgentDatabase.get(app).dao())
    private val emailRepo = EmailRepository(app)

    data class BriefItem(
        val id: String,
        val source: String,
        val sender: String,
        val title: String,
        val classification: String,
        val summary: String,
    )

    data class Briefing(
        val items: List<BriefItem>,
        val gmailConnected: Boolean,
    ) {
        val needsReply get() = items.filter { it.classification == MessageClass.NEEDS_REPLY }
        val actions get() = items.filter { it.classification == MessageClass.ACTION }
        val info get() = items.filter { it.classification == MessageClass.INFO }
        val isEmpty get() = items.isEmpty()
    }

    private val maxClassifyPerRun = 8

    suspend fun buildBriefing(onStatus: (String) -> Unit = {}): Briefing {
        repo.prune()
        onStatus("Checking your inbox…")
        val gmailConnected = syncGmail(onStatus)

        val unprocessed = repo.unprocessed(limit = maxClassifyPerRun)
        if (unprocessed.isNotEmpty()) {
            onStatus("Loading AI model…")
            LlmBridge.ensureLoaded(app, onStatus)
            unprocessed.forEachIndexed { i, item ->
                onStatus("Reviewing message ${i + 1} of ${unprocessed.size}…")
                val v = MessageClassifier.classify(app, item)
                repo.setClassification(item.id, v.classification, v.summary)
                repo.setStatus(item.id, ReviewStatus.BRIEFED)
            }
        }

        val recent = repo.recent(limit = 30)
            .filter { it.classification != MessageClass.NONE && !it.handled }
            .filter { it.classification.isNotBlank() }
        val items = recent.map {
            BriefItem(it.id, it.source, it.sender, it.title, it.classification, it.summary.ifBlank { it.preview.take(80) })
        }
        return Briefing(items, gmailConnected)
    }

    private suspend fun syncGmail(onStatus: (String) -> Unit): Boolean {
        val token = when (val r = GmailAuth.acquire(app)) {
            is GmailAuth.Result.Token -> r.accessToken
            is GmailAuth.Result.NeedsConsent -> return false
            is GmailAuth.Result.Error -> { Log.w(TAG, "gmail auth: ${r.message}"); return false }
        }
        return try {
            onStatus("Fetching new email…")
            val ids = GmailApi.list(token, "is:unread in:inbox", max = 15)
            ids.forEach { id ->
                val existing = repo.get("gmail:$id")
                if (existing != null) return@forEach
                val m = emailRepo.metadata(token, id)
                repo.upsert(
                    InboxItemEntity(
                        id = "gmail:$id",
                        source = InboxSource.GMAIL,
                        sender = EmailSummarizer.cleanFrom(m.from),
                        title = m.subject.ifBlank { "(no subject)" },
                        preview = m.snippet,
                        threadRef = id,
                        classification = MessageClass.UNCLASSIFIED,
                        summary = "",
                        reviewStatus = ReviewStatus.NEW,
                        handled = false,
                        receivedAtMs = m.internalDateMs,
                        capturedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            true
        } catch (e: GmailApi.Unauthorized) {
            GmailAuth.invalidate(); false
        } catch (t: Throwable) {
            Log.w(TAG, "gmail sync failed", t); false
        }
    }

    suspend fun item(id: String): InboxItemEntity? = repo.get(id)

    suspend fun markReviewed(id: String) = repo.setStatus(id, ReviewStatus.REVIEWED)

    suspend fun markHandled(id: String) = repo.markHandled(id)

    suspend fun recent(limit: Int = 30): List<InboxItemEntity> = repo.recent(limit)

    suspend fun newCount(): Int = repo.newCount()

    companion object {
        @Volatile private var instance: InboxManager? = null
        fun get(context: Context): InboxManager =
            instance ?: synchronized(this) {
                instance ?: InboxManager(context.applicationContext).also { instance = it }
            }
    }
}
