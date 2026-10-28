package com.carrot.hud
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

class TuningProfilesTest {
    private fun presets()=JSONObject(File("src/main/assets/tuning_profiles_v1.json").readText()).getJSONArray("profiles")
    private fun p()=JSONObject(presets().getJSONObject(0).toString())
    private fun rejects(raw:String) {try{TuningProfiles.parse(raw);fail("Expected profile rejection")}catch(_:IllegalArgumentException){}catch(_:org.json.JSONException){}}
    private fun state(profile:JSONObject):JSONObject {
        val fields=JSONObject();for(k in TuningProfiles.units.keys)fields.put(k,JSONObject().put("unit",TuningProfiles.units[k]).put("min",0).put("max",10000))
        return JSONObject().put("ok",true).put("schema",1).put("family","LX3").put("fields",fields).put("values",profile.getJSONObject("values")).put("context",JSONObject().put("serial","device-A"))
    }
    @Test fun fivePresetsRoundtripAndNeverTouchHardware() {
        val a=presets();assertEquals(5,a.length())
        for(i in 0 until a.length()) {
            val v=TuningProfiles.parse(a.getJSONObject(i).toString());assertEquals(27,v.getJSONObject("values").length())
            assertEquals(TuningProfiles.units.keys,v.getJSONObject("values").keys().asSequence().toSet())
            assertFalse(v.getJSONObject("values").has("DisableDM"));assertFalse(v.getJSONObject("values").has("UseLaneLineSpeed"))
            val exported=TuningProfiles.fromValues("current",v.getJSONObject("values"));assertTrue(TuningProfiles.matches(exported,state(v)))
        }
    }
    @Test fun badSchemaFamilyScopeAndWorkFileRejected() {
        rejects(p().put("schema",2).toString());rejects(p().put("schema",true).toString());rejects(p().put("family","EV9").toString())
        rejects(p().put("scope","lateral").toString());rejects("{\"Format\":\"CarrotTuningLab-v1\",\"B\":{}}")
        rejects(p().put("name","").toString());rejects(p().put("id","../unsafe").toString())
    }
    @Test fun duplicateKeysNumbersTrailingDataAndBadUnitsRejected() {
        rejects("{\"kind\":\"x\",\"kind\":\"CarrotTuningProfile\"}")
        val raw=p().toString();rejects(raw+" {}");rejects(raw.replace("\"ApproachCoastStrength\":60","\"ApproachCoastStrength\":true"))
        rejects(raw.replace("\"ApproachCoastStrength\":60","\"ApproachCoastStrength\":60.0"))
        rejects(raw.replace("\"ApproachCoastStrength\":60","\"ApproachCoastStrength\":\"60\""))
        rejects(p().apply {getJSONObject("units").put("TFollowGap1","seconds")}.toString())
        rejects(p().apply {getJSONObject("values").put("DisableDM",0);getJSONObject("units").put("DisableDM","bool-int")}.toString())
    }
    @Test fun firmwareRangeOrMissingFieldIsNotClamped() {
        val profile=p();val s=state(profile);s.getJSONObject("fields").remove("LaunchFollowStrength")
        try{TuningProfiles.validateFor(profile,s);fail()}catch(_:Exception){}
        val s2=state(profile);s2.getJSONObject("fields").getJSONObject("ApproachCoastStrength").put("max",40)
        try{TuningProfiles.validateFor(profile,s2);fail()}catch(_:Exception){}
        assertEquals(60,profile.getJSONObject("values").getInt("ApproachCoastStrength"))
    }
    @Test fun uncertainApplyNeverAutomaticallyRepeatsPost() {
        var posts=0;val api=TuningApi {method,_,_->if(method=="POST"){posts++;throw java.io.IOException("response lost")}else state(p())}
        val preview=JSONObject().put("revision","a".repeat(64)).put("profile_hash","c".repeat(64)).put("context",JSONObject().put("serial","device-A"))
        try{api.apply(p(),preview,"b".repeat(32));fail()}catch(_:java.io.IOException){}
        api.state();assertEquals(1,posts)
    }
    @Test fun strictScannerAcceptsQuotedDescriptionsAndRejectsNestedDuplicate() {
        StrictTuningJson.check("{\"text\":\"a\\\"b\",\"array\":[true,false,null,-1,1.5]}")
        try{StrictTuningJson.check("{\"values\":{\"x\":1,\"x\":2}}");fail()}catch(_:IllegalArgumentException){}
        try{StrictTuningJson.check("{\"x\":NaN}");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun rfcScannerRejectsCommentsControlsAndNegativeZero() {
        StrictTuningJson.check("""{"x":[true,false,null,0,-1,1.5]}""")
        val bad=listOf("""{/*comment*/"x":1}""", """{//comment
          "x":1}""", """{#comment
          "x":1}""", """{"x":-0}""", """{"x":"bad
          text"}""")
        for(raw in bad) {try{StrictTuningJson.check(raw);fail(raw)}catch(_:Exception){}}
    }

}
