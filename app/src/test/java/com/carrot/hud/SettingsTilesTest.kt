package com.carrot.hud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SettingsTilesTest {
    private val p=TileSetting("ApproachBrakeMargin","감속 여유","원본 설명",0,100,5,"개발 중 기능")
    private fun read(v:Any?)=JSONObject().put("ok",true).put("values",JSONObject().apply {if(v!=null)put(p.name,v)})
    private fun ack(v:Any=10)=JSONObject().put("ok",true).put("has_params",true).put("name",p.name).put("value",v)
    private fun fixture()=JSONObject(javaClass.getResourceAsStream("/settings_tiles_lx3_v22.json")!!.bufferedReader().use {it.readText()})
    @Test fun all184FirmwareDefinitionsAppearOnceWithoutRenamingOrReusingDefaultsAsValues(){
        val c=SettingsTiles.parse(fixture());val keys=c.categories.flatMap {it.sections.flatMap {s->s.names}}
        assertEquals(184,keys.size);assertEquals(184,keys.toSet().size);assertEquals(c.definitions.keys,keys.toSet());assertTrue(c.hasParams)
        assertEquals("development",c.categories.first().id);assertEquals(7,c.categories.size)
        assertEquals(0,c.definitions.getValue(p.name).minimum);assertEquals(100,c.definitions.getValue(p.name).maximum)
    }
    @Test fun flatOlderFirmwareAndUnknownCategoryRemainVisible(){
        for(tree in listOf(false,true)){
            val f=fixture();if(!tree)f.remove("categories")else f.getJSONArray("categories").getJSONObject(0).put("id","future-menu")
            val c=SettingsTiles.parse(f);assertEquals(184,c.categories.sumOf {it.count});assertTrue(c.categories.any {it.id=="other"})
        }
    }
    @Test fun duplicateDefinitionsAreRejectedNotSilentlyOverwritten(){
        val f=fixture();val all=f.getJSONObject("items_by_group");val first=all.getJSONArray(all.keys().next()).getJSONObject(0)
        all.put("duplicate",JSONArray().put(first));assertThrows(IllegalArgumentException::class.java){SettingsTiles.parse(f)}
    }
    @Test fun missingOrCoercedValuesNeverTurnIntoDeviceDefaults(){
        for(v in listOf(null,true,"10",10.5,Long.MAX_VALUE)){
            val api=SettingsTilesApi {_,_,_->read(v)};assertFalse(api.values(listOf(p.name)).containsKey(p.name))
        }
    }
    @Test fun readRequestsAreBatchedAndNeverPost(){
        val names=(0..183).map {"Param$it"};var calls=0
        val api=SettingsTilesApi {m,path,_->assertEquals("GET",m);calls++;val keys=path.substringAfter("names=").split(',');assertTrue(keys.size<=32);JSONObject().put("ok",true).put("values",JSONObject().apply {keys.forEach {put(it,10)}})}
        assertEquals(184,api.values(names).size);assertEquals(6,calls)
    }
    @Test fun validWriteRequiresExactAckAndReadback(){
        var current=40;var posts=0
        val api=SettingsTilesApi {m,_,_->if(m=="POST"){posts++;current=10;ack()}else read(current)}
        val r=api.write(p,40,10){};assertTrue(r.confirmed);assertEquals(10,r.current);assertEquals(1,posts)
    }
    @Test fun changedExpectedValueBlocksBeforePost(){
        var posts=0;val api=SettingsTilesApi {m,_,_->if(m=="POST")posts++;read(60)}
        assertThrows(IllegalArgumentException::class.java){api.write(p,40,10){}};assertEquals(0,posts)
    }
    @Test fun eitherIdentityOrLifecycleGuardRejectionPreventsPost(){
        for(reject in listOf(1,2)){
            var guards=0;var posts=0;val api=SettingsTilesApi {m,_,_->if(m=="POST")posts++;read(40)}
            assertThrows(IllegalArgumentException::class.java){api.write(p,40,10){guards++;require(guards!=reject){"target changed"}}};assertEquals(0,posts)
        }
    }
    @Test fun invalidRangeNeverClampsOrMakesARequest(){
        var calls=0;val api=SettingsTilesApi {_,_,_->calls++;read(40)}
        for(v in listOf(-1,101))assertThrows(IllegalArgumentException::class.java){api.write(p,40,v){}};assertEquals(0,calls)
    }
    @Test fun lostPostResponseEvenAfterActualWriteIsUnconfirmedAndNeverRetried(){
        var current=40;var posts=0;val api=SettingsTilesApi {m,_,_->if(m=="POST"){posts++;current=10;throw IOException("response lost")}else read(current)}
        val r=api.write(p,40,10){};assertFalse(r.confirmed);assertEquals(10,r.current);assertEquals(1,posts)
    }
    @Test fun wrongOrRejectedAckCannotBeConfirmedByCoincidentallyMatchingReadback(){
        val bad=listOf(ack().put("ok",false),ack().put("has_params",false),ack().put("name","Other"),ack("10"),ack(9))
        for(reply in bad){var reads=0;var posts=0;val api=SettingsTilesApi {m,_,_->if(m=="POST"){posts++;reply}else read(if(reads++==0)40 else 10)}
            assertFalse(api.write(p,40,10){}.confirmed);assertEquals(1,posts)}
    }
    @Test fun exactAckWithMissingOrDifferentReadbackIsUnconfirmed(){
        for(after in listOf(null,40)){var reads=0;val api=SettingsTilesApi {m,_,_->if(m=="POST")ack()else read(if(reads++==0)40 else after)}
            assertFalse(api.write(p,40,10){}.confirmed)}
    }
    @Test fun parentGatesAndIndependentLaunchAreNotConfused(){
        assertTrue(SettingsTiles.condition("ApproachStopSmooth",mapOf("ApproachComfortEnabled" to 0)).contains("대기"))
        assertTrue(SettingsTiles.condition("ApproachFlexibleGapMin",mapOf("ApproachComfortEnabled" to 1,"ApproachFlexibleGap" to 0)).contains("대기"))
        assertTrue(SettingsTiles.condition("LaunchFollowStrength",mapOf("ApproachComfortEnabled" to 0,"LaunchFollowStrength" to 20)).contains("독립"))
        assertTrue(SettingsTiles.condition("NearbyVehicleBiasCm",mapOf("UseLaneLineSpeed" to 0)).contains("모델 기본"))
        assertTrue(SettingsTiles.condition("NearbyVehicleBiasCm",mapOf("UseLaneLineSpeed" to 30,"LateralMarginShadow" to 1)).contains("기록 전용"))
        assertTrue(SettingsTiles.condition("ManualSteerWithBlinker",mapOf("LaneChangeDelay" to -1,"LaneChangeNeedTorque" to 1)).contains("대기"))
    }
    @Test fun displayUnitConversionDoesNotChangeRawFields(){
        assertEquals("0.70초",SettingsTiles.value(p.copy(name="TFollowGap1"),70));assertEquals("5.50m",SettingsTiles.value(p.copy(name="StopDistanceCarrot"),550))
        assertEquals("조회 미확인",SettingsTiles.value(p,null));assertNull(SettingsTiles.integer("10"));assertNull(SettingsTiles.integer(true))
    }
    @Test fun explicitZeroAndUnreadParentAreNotAdvertisedAsReady(){
        assertTrue(SettingsTiles.condition("ApproachCoastStrength",mapOf("ApproachCoastStrength" to 0,"ApproachComfortEnabled" to 1)).contains("강도0"))
        assertTrue(SettingsTiles.condition("ApproachFlexibleGapMin",mapOf("ApproachComfortEnabled" to 1)).contains("설정 확인 필요"))
    }
}
