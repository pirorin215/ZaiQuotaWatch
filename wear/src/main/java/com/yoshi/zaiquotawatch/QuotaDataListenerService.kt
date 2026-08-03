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
        Log.d(TAG, "onDataChanged called with ${dataEvents.count} events")
        dataEvents.forEach { event ->
            Log.d(TAG, "Event type: ${event.type}, path: ${event.dataItem.uri.path}")
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == DATA_PATH) {
                val dm = DataMapItem.fromDataItem(event.dataItem).dataMap
                val reset = dm.getString(KEY_RESET, "")
                val pct = dm.getInt(KEY_PCT, -1)
                Log.d(TAG, "Received reset=$reset pct=$pct")
                // 受け手側での有効性ガードは持たない（送り手に一任）。
                // ここで skip すると「Phone は送っているのに Watch だけ古い値」の gap を生む。
                QuotaStore.save(this, reset, pct)
                ComplicationDataSourceUpdateRequester.create(
                    this, ComponentName(this, ZaiQuotaComplicationService::class.java)
                ).requestUpdateAll()
                Log.d(TAG, "Saved & requested Complication update")
            }
        }
    }

    companion object {
        private const val TAG = "QuotaDataListener"
        // app モジュールの QuotaRelay と同じ値（手動同期）
        private const val DATA_PATH = "/zai_quota"
        private const val KEY_RESET = "reset"
        private const val KEY_PCT = "pct"
    }
}
