package com.carrot.hud

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Shared with the full editor. A failed try never queues a later write. */
object TuningOperationGate {
    private val busy=AtomicBoolean(false)
    fun enter()=busy.compareAndSet(false,true)
    fun leave(){busy.set(false)}
    fun isBusy()=busy.get()
}

class TuningHttpError(val code:Int,message:String):IllegalStateException(message)
enum class QuickPhase { VERIFIED, UNCHANGED, REJECTED, RESTORED, UNKNOWN }
data class QuickResult(val phase:QuickPhase,val message:String,val state:JSONObject?=null) {
    val clearPending:Boolean get()=phase!=QuickPhase.UNKNOWN
}

/** Network-independent state machine: a selection means at most one apply POST. */
class TuningQuickApply(
    private val api:TuningApi,
    private val assertTarget:()->Unit,
    private val persist:(String,JSONObject,String)->Unit
) {
    fun apply(input:JSONObject,id:String):QuickResult {
        var posted=false
        var serial=""
        try {
            require(id.matches(Regex("[a-f0-9]{32}"))){"요청 식별 오류"}
            val profile=TuningProfiles.parse(input.toString())
            assertTarget()
            val before=api.state();serial=before.getJSONObject("context").getString("serial")
            require(!before.optBoolean("recovery_required")&&!before.optBoolean("in_progress")){"이전 적용 결과·복구를 먼저 확인하세요."}
            TuningProfiles.validateFor(profile,before)
            assertTarget()
            val preview=api.preview(profile)
            require(preview.getJSONObject("context").getString("serial")==serial){"대상 기기가 바뀌었습니다."}
            require(preview.optString("profile_hash").matches(Regex("[a-f0-9]{64}"))){"프로필 식별을 확인하지 못했습니다."}
            assertTarget()
            persist(id,profile,serial)
            assertTarget()
            var response:JSONObject?=null
            var error:Exception?=null
            posted=true
            try{response=api.apply(profile,preview,id)}catch(e:Exception){error=e}
            // Even if the active selection changed, GET only the original endpoint.
            val after=try{api.state()}catch(_:Exception){return QuickResult(QuickPhase.UNKNOWN,"완료 미확인 · 자동 재적용 없음. 결과 확인을 누르세요.")}
            assertTarget()
            return reconcile(profile,id,serial,after,response?.optString("phase")=="unchanged",error)
        }catch(e:Exception){
            return QuickResult(if(posted)QuickPhase.UNKNOWN else QuickPhase.REJECTED,
                if(posted)"완료 미확인 · 자동 재적용 없음: ${e.message}" else "적용하지 않음: ${e.message}")
        }
    }
    companion object {
        fun reconcile(profile:JSONObject,id:String,serial:String,state:JSONObject,unchanged:Boolean=false,error:Exception?=null):QuickResult {
            if(state.optJSONObject("context")?.optString("serial")!=serial)
                return QuickResult(QuickPhase.UNKNOWN,"대상 기기 식별 불일치 · 결과 미확인")
            val receipt=state.optJSONObject("receipt")
            val ours=receipt?.optString("request_id")==id
            if(state.optBoolean("recovery_required")||state.optBoolean("in_progress"))
                return QuickResult(QuickPhase.UNKNOWN,"적용 진행·복구 확인 필요 · 튜닝 메뉴에서 확인",state)
            if(ours&&receipt!!.optString("phase")=="rolled_back")
                return QuickResult(QuickPhase.RESTORED,"적용 실패 · 적용 전 값 복원 확인",state)
            if(TuningProfiles.matches(profile,state)) {
                if(unchanged)return QuickResult(QuickPhase.UNCHANGED,"현재 저장값이 이미 같은 프로필입니다.",state)
                if(ours&&receipt!!.optString("phase")=="complete")
                    return QuickResult(QuickPhase.VERIFIED,"프로필 저장 확인 완료",state)
            }
            if(error is TuningHttpError&&error.code in 400..499&&!ours)
                return QuickResult(QuickPhase.REJECTED,"적용 거절 · P·속도0·제어 해제 후 다시 선택: ${error.message}",state)
            return QuickResult(QuickPhase.UNKNOWN,"요청 결과·저장값 미확인 · 자동 재적용 없음",state)
        }
    }
}
