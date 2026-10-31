package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TuningProfilePresentationTest {
    private fun state()=JSONObject().put("ok",true).put("schema",1).put("family","LX3").put("context",JSONObject().put("serial","80acfe79"))
        .put("values",JSONObject().put("ApproachBrakeMargin",10)).put("receipt",JSONObject.NULL)
    @Test fun fullEditorLabelsTheDefaultSelectionAsCandidateNotCurrentProfile(){
        val controller=org.robolectric.Robolectric.buildActivity(TuningProfilesActivity::class.java).create()
        fun all(v:android.view.View):List<android.view.View> = listOf(v)+(if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList())
        val views=all(controller.get().findViewById(android.R.id.content))
        val selector=views.filterIsInstance<android.widget.Spinner>().single()
        assertEquals(5,selector.adapter.count)
        assertTrue(selector.adapter.getItem(0).toString().startsWith("선택 후보 · "))
        assertTrue(views.filterIsInstance<android.widget.TextView>().any {it.text.toString().startsWith("현재 저장 상태:")})
        assertTrue(views.filterIsInstance<android.widget.TextView>().any {it.text.toString().contains("선택만으로 저장되지 않습니다.")})
        assertFalse(TuningOperationGate.isBusy());controller.destroy()
    }
    @Test fun existingHealthRefreshReadsStoredValuesWithoutWrites(){
        val calls=mutableListOf<String>()
        val result=TuningProfileHealth.readStatus(TuningApi {method,path,_ ->calls.add("$method $path");state()})
        assertEquals(listOf("GET /api/tuning_profiles"),calls)
        assertEquals("개별 설정 · 프로필 적용 기록 없음",TuningProfilePresentation.stored(result))
    }
    @Test fun badValuesStillAllowReceiptRecoveryWarnings(){
        val calls=mutableListOf<String>();val receipt=JSONObject().put("ok",true).put("schema",1).put("recovery_required",true)
        val result=TuningProfileHealth.readStatus(TuningApi {method,path,_ ->
            calls.add("$method $path")
            if(path=="/api/tuning_profiles")error("invalid saved value") else receipt
        })
        assertTrue(result.optBoolean("recovery_required"))
        assertEquals(listOf("GET /api/tuning_profiles","GET /api/tuning_profiles/receipt"),calls)
    }
    @Test fun absentReceiptDoesNotPretendTheDefaultFirstSelectionWasApplied(){
        assertEquals("개별 설정 · 프로필 적용 기록 없음",TuningProfilePresentation.stored(state()))
    }
    @Test fun partialReceiptPollCannotEraseFullStateEvidence(){
        val c=RuntimeEnvironment.getApplication();val d=DeviceStore.addDevice(c,"LX3","100.98.217.122")
        TuningWidgetStatus.observe(c,d,state());val previous=TuningWidgetStatus.text(c)
        TuningWidgetStatus.observe(c,d,JSONObject().put("ok",true).put("schema",1).put("receipt",JSONObject.NULL))
        assertEquals(previous,TuningWidgetStatus.text(c));assertTrue(previous.contains("프로필 적용 기록 없음"))
    }
    @Test fun selectionOrMatchingValuesWithoutAReceiptNeverClaimsApplied(){
        assertFalse(TuningProfilePresentation.stored(state().put("current_matches_last",true))!!.contains("저장값 일치"))
        val p=JSONObject().put("name","01 일상 균형 조합").put("description","[조합 의도] 균형")
        TuningProfilePresentation.intent(p)
        assertEquals("개별 설정 · 프로필 적용 기록 없음",TuningProfilePresentation.stored(state()))
    }
    @Test fun completedReceiptWithChangedValuesIsClearlyIndividualSettings(){
        val s=state().put("receipt",JSONObject().put("phase","complete").put("profile_name","02 매끄러운 전환 조합"))
        assertTrue(TuningProfilePresentation.stored(s)!!.contains("적용 후 변경됨"))
        assertTrue(TuningProfilePresentation.stored(s.put("current_matches_last",true))!!.contains("현재 저장값 일치"))
    }
    @Test fun recoveryAndUncertaintyOverrideOldCompleteReceipt(){
        val s=state().put("receipt",JSONObject().put("phase","complete").put("profile_name","01"))
        assertTrue(TuningProfilePresentation.stored(s.put("in_progress",true))!!.contains("진행 중"))
        s.put("in_progress",false).put("recovery_required",true)
        assertTrue(TuningProfilePresentation.stored(s)!!.contains("복구 확인 필요"))
        s.put("recovery_required",false).put("unverified_previous",true)
        assertTrue(TuningProfilePresentation.stored(s)!!.contains("결과 미확인"))
    }
    @Test fun malformedAndUnsupportedStatesDoNotReplaceEvidence(){
        assertNull(TuningProfilePresentation.stored(state().put("schema",2)))
        assertNull(TuningProfilePresentation.stored(state().put("receipt","bad")))
        assertNull(TuningProfilePresentation.stored(state().put("ok",false)))
        val s=state();s.remove("receipt");assertNull(TuningProfilePresentation.stored(s))
    }
    @Test fun allFiveGoldenDescriptionsUseTheSameUntruncatedIntent(){
        val catalog=TuningProfileStore.load(RuntimeEnvironment.getApplication());assertEquals(5,catalog.profiles.size)
        for(p in catalog.profiles){
            val intent=TuningProfilePresentation.intent(p)
            assertTrue(intent.isNotBlank());assertFalse(intent.contains("[발동 조건]"))
            assertTrue(p.getString("description").contains("[조합 의도] "+intent))
        }
        assertEquals("사용자 설명",TuningProfilePresentation.intent(JSONObject().put("description","사용자 설명")))
    }
}
