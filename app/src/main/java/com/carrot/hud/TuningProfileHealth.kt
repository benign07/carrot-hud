package com.carrot.hud
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only low-frequency status. Retain warnings on offline/unsupported rollback. */
object TuningProfileHealth {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private var next=0L
    private fun prefs(c:Context)=c.getSharedPreferences("tuning_health_v1",Context.MODE_PRIVATE)
    fun text(c:Context):String {val id=DeviceStore.getActive(c)?.id ?: return "";return prefs(c).getString(id,"") ?: ""}
    fun observe(c:Context,d:Device,s:JSONObject) {
        if(DeviceStore.getActive(c)?.id!=d.id)return
        val r=s.optJSONObject("receipt")
        val keys=r?.optJSONArray("attempted")
        val message=if(s.optBoolean("recovery_required")) "튜닝 일부 적용·복구 확인 필요 · P 정차 후 튜닝 메뉴\n"+(if(keys==null) "기록을 확인하세요" else (0 until keys.length()).joinToString(", ") {keys.optString(it)}) else ""
        prefs(c).edit().putString(d.id,message).commit()
        ArchiveWidgetStatus.publish(c)
    }
    fun refresh(c:Context,d:Device) {
        synchronized(this) {if(SystemClock.elapsedRealtime()<next||!busy.compareAndSet(false,true))return;next=SystemClock.elapsedRealtime()+60_000}
        executor.execute {try {val resolved=Net.resolve(c,d) ?: return@execute;if(!resolved.identityNeedsCheck)observe(c,resolved,TuningApi.forDevice(resolved).state())}catch(_:Exception){}finally {busy.set(false)}}
    }
}
