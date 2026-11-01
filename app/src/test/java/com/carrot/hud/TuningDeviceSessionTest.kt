package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TuningDeviceSessionTest {
    private val clone=Device("original","LX3","100.98.217.122",7000,"UnregisteredDevice")
    private fun profile()=JSONObject(File("src/main/assets/tuning_profiles_v1.json").readText()).getJSONArray("profiles").getJSONObject(0)
    private fun receipt(serial:String="80acfe79")=JSONObject().put("ok",true).put("schema",1).put("family","LX3").put("context",JSONObject().put("serial",serial))
    private fun rejects(action:()->Unit){try{action();fail("must reject")}catch(_:IllegalArgumentException){}}
    @Test fun cloneUsesPhysicalSerialWithoutTreatingPlaceholderAsAccountIdentity(){
        var dongleReads=0;val calls=mutableListOf<String>()
        val session=TuningDeviceSession(clone,{clone},{dongleReads++;"UnregisteredDevice"},TuningApi {method,path,_ ->calls.add("$method $path");receipt()})
        assertEquals("80acfe79",session.serial);session.check();session.api.state()
        assertEquals(0,dongleReads);assertTrue(calls.all {it.startsWith("GET ")})
        assertFalse(OpPairingBinding.known(clone.dongle)) // OTA rules remain strict.
    }
    @Test fun unknownAccountNeverScansOrChangesTheRegisteredEndpoint(){
        var scans=0
        for(d in listOf(clone,clone.copy(dongle=""))){assertEquals(d,TuningDeviceSession.resolve(d){scans++;d.copy(ip="192.168.1.2")})}
        assertEquals(0,scans)
        val registered=clone.copy(dongle="known-account")
        assertEquals(registered,TuningDeviceSession.resolve(registered){scans++;it});assertEquals(1,scans)
    }
    @Test fun knownAccountMismatchStillFailsBeforeAnyProfileRequest(){
        var reads=0
        rejects {TuningDeviceSession(clone.copy(dongle="known"),{clone.copy(dongle="known")},{"other"},TuningApi {_,_,_->reads++;receipt()})}
        assertEquals(0,reads)
    }
    @Test fun selectionEndpointAndIdentityEditsBlockEveryPost(){
        for(changed in listOf(clone.copy(id="additional"),clone.copy(ip="100.71.19.14"),clone.copy(port=7001),clone.copy(dongle="changed"),clone.copy(identityNeedsCheck=true))){
            var active=clone;var posts=0
            val session=TuningDeviceSession(clone,{active},{null},TuningApi {method,_,_->if(method=="POST")posts++;receipt()})
            active=changed
            rejects {session.api.preview(profile())};assertEquals(0,posts)
        }
    }
    @Test fun aDifferentPhysicalDeviceOnTheSameAddressCannotReceiveAPost(){
        var serial="80acfe79";var posts=0
        val session=TuningDeviceSession(clone,{clone},{null},TuningApi {method,_,_->if(method=="POST")posts++;receipt(serial)})
        serial="other-device";rejects {session.api.preview(profile())};assertEquals(0,posts)
    }
    @Test fun registrationChangingDuringIdentityReadIsCaughtBeforePost(){
        var active=clone;var posts=0;var switch=false
        val session=TuningDeviceSession(clone,{active},{null},TuningApi {method,_,_->if(method=="POST")posts++;if(switch)active=clone.copy(id="other");receipt()})
        switch=true;rejects {session.api.preview(profile())};assertEquals(0,posts)
    }
    @Test fun switchedReadReplyIsNotAcceptedAsCurrentStoredSettings(){
        val session=TuningDeviceSession(clone,{clone},{null},TuningApi {_,path,_->receipt(if(path.endsWith("/receipt"))"80acfe79" else "wrong-device")})
        rejects {session.api.state()}
    }
    @Test fun missingOrPlaceholderSerialAndOtherFamilyStayRejected(){
        for(s in listOf("","UnregisteredDevice","bad serial"))rejects {TuningDeviceSession(clone,{clone},{null},TuningApi {_,_,_->receipt(s)})}
        rejects {TuningDeviceSession(clone,{clone},{null},TuningApi {_,_,_->receipt().put("family","EV9")})}
    }
    @Test fun allFiveProfilesUseOneApplyPostAndVerifiedReadbackOnTheClone(){
        val fixture=JSONObject(File("src/main/assets/tuning_profiles_v1.json").readText());val profiles=fixture.getJSONArray("profiles")
        for(i in 0 until profiles.length()){
            val profile=profiles.getJSONObject(i);val id="b".repeat(32);var applied=false;var writes=0
            fun state():JSONObject {
                val values=JSONObject(profile.getJSONObject("values").toString());if(!applied)values.put("ApproachCoastStrength",0)
                return receipt().put("fields",fixture.getJSONObject("contract").getJSONObject("fields")).put("values",values)
                    .put("receipt",if(applied)JSONObject().put("request_id",id).put("phase","complete") else JSONObject.NULL)
            }
            val raw=TuningApi {method,path,_ ->when {
                method=="GET" ->if(path.endsWith("/receipt"))receipt() else state()
                path.endsWith("/preview") ->receipt().put("revision","a".repeat(64)).put("profile_hash","c".repeat(64))
                else ->{writes++;applied=true;JSONObject().put("ok",true)}
            }}
            val session=TuningDeviceSession(clone,{clone},{null},raw)
            val result=TuningQuickApply(session.api,{session.check()},{_,_,_->},{session.check(false)}).apply(profile,id)
            assertEquals(QuickPhase.VERIFIED,result.phase);assertEquals(1,writes)
        }
    }
}
