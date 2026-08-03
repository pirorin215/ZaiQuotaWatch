package com.yoshi.zaiquota

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ntfy サーバーへ直接 poll し、NotificationListener が取りこぼした可能性のある
 * 直近メッセージを回収するフォールバック Worker（5分周期）。
 *
 * 背景:
 *   Mac 側 omp_usage.300s.sh は ntfy データトピック（claude-code-notice215）へ
 *   クォータ情報を POST する。通常は ntfy 公式アプリが通知を出し
 *   QuotaNotificationListener が即時検知するが、以下のケースで取りこぼす:
 *     - ntfy アプリが複数メッセージを1通知に group 化し onNotificationPosted が
 *       発火しない / 遅延する
 *     - NotificationListenerService がシステムによって休眠（Android 14+ の既知問題）
 *
 * 本 Worker は Mac 側スクリプトの poll と同じ since カーソル方式を採用し、
 * 重複処理を防ぐ。Mac 側の ~/.cache/omp_watchpoll_since と同等の役割を
 * SharedPreferences (KEY_POLL_SINCE) で Phone 側に持つ。
 *
 * シーケンス:
 *   Mac → ntfy.sh/claude-code-notice215 へ POST
 *   → ntfy 公式アプリ通知（QuotaNotificationListener 経路）  ← 即時だが取りこぼし有
 *   → （5分後）本 Worker が since=<前回id>&poll=1 で未読を回収  ← フォールバック
 *   → QuotaRelay.relayToWatch で Watch へ転送
 *
 * リスナーが生きていれば since 以降に新着は無く no-op。
 * リスナーが死んでいれば回収され、結果的に最大5分遅延で Watch へ届く。
 */
class QuotaPollWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var since = PollCursor.getSince(applicationContext)
        // 初回（since="all"）は過去全メッセージを読むと膨大になるため、
        // まず最新メッセージIDを取得してカーソルを初期化する。
        // これにより「次回の poll から最新以降の差分だけを見る」状態になる。
        // 初回はフォールバックとして機能しないが、リスナー経路が生きていれば
        // 即時受信できるので問題ない。リスナー死んでいる場合は次回周期から有効化。
        if (since == "all") {
            val latestId = fetchLatestMessageId()
            if (latestId != null) {
                PollCursor.setSince(applicationContext, latestId)
                Log.d(TAG, "Initialized poll cursor to latest: $latestId")
                DebugLog.append(applicationContext, TAG_POLL, "🔖 カーソル初期化 cursor=${latestId.takeLast(6)}")
                return Result.success()
            }
            // 取得失敗時は "all" のまま次回再試行
            return Result.success()
        }

        val fetched = try {
            pollMessages(since)
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.javaClass.simpleName}: ${e.message}")
            DebugLog.append(applicationContext, TAG_POLL, "❌ poll失敗 ${e.javaClass.simpleName}")
            // ネットワークエラー等はリトライ対象にするが WorkManager の通常バックオフに任せる
            return Result.success()
        }

        if (fetched.isEmpty()) {
            // 新着なし: リスナー経路で処理済み、または本当に新着なし。
            // skip ログは冗長になるため DEBUG のみ。
            Log.d(TAG, "No new messages since $since")
            return Result.success()
        }

        // 最新メッセージまでカーソルを進める（次回 poll はこの id 以降を読む）
        PollCursor.setSince(applicationContext, fetched.last().id)
        Log.d(TAG, "Polled ${fetched.size} messages, cursor → ${fetched.last().id}")

        // クォータ関連メッセージだけ抽出して処理。
        // 状態機械通知（枯渇/回復/使用開始）も silent ミラーもこのトピックを流れる。
        // "pct=" を含むものだけを対象にし、システムメッセージ等を除外。
        var processed = 0
        for (msg in fetched) {
            if (!msg.message.contains("pct=")) continue
            // 各メッセージを個別にパース（group 化の影響を受けない）
            QuotaRelay.parseAndRelay(applicationContext, msg.message, QuotaRelay.TAG_POLL_RECEIVED)
            processed++
        }

        DebugLog.append(
            applicationContext, TAG_POLL,
            "📩 ${fetched.size}件取得 / ${processed}件処理 cursor=${fetched.last().id.takeLast(6)}"
        )
        return Result.success()
    }

    /**
     * 現在の最新メッセージIDを取得（初回カーソル初期化用）。
     * poll=1 無し + since=all で既到着分を1件読んだら即切断し、その id を返す。
     */
    private fun fetchLatestMessageId(): String? {
        val url = URL("${DATA_TOPIC_URL}/json?poll=1&since=all")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "fetchLatest HTTP ${conn.responseCode}")
                return null
            }
            var latestId: String? = null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                // 全メッセージを読む必要は無い。最初の数件から最新idを取得。
                // since=all は古い順に返るため、読めるだけ読んで最新を採用。
                var line = reader.readLine()
                var count = 0
                while (line != null && count < MAX_FETCH) {
                    if (line.isNotBlank()) {
                        parseJsonLine(line)?.let { latestId = it.id }
                        count++
                    }
                    line = reader.readLine()
                }
            }
            latestId
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatest failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * ntfy データトピックから since 以降のクォータ関連メッセージを取得。
     *
     * ntfy /json は long-poll 既定だが、本トピックは ZCode 通知等も流れる活況で
     * poll=1 + readTimeout の Mac 方式だと常に timeout まで保持してしまう。
     * そのため poll=1 を付けず、MAX_FETCH 件読むかクォータメッセージを必要数集めたら
     * 即 disconnect する方式（Mac の --max-time 相当）をとる。
     */
    private fun pollMessages(since: String): List<NtfyMessage> {
        val url = URL("${DATA_TOPIC_URL}/json?since=${since}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            // long-poll 待機に入る前の読み出しは即時なので短めで十分。
            readTimeout = 8_000
        }
        val messages = mutableListOf<NtfyMessage>()
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "ntfy poll HTTP ${conn.responseCode}")
                return emptyList()
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                var quotaCount = 0
                while (line != null && messages.size < MAX_FETCH) {
                    if (line.isNotBlank()) {
                        parseJsonLine(line)?.let { msg ->
                            messages.add(msg)
                            // クォータ系（pct= 含む）だけ数える。ZCode の通知等は無視。
                            if (msg.message.contains("pct=")) quotaCount++
                        }
                    }
                    // クォータメッセージを必要数集めたら即打ち切り（long-poll 回避）
                    if (quotaCount >= MAX_QUOTA_MESSAGES) break
                    line = reader.readLine()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            // 既到着分が多く読み出し中に timeout したか、since が未来位置で long-poll 待機に入ったか。
            // 取得済み分をそのまま返す。
            Log.d(TAG, "poll timeout after ${messages.size} msgs")
        } finally {
            conn.disconnect()
        }
        return messages
    }

    /** ntfy の1行 JSON を NtfyMessage へ変換。event=message 以外は null。 */
    private fun parseJsonLine(line: String): NtfyMessage? = try {
        val obj = org.json.JSONObject(line)
        if (obj.optString("event") != "message") null
        else NtfyMessage(id = obj.getString("id"), message = obj.getString("message"))
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse json line: ${line.take(80)}")
        null
    }

    private data class NtfyMessage(val id: String, val message: String)

    /**
     * ntfy poll の since カーソルを SharedPreferences で永続化。
     * Mac 側の ~/.cache/omp_watchpoll_since と同等。
     */
    private object PollCursor {
        private const val PREFS = "zai_quota_poll"
        private const val KEY_SINCE = "since"

        fun getSince(context: android.content.Context): String =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_SINCE, "all") ?: "all"

        fun setSince(context: android.content.Context, id: String) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_SINCE, id).apply()
        }
    }

    companion object {
        private const val TAG = "QuotaPollWorker"
        private const val TAG_POLL = "③poll"
        // Mac omp_usage.300s.sh の NOTIFY_URL と同一トピック
        private const val DATA_TOPIC_URL = "https://ntfy.sh/claude-code-notice215"
        // 読み出し上限: クォータ以外（ZCode通知等）も流れるトピックなので
        // 無制限に読まないようキャップ。5分周期なら高々数件〜数十件。
        private const val MAX_FETCH = 50
        // クォータメッセージ（pct= 含む）をこの数集めたら即切断。
        // silent ミラーと状態機械通知が同時に来ても高々2-3件で十分。
        private const val MAX_QUOTA_MESSAGES = 3
    }
}
