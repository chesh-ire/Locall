package com.example.locall.voice.agent.inbox

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.locall.voice.agent.data.AgentDatabase
import com.example.locall.voice.agent.data.InboxItemEntity
import com.example.locall.voice.agent.data.InboxRepository
import com.example.locall.voice.agent.data.InboxSource
import com.example.locall.voice.agent.data.MessageClass
import com.example.locall.voice.agent.data.ReviewStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "NotifCapture"

class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { InboxRepository(AgentDatabase.get(applicationContext).dao()) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = SOURCES[sbn.packageName] ?: return
        if (sbn.packageName == applicationContext.packageName) return

        val n = sbn.notification ?: return
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (isNoise(title, text)) return

        val contact = title.ifBlank { "Unknown" }
        val item = InboxItemEntity(
            id = "$source:${stableHash(sbn.packageName + '|' + contact + '|' + text)}",
            source = source,
            sender = contact,
            title = contact,
            preview = text,
            threadRef = contact,
            classification = MessageClass.UNCLASSIFIED,
            summary = "",
            reviewStatus = ReviewStatus.NEW,
            handled = false,
            receivedAtMs = sbn.postTime,
            capturedAtMs = System.currentTimeMillis(),
        )
        scope.launch {
            try {
                if (repo.upsert(item)) Log.i(TAG, "captured ${item.source} from $contact")
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed", t)
            }
        }
    }

    private fun isNoise(title: String, text: String): Boolean {
        val t = text.lowercase()
        return t == "typing…" || t == "typing..." ||
            Regex("^\\d+ (new )?messages?$").matches(t) ||
            Regex("^\\d+ chats?$").matches(t) ||
            (title.isBlank() && t.startsWith("checking for new messages"))
    }

    private fun stableHash(s: String): String =
        Integer.toHexString(s.hashCode() and 0x7fffffff)

    companion object {
        private val SOURCES = mapOf(
            "com.whatsapp" to InboxSource.WHATSAPP,
            "com.whatsapp.w4b" to InboxSource.WHATSAPP,
            "com.google.android.apps.messaging" to InboxSource.SMS,
            "com.android.messaging" to InboxSource.SMS,
            "com.facebook.orca" to InboxSource.OTHER,
            "org.telegram.messenger" to InboxSource.OTHER,
        )
    }
}
