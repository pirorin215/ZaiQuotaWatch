package com.yoshi.zaiquotawatch

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * phone からの DataLayer 受信。/zai_quota の DataItem 変更を検知 → キャッシュ → Complication 更新。
 * phone が ntfy から取得した reset 値を BT/WiFi 経由で受け取る。
 */
class QuotaDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("QuotaDataListener", "onDataChanged called with ${dataEvents.count} events")
        dataEvents.forEach { event ->
            Log.d("QuotaDataListener", "Event type: ${event.type}, path: ${event.dataItem.uri.path}")
            if (event.type == DataEvent.TYPE_CHANGED
                && event.dataItem.uri.path == DATA_PATH
            ) {
                val reset = DataMapItem.fromDataItem(event.dataItem).dataMap
                    .getString(KEY_RESET, "")
                val pct = DataMapItem.fromDataItem(event.dataItem).dataMap
                    .getInt(KEY_PCT, -1)
                Log.d("QuotaDataListener", "Received reset value: $reset, pct: $pct")
                // 受け手側での有効性ガードは持たない。
                // 送り手（Phone QuotaRelay）が送ってきたものは無条件で保存・反映する。
                // ここで独自基準（reset空/pct<0 等）で skip すると、Phone は送っているのに
                // Watch だけ古い値のまま据え置かれ、「スマホで取れているのに Watch に届かない」
                // gap を生む。有効性判断は送り手（Mac omp_usage / Phone QuotaRelay）に一任。
                QuotaStore.save(this, reset, pct)
                Log.d("QuotaDataListener", "Saved to QuotaStore: reset=$reset, pct=$pct")
                // Complication へ即時更新要求
                val component = ComponentName(this, ZaiQuotaComplicationService::class.java)
                ComplicationDataSourceUpdateRequester.create(this, component)
                    .requestUpdateAll()
                Log.d("QuotaDataListener", "Requested Complication update")
            }
        }
    }

    companion object {
        // app モジュールの QuotaFetchWorker と同じ値（手動同期）
        private const val DATA_PATH = "/zai_quota"
        private const val KEY_RESET = "reset"
        private const val KEY_PCT = "pct"
    }
}
