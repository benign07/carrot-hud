package com.carrot.hud

import org.json.JSONObject

/** Display only: never infer application from a selected preset or matching values alone. */
object TuningProfilePresentation {
    const val modeExplanation="운전모드(연비·완만·일반·고속)는 프로필과 별개입니다. 모드를 바꾸면 프로필의 일부 값도 달라집니다."
    fun intent(profile:JSONObject):String {
        val description=profile.optString("description")
        return description.split("\n\n").firstOrNull {it.startsWith("[조합 의도]")}
            ?.removePrefix("[조합 의도]")?.trim() ?: description
    }
    /** Only a complete state response can replace cached storage evidence. */
    fun stored(state:JSONObject):String? {
        if(!state.optBoolean("ok")||state.opt("schema")!=1||!state.has("receipt")||
            state.optJSONObject("values")==null||state.optJSONObject("context")?.optString("serial").isNullOrBlank())return null
        if(state.optBoolean("in_progress"))return "프로필 적용 진행 중 · 완료 확인 전"
        if(state.optBoolean("recovery_required"))return "프로필 적용 결과·복구 확인 필요"
        if(state.optBoolean("unverified_previous"))return "이전 프로필 결과 미확인 · 현재 값 재확인 필요"
        if(state.isNull("receipt"))return "개별 설정 · 프로필 적용 기록 없음"
        val receipt=state.optJSONObject("receipt") ?: return null
        if(receipt.optString("phase")!="complete")return "현재 개별 설정 · 직전 프로필 적용 미완료"
        val name=receipt.optString("profile_name").ifBlank {"이름 없는 프로필"}
        return if(state.optBoolean("current_matches_last"))"$name · 현재 저장값 일치"
            else "$name 적용 후 변경됨 · 현재 개별 설정"
    }
}
