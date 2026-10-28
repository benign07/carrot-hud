package com.carrot.hud

import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Versioned longitudinal-only interchange. Never treat a PC184-key work file as a vehicle profile. */
object TuningProfiles {
    private fun keys(o:JSONObject)=o.keys().asSequence().toSet()
    val units = mapOf(
            "MyDrivingMode" to "mode-enum-v1",
            "ApproachComfortEnabled" to "bool-int",
            "ApproachCoastStrength" to "percent-strength-v1",
            "ApproachBrakeMargin" to "percent-strength-v1",
            "ApproachStopSmooth" to "percent-strength-v1",
            "ApproachTrafficCalm" to "percent-strength-v1",
            "ApproachFlexibleGap" to "bool-int",
            "ApproachFlexibleGapMin" to "moving-tf-percent",
            "TFollowGap1" to "centisecond",
            "TFollowGap2" to "centisecond",
            "TFollowGap3" to "centisecond",
            "TFollowGap4" to "centisecond",
            "EnableSpeedTF" to "speed-tf-selector-v1",
            "TFollowDecelBoost" to "percent-strength-v1",
            "DynamicTFollow" to "percent-strength-v1",
            "StopDistanceCarrot" to "centimeter",
            "AChangeCostStarting" to "cost-weight",
            "CruiseMaxVals0" to "centi-mps2",
            "CruiseMaxVals1" to "centi-mps2",
            "CruiseMaxVals2" to "centi-mps2",
            "CruiseMaxVals3" to "centi-mps2",
            "CruiseMaxVals4" to "centi-mps2",
            "CruiseMaxVals5" to "centi-mps2",
            "CruiseMaxVals6" to "centi-mps2",
            "LaunchFollowStrength" to "percent-strength-v1",
            "MyDrivingModeAuto" to "auto-mode-enum-v1",
            "DynamicTFollowLC" to "percent-strength-v1"
    )
    fun parse(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32768) { "프로필 파일이 너무 큽니다." }
        StrictTuningJson.check(raw)
        val p = JSONObject(raw)
        require(p.optString("kind") == "CarrotTuningProfile" && p.opt("schema") == 1 &&
            p.optString("family") == "LX3" && p.optString("scope") == "longitudinal") {
            "HUD용 튜닝 프로필이 아닙니다. 시뮬레이터에서 HUD 프로필 내보내기를 사용하세요."
        }
        require(p.optString("id").matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) && p.optString("name").trim().length in 1..80)
        val v=p.getJSONObject("values"); val u=p.getJSONObject("units")
        require(v.length() in 1..units.size && keys(u)==keys(v))
        for(k in keys(v)) {
            require(units[k]!=null && units[k]==u.getString(k)) { "$k: 미지원 설정 또는 단위" }
            require(v.get(k) is Int || v.get(k) is Long) { "$k: 정수값 필요" }
            require(v.getLong(k) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        }
        return p
    }
    fun fromValues(name: String, values: JSONObject): JSONObject {
        val v=JSONObject();val u=JSONObject()
        for(k in keys(values)) { require(k in units); v.put(k,values.get(k));u.put(k,units[k]) }
        return parse(JSONObject().put("kind","CarrotTuningProfile").put("schema",1).put("family","LX3")
            .put("scope","longitudinal").put("id","user-"+UUID.randomUUID().toString().replace("-","").take(20))
            .put("name",name).put("values",v).put("units",u).toString())
    }
    fun validateFor(p: JSONObject, state: JSONObject) {
        parse(p.toString())
        require(state.optBoolean("ok") && state.opt("schema")==1 && state.optString("family")=="LX3") { "지원하지 않는 오파 프로필 버전입니다." }
        val fields=state.getJSONObject("fields");val values=p.getJSONObject("values")
        for(k in keys(values)) {
            val f=fields.optJSONObject(k) ?: error("$k: 현재 오파에서 지원하지 않음")
            require(f.getString("unit")==units[k] && values.getInt(k) in f.getInt("min")..f.getInt("max")) { "$k: 현재 오파의 단위·범위와 맞지 않습니다." }
        }
    }
    fun matches(p: JSONObject, state: JSONObject): Boolean = try {
        val values=p.getJSONObject("values");val current=state.getJSONObject("values")
        keys(values).all { current.has(it) && current.getInt(it)==values.getInt(it) }
    } catch(_:Exception) { false }
}

