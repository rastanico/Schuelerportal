package de.example.chatmonitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val CHAT_URL = "https://api.schueler.schule-infoportal.de/luigywas/api/chat"
private const val TAG = "ChatMonitor"
private const val CHANNEL_ID = "chat_messages"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

object Notifier {

    fun createChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
        )
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(id, notification)
    }
}

class ChatCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val prefs = ctx.getSharedPreferences("chat_state", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        val cookie = CookieStore.header(applicationContext)
        if (cookie.isEmpty()) return Result.success() // never logged in

        try {
            val conn = URL(CHAT_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Referer", "https://schueler.schule-infoportal.de/")
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            Log.i(TAG, "GET /api/chat -> HTTP $code")

            if (code == 401 || code == 419) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                } catch (e: Exception) { null }
                Log.w(TAG, "Rejected by server: $err")
                if (!prefs.getBoolean("expired_notified", false)) {
                    Notifier.show(
                        applicationContext, 1,
                        applicationContext.getString(R.string.session_expired_title),
                        applicationContext.getString(R.string.session_expired_text),
                    )
                    prefs.edit().putBoolean("expired_notified", true).apply()
                }
                return Result.success()
            }

            if (code !in 200..299) return Result.retry()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            prefs.edit().putBoolean("expired_notified", false).apply()
            process(JSONArray(body))
            return Result.success()

        } catch (e: IOException) {
            return Result.retry()
        } catch (e: org.json.JSONException) {
            return Result.failure()
        }
    }

    // Same logic as monitor.py
    private fun process(chats: JSONArray) {
        for (i in 0 until chats.length()) {
            val chat = chats.optJSONObject(i) ?: continue
            val chatId = chat.opt("id")?.toString() ?: continue
            val name = chat.optString("name", "Chat $chatId")
            val unread = chat.optInt("unreadMessagesCount", 0)
            val latest = chat.optJSONObject("latestMessage") ?: continue
            if (!latest.has("timestamp")) continue

            val timestamp = latest.optLong("timestamp")
            val key = "ts_$chatId"
            val previous = if (prefs.contains(key)) prefs.getLong(key, 0) else null

            if (unread <= 0) {
                if (previous == null) prefs.edit().putLong(key, timestamp).apply()
                continue
            }

            if (previous == null || timestamp > previous) {
                var text = latest.optString("text", "").trim().ifEmpty { applicationContext.getString(R.string.no_text) }
                val file = latest.optString("file", "")
                if (file.isNotEmpty() && file != "null") text += "\n📎 $file"

                Notifier.show(applicationContext, chatId.hashCode(), name, text)
                prefs.edit().putLong(key, timestamp).apply()
            }
        }
    }
}
