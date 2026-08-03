package com.yoshi.zaiquota

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * クォータデータ（reset/pct）をパースして Watch へ転送する共通経路。
 * QuotaNotificationListener（ntfy 通知経由）と QuotaPollWorker（ntfy poll 経由）の両方から利用する。
 *
 * silent フラグは通知シェードを汚さないよう Toast を抑制する目的のみ。
 */
object QuotaRelay {

    private const val TAG = "QuotaRelay"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * メッセージから "key=value" 形式のフィールドを抽出する共通パーサ。
     * ntfy アプリが複数メッセージを1通知へ group 化した場合、bigText に
     * "pct=1 ... pct=5 ... pct=18" のように連結される。最新値（末尾マッチ）を
     * 返さないと最も古い値が採用されてしまうため findAll の最後を採用する。
     */
    fun parseField(message: String, field: String): String =
        Regex("$field=([^\\s]+)").findAll(message).lastOrNull()?.groupValues?.getOrNull(1) ?: ""

    /**
     * reset/pct をパース → QuotaStore へ保存 → DataLayer で Watch へ push。
     * UI スレッドから呼ばれることを想定（Toast 表示のため）。
     *
     * @param message パース対象の生メッセージ
     * @param context Application/Service Context
     * @param sourceTag デバッグログの受信元タグ
     *   （"③ntfy受信" = リスナー、"③poll受信" = フォールバック poll）
     */
    fun parseAndRelay(context: Context, message: String, sourceTag: String) {
        val reset = parseField(message, KEY_RESET)
        // "none" 等のプレースホルダは空扱い（Mac 側で RESET_TIME が取れなかったケース）
        val normalizedReset = if (reset.isEmpty() || reset == "none") "" else reset
        val pct = parseField(message, KEY_PCT).toIntOrNull() ?: -1
        val silent = parseField(message, KEY_SILENT) == "1"

        Log.d(TAG, "Parsed [$sourceTag]: reset='$normalizedReset' pct=$pct silent=$silent")
        DebugLog.append(context, sourceTag, "pct=${pct}% reset=${normalizedReset}${if (silent) " [silent]" else ""}")

        // 受け手（Watch）側でガードすると「送っているのに届かない」gap ができるため、
        // 有効性判断は Mac 側 omp_usage スクリプトに一任し Phone/Watch は素通しする。
        relayToWatch(context, normalizedReset, pct, silent)
    }

    /**
     * QuotaStore へ保存し DataLayer へ urgent push する。
     * 有効性ガードは持たない: 受け取った値をそのまま送る（Mac 側が意味のある値のみ送る設計なので信頼）。
     */
    fun relayToWatch(context: Context, reset: String, pct: Int, silent: Boolean) {
        // 呼び出し元で正規化済みを前提とするが、直接呼び出しの保険としてここでも1回だけ保証。
        val r = if (reset.isEmpty()) "" else reset
        scope.launch {
            try {
                QuotaStore.save(context, r, pct)
                // reset は空でも常に送る。省略すると Watch が前回値を保持してしまうため、
                // 「reset 無し」状態を明示伝達するためにも必ず put する。
                val request = PutDataMapRequest.create(DATA_PATH).apply {
                    dataMap.putString(KEY_RESET, r)
                    dataMap.putInt(KEY_PCT, pct)
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(context).putDataItem(request).await()
                Log.d(TAG, "Pushed to Watch: reset=$r pct=$pct silent=$silent")
                DebugLog.append(context, TAG_WATCH_PUSHED, "✅ pct=${pct}%${if (silent) " [silent]" else ""}")
                // silent はウォッチ要請由来（ユーザー操作のない自動更新）なので Toast 抑制
                if (!silent) {
                    debugToast(context, "✅ ntfy→Watch送信\nreset=$r pct=${pct}%")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push to Watch", e)
                DebugLog.append(context, TAG_WATCH_PUSHED, "❌ ${e.javaClass.simpleName}: ${e.message}")
                if (!silent) {
                    debugToast(context, "❌ Watch送信エラー\n${e.message}")
                }
            }
        }
    }

    /** Toast はメインスレッドで出す必要があるため Handler 経由で post。 */
    private fun debugToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    const val DATA_PATH = "/zai_quota"
    const val KEY_RESET = "reset"
    const val KEY_PCT = "pct"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SILENT = "silent"

    const val TAG_NTFY_RECEIVED = "③ntfy受信"
    const val TAG_POLL_RECEIVED = "③poll受信"
    const val TAG_WATCH_PUSHED = "④Watch送信"
}