class TuningApi(private val exchange:(String,String,JSONObject?)->JSONObject) {
    fun state():JSONObject=exchange("GET","/api/tuning_profiles",null).also {
        require(it.optBoolean("ok") && it.opt("schema")==1 && it.optString("family")=="LX3") { "오파 프로필 지원 업데이트가 필요합니다." }
        require(it.getJSONObject("context").getString("serial").isNotBlank())
    }
    fun preview(profile:JSONObject):JSONObject {
        TuningProfiles.parse(profile.toString())
        return exchange("POST","/api/tuning_profiles/preview",JSONObject().put("profile",profile)).also {
            require(it.optBoolean("ok") && it.optString("revision").matches(Regex("[a-f0-9]{64}"))) { "변경값 미리보기 실패" }
        }
    }
    /** Exactly one POST. An uncertain response is resolved by a GET, never a repeat write. */
    fun apply(profile:JSONObject,preview:JSONObject,id:String):JSONObject {
        require(id.matches(Regex("[a-f0-9]{32}")))
        val body=JSONObject().put("profile",profile).put("revision",preview.getString("revision"))
            .put("serial",preview.getJSONObject("context").getString("serial")).put("request_id",id)
        return exchange("POST","/api/tuning_profiles/apply",body).also { require(it.optBoolean("ok")) { it.optString("error","적용 미확인") } }
    }
    fun recover(id:String):JSONObject=exchange("POST","/api/tuning_profiles/recover",JSONObject().put("request_id",id)).also { require(it.optBoolean("ok")) { it.optString("error","복구 미확인") } }
    companion object {
        fun forDevice(d:Device)=TuningApi {method,path,body ->
            val c=URL("http://${d.ip}:${d.port}$path").openConnection() as HttpURLConnection
            try {
                c.requestMethod=method;c.connectTimeout=4000;c.readTimeout=20000;c.instanceFollowRedirects=false;c.useCaches=false
                if(body!=null) { val bytes=body.toString().toByteArray(Charsets.UTF_8);c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use { it.write(bytes) } }
                val code=c.responseCode
                if(code==404)error("이 오파는 튜닝 프로필 API를 지원하지 않습니다. 오파 업데이트 후 다시 확인하세요.")
                val stream=if(code in 200..299)c.inputStream else c.errorStream
                val r=JSONObject(stream?.use {it.readBytesLimited(131072)}?.toString(Charsets.UTF_8) ?: "{}")
                require(code in 200..299 && r.optBoolean("ok")) { r.optString("error","오파 응답 확인 실패($code)") };r
            } finally {c.disconnect()}
        }
    }
}


/** Do not rely on Android JSONObject silently overwriting duplicate keys. */
object StrictTuningJson {
    fun check(raw:String) {
        val t=JSONTokener(raw)
        fun value(depth:Int) {
            require(depth<=32) {"JSON 깊이 초과"}
            when(val c=t.nextClean()) {
                '{' -> {
                    val names= mutableSetOf<String>();var c1=t.nextClean()
                    if(c1!='}') {t.back();while(true) {
                        require(t.nextClean()=='"') {"JSON 키 형식"};val key=t.nextString('"')
                        require(names.add(key)) {"중복 JSON 키: $key"};require(t.nextClean()==':');value(depth+1)
                        c1=t.nextClean();if(c1=='}')break;require(c1==',')
                    }}
                }
                '[' -> {if(t.nextClean()!=']') {t.back();while(true){value(depth+1);val end=t.nextClean();if(end==']')break;require(end==',')}}}
                '"' -> t.nextString('"')
                else -> {
                    require(c!='\u0000');val b=StringBuilder().append(c)
                    while(true) {val n=t.next();if(n=='\u0000')break;if(n.isWhitespace()||n in ",]}"){t.back();break};b.append(n)}
                    require(b.toString() in listOf("true","false","null") || b.toString().matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) {"JSON 값 형식"}
                }
            }
        }
        value(0);require(t.nextClean()=='\u0000') {"JSON 뒤의 추가 데이터"}
    }
}
