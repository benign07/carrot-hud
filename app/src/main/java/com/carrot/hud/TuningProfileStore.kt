package com.carrot.hud
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

data class TuningCatalog(val profiles:MutableList<JSONObject>,val problems:List<String>)
/** One loader for the full editor and quick chooser; incompatible files stay untouched. */
object TuningProfileStore {
    fun load(c:Context):TuningCatalog {
        val list=mutableListOf<JSONObject>();val problems=mutableListOf<String>()
        try{val a=JSONObject(c.assets.open("tuning_profiles_v1.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
            for(i in 0 until a.length())list.add(TuningProfiles.parse(a.getJSONObject(i).toString()))
        }catch(e:Exception){problems.add("내장 프로필: ${e.message}")}
        val legacy=File(c.filesDir,"tuning-profiles-v1.json")
        if(legacy.exists())try{require(legacy.length()<=1_310_720);val a=JSONArray(legacy.readText())
            for(i in 0 until a.length())try{list.add(TuningProfiles.parse(a.getJSONObject(i).toString()))}catch(_:Exception){problems.add("기존 사용자 프로필${i+1}: 호환 안 됨·원본 보존")}
        }catch(_:Exception){problems.add("기존 사용자 저장소: 손상/미지원·원본 보존")}
        val files=File(c.filesDir,"tuning-profiles-v1").listFiles()?.filter{it.extension=="json"}?.sortedBy{it.name} ?: emptyList()
        for(file in files)try{require(file.length()<=32768);list.add(TuningProfiles.parse(file.readText()))}
            catch(_:Exception){problems.add("${file.name}: 호환 안 됨·원본 보존")}
        return TuningCatalog(list,problems)
    }
}
