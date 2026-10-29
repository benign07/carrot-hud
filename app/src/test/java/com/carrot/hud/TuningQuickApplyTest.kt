package com.carrot.hud
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.IOException

class TuningQuickApplyTest {
    private val id="b".repeat(32)
    private fun p()=JSONObject(File("src/main/assets/tuning_profiles_v1.json").readText()).getJSONArray("profiles").getJSONObject(0)
    private fun state(profile:JSONObject=p(),phase:String="",owner:String=id,match:Boolean=true,serial:String="device-A"):JSONObject {
        val contract=JSONObject(File("src/main/assets/tuning_profiles_v1.json").readText()).getJSONObject("contract")
        val values=JSONObject(profile.getJSONObject("values").toString());if(!match)values.put("ApproachCoastStrength",0)
        val s=JSONObject().put("ok",true).put("schema",1).put("family","LX3").put("fields",contract.getJSONObject("fields"))
            .put("context",JSONObject().put("serial",serial)).put("values",values).put("recovery_required",false).put("in_progress",false)
        if(phase.isNotEmpty())s.put("receipt",JSONObject().put("request_id",owner).put("phase",phase))
        return s
    }
    private fun preview()=JSONObject().put("ok",true).put("revision","a".repeat(64)).put("profile_hash","c".repeat(64)).put("context",JSONObject().put("serial","device-A"))
    private class Session(val response:JSONObject?=null,val error:Exception?=null,val after:()->JSONObject) {
        var posts=0;var reads=0;var persisted=false;val order=mutableListOf<String>();var before:JSONObject?=null
        lateinit var initial:JSONObject
        fun api()=TuningApi {method,path,body ->
            when {
                method=="GET"->{reads++;order.add("read");if(reads==1)initial else after()}
                path.endsWith("preview")->JSONObject().put("ok",true).put("revision","a".repeat(64)).put("profile_hash","c".repeat(64)).put("context",JSONObject().put("serial","device-A"))
                else->{assertTrue(persisted);assertEquals("device-A",body!!.getString("serial"));posts++;order.add("post");if(error!=null)throw error;response ?: JSONObject().put("ok",true)}
            }
        }
    }
    private fun run(s:Session,check:()->Unit={},persist:()->Unit={}):QuickResult {
        s.initial=s.before ?: state(match=false)
        return TuningQuickApply(s.api(),check,{_,_,_->persist();s.persisted=true;s.order.add("persist")}).apply(p(),id)
    }
    @Test fun oneSelectionPostsOnceAndRequiresOurReceiptPlusReadback(){
        val s=Session(after={state(phase="complete")});val result=run(s)
        assertEquals(QuickPhase.VERIFIED,result.phase);assertEquals(1,s.posts);assertTrue(s.order.indexOf("persist")<s.order.indexOf("post"))
    }
    @Test fun lostResponseReconcilesWithReadOnlyFetchNeverAnotherPost(){
        val s=Session(error=IOException("lost"),after={state(phase="complete")});assertEquals(QuickPhase.VERIFIED,run(s).phase);assertEquals(1,s.posts)
    }
    @Test fun sameValuesWithoutOurReceiptAreNotAClaimOfApplied(){
        val s=Session(error=IOException("lost"),after={state(phase="complete",owner="other")});assertEquals(QuickPhase.UNKNOWN,run(s).phase);assertFalse(runResult(s).clearPending)
    }
    private fun runResult(s:Session)=TuningQuickApply.reconcile(p(),id,"device-A",s.after())
    @Test fun explicitParkedRejectionDoesNotQueueOrRetry(){
        val s=Session(error=TuningHttpError(409,"P required"),after={state()});val r=run(s);assertEquals(QuickPhase.REJECTED,r.phase);assertTrue(r.clearPending);assertEquals(1,s.posts)
    }
    @Test fun storageFailureCannotSend(){
        val s=Session(after={state()});assertEquals(QuickPhase.REJECTED,run(s,persist={throw IOException("disk")}).phase);assertEquals(0,s.posts)
    }
    @Test fun targetSwitchBeforePostCannotSend(){
        val s=Session(after={state()});var n=0;assertEquals(QuickPhase.REJECTED,run(s,check={n++;if(n==4)error("changed")}).phase);assertEquals(0,s.posts)
    }
    @Test fun targetSwitchAfterPostIsUnknownNeverWrittenToAnotherTarget(){
        val s=Session(after={state(phase="complete")});var n=0;assertEquals(QuickPhase.UNKNOWN,run(s,check={n++;if(n==5)error("changed")}).phase);assertEquals(1,s.posts)
    }
    @Test fun completeReceiptWithDifferentValuesIsUnknown(){
        val s=Session(after={state(phase="complete",match=false)});assertEquals(QuickPhase.UNKNOWN,run(s).phase)
    }
    @Test fun differentHardwareSerialIsUnknown(){
        val s=Session(after={state(phase="complete",serial="device-B")});assertEquals(QuickPhase.UNKNOWN,run(s).phase)
    }
    @Test fun restoredFailureIsNotSuccessAndCanClearPending(){
        val s=Session(error=TuningHttpError(409,"failed"),after={state(phase="rolled_back",match=false)});val r=run(s);assertEquals(QuickPhase.RESTORED,r.phase);assertTrue(r.clearPending)
    }
    @Test fun inProgressOrRecoveryNeverClaimsSuccess(){
        for(key in listOf("in_progress","recovery_required")){
            val s=Session(after={state(phase="complete").put(key,true)});assertEquals(QuickPhase.UNKNOWN,run(s).phase)
        }
    }
    @Test fun priorUnfinishedStateDoesNotWrite(){
        val s=Session(after={state()});s.before=state().put("recovery_required",true);assertEquals(QuickPhase.REJECTED,run(s).phase);assertEquals(0,s.posts)
    }
    @Test fun successfulUnchangedResponseIsLabelledAsExistingValues(){
        val s=Session(response=JSONObject().put("ok",true).put("phase","unchanged"),after={state()});assertEquals(QuickPhase.UNCHANGED,run(s).phase)
    }
    @Test fun gateIsSharedAndDoesNotQueue(){
        assertTrue(TuningOperationGate.enter());try{assertFalse(TuningOperationGate.enter());assertTrue(TuningOperationGate.isBusy())}finally{TuningOperationGate.leave()};assertTrue(TuningOperationGate.enter());TuningOperationGate.leave()
    }
}
