package com.carrot.hud
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Cached evidence only. A tap opens a chooser; no writes from a render or refresh. */
object TuningWidgetStatus {
    private fun prefs(c:Context,id:String)=c.getSharedPreferences("tuning_widget_v1_"+id,Context.MODE_PRIVATE)
    fun pending(c:Context,id:String)=c.getSharedPreferences("tuning_pending_v1_"+id,Context.MODE_PRIVATE)
    fun text(c:Context):String {
        val d=DeviceStore.getActive(c) ?: return "프로필 선택 ▾ · 기기 등록 필요"
        val p=prefs(c,d.id);val label=p.getString("label","").orEmpty()
        return if(label.isBlank())"프로필 선택 ▾ · 저장 상태 확인 전" else "프로필 선택 ▾ · "+label
    }
    fun set(c:Context,d:Device,name:String,message:String) {
        val at=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        prefs(c,d.id).edit().putString("label","$name · $message ($at)").commit()
        ArchiveWidgetStatus.publish(c)
    }
    fun observe(c:Context,d:Device,state:JSONObject) {
        if(!state.has("values")||state.optJSONObject("context")?.optString("serial").isNullOrBlank())return
        val r=state.optJSONObject("receipt") ?: return
        if(r.optString("phase")!="complete")return
        val name=r.optString("profile_name","프로필")
        val text=if(state.optBoolean("current_matches_last"))"마지막 저장값 일치 확인" else "마지막 적용 후 개별 설정 변경"
        prefs(c,d.id).edit().putString("label","$name · $text").commit()
    }
    fun openIntent(c:Context):PendingIntent {
        val id=DeviceStore.getActive(c)?.id.orEmpty()
        return PendingIntent.getActivity(c,1951,Intent(c,TuningQuickActivity::class.java)
            .setData(Uri.parse("carrot-hud://tuning-picker/"+Uri.encode(id))).putExtra("device_id",id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
