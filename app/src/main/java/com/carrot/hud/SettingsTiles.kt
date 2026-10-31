package com.carrot.hud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TileSetting(val name:String,val title:String,val description:String,val minimum:Int?,val maximum:Int?,val step:Int,val originalGroup:String) {
    val editable:Boolean get()=minimum!=null&&maximum!=null&&minimum<=maximum
    val toggle:Boolean get()=minimum==0&&maximum==1
}
data class TileSection(val id:String,val title:String,val names:List<String>)
data class TileCategory(val id:String,val title:String,val description:String,val sections:List<TileSection>) {
    val count:Int get()=sections.sumOf { it.names.size }
}
data class TileCatalog(val definitions:Map<String,TileSetting>,val categories:List<TileCategory>,val hasParams:Boolean)
data class TileWriteResult(val confirmed:Boolean,val current:Int?,val message:String)

/** Presentation metadata comes from the connected firmware, never a packaged default snapshot. */
object SettingsTiles {
    fun integer(value:Any?):Int? = when(value) {
        is Int -> value
        is Long -> value.takeIf {it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()}?.toInt()
        else -> null
    }
    fun parse(response:JSONObject):TileCatalog {
        require(response.opt("ok")==true) {"설정 목록 응답 확인 실패"}
        val definitions=linkedMapOf<String,TileSetting>()
        val flat=response.getJSONObject("items_by_group")
        for(group in flat.keys()) {
            val list=flat.getJSONArray(group)
            for(i in 0 until list.length()) {
                val p=list.getJSONObject(i);val name=p.getString("name")
                require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))&&!definitions.containsKey(name)) {"중복·잘못된 설정 항목"}
                val integralDefault=integer(p.opt("default"))!=null
                definitions[name]=TileSetting(name,p.optString("title",name),p.optString("descr",""),
                    integer(p.opt("min")).takeIf {integralDefault},integer(p.opt("max")).takeIf {integralDefault},
                    integer(p.opt("unit"))?.takeIf {it>0} ?: 1,group)
            }
        }
        val specs=listOf(
            Triple("development","개발 중 기능","감속·재출발·측방 여유·진동"),
            Triple("following","앞차·가감속","차간거리·가속 곡선·정차 특성"),
            Triple("steering","차선·조향","추종 모드·중앙 보정·차선변경"),
            Triple("operation","시작·버튼·속도","인게이지·버튼 동작·제한속도"),
            Triple("display","화면·HUD","주행 화면·외부 HUD"),
            Triple("vehicle","차량·하드웨어","차종별 CAN·레이더·장치"),
            Triple("system","기록·연결·소리","녹화·네트워크·기본 알림음"),
            Triple("other","기타·구버전 설정","기기가 제공한 원래 그룹"))
        val sections=specs.associate {it.first to mutableListOf<TileSection>()}
        val seen=mutableSetOf<String>()
        val tree=response.optJSONArray("categories")
        if(tree!=null) for(i in 0 until tree.length()) {
            val cat=tree.getJSONObject(i);val catId=cat.optString("id")
            val groups=cat.optJSONArray("groups") ?: continue
            for(j in 0 until groups.length()) {
                val g=groups.getJSONObject(j);val target=when {
                    catId.startsWith("development_features")->"development"
                    catId=="DRIVING"->when(g.optString("id")){"CRUISE"->"following";"STEER"->"steering";else->"operation"}
                    else->mapOf("DISPLAY" to "display","VEHICLE" to "vehicle","SYSTEM" to "system")[catId] ?: "other"
                }
                val leaves=g.optJSONArray("sections") ?: continue
                for(k in 0 until leaves.length()) {
                    val s=leaves.getJSONObject(k);val names=s.optJSONArray("items") ?: continue
                    val accepted=(0 until names.length()).map {names.getString(it)}.filter {it in definitions&&seen.add(it)}
                    if(accepted.isNotEmpty())sections.getValue(target).add(TileSection("$i:$j:$k",s.optString("ko").takeIf {it.isNotBlank()&&it!="null"} ?: g.optString("ko",g.optString("id")),accepted))
                }
            }
        }
        // Old flat menus and future unknown sections remain visible, never dropped.
        definitions.values.filter {it.name !in seen}.groupBy {it.originalGroup}.forEach { (g,items)->
            val target=if(g=="개발 중 기능")"development" else "other"
            sections.getValue(target).add(TileSection("fallback:$g",g,items.map {it.name}))
        }
        val categories=specs.map {TileCategory(it.first,it.second,it.third,sections.getValue(it.first))}.filter {it.count>0}
        require(categories.flatMap {it.sections.flatMap {s->s.names}}.toSet()==definitions.keys)
        return TileCatalog(definitions,categories,response.opt("has_params")==true)
    }
    fun condition(name:String,values:Map<String,Int>):String {
        fun off(key:String)=values[key]==0
        if(off(name)&&(name.startsWith("Approach")&&name!="ApproachComfortEnabled"||name in setOf("YellowLineBiasCm","NearbyVehicleBiasCm","DriverPositionHold","ManualSteerWithBlinker")||name.startsWith("C3xLiteHaptic")&&name!="C3xLiteHapticPattern"))return "대기 · 이 보완/이벤트 꺼짐 또는 강도0"
        if(name=="LaunchFollowStrength")return if(values[name]==0)"추가 출발 보완 꺼짐" else "독립 · 정지 후25초 · 20→40km/h 보완 종료"
        if(name.startsWith("Approach")&&name!="ApproachComfortEnabled") {
            if(!values.containsKey("ApproachComfortEnabled"))return "상위 설정 확인 필요"
            if(off("ApproachComfortEnabled"))return "대기 · 앞차 접근·정차 편안함 꺼짐"
            if(name=="ApproachFlexibleGapMin") {
                if(!values.containsKey("ApproachFlexibleGap"))return "간격 유연화 설정 확인 필요"
                if(!values.containsKey("ApproachFlexibleGap"))return "간격 유연화 설정 확인 필요"
                if(off("ApproachFlexibleGap"))return "대기 · 간격 유연화 꺼짐"
            }
            return "상위 설정 켜짐 · 앞차·속도·제어 조건 별도"
        }
        if(name.startsWith("C3xLiteHaptic")&&name!="C3xLiteHapticNotifications")return when(values["C3xLiteHapticNotifications"]){0->"대기 · 일반 핸들 진동 꺼짐";1->"진동 상위 켜짐 · 이벤트·제어 조건 별도";else->"진동 상위 설정 확인 필요"}
        if(name in setOf("YellowLineBiasCm","NearbyVehicleBiasCm","DriverPositionHold")) {
            if(!values.containsKey("UseLaneLineSpeed"))return "차로 모드 설정 확인 필요"
            if(off("UseLaneLineSpeed"))return "모델 기본 모드 · 실제 이동 대기"
            if(name!="DriverPositionHold"&&values["LateralMarginShadow"]==1)return "기록 전용 · 실제 이동 안 함"
            return "조건부 · 실제 차로 모드·차선·폭·점유 조건 필요"
        }
        if(name=="ManualSteerWithBlinker")return when {
            !values.containsKey("LaneChangeNeedTorque")->"자동 차선변경 설정 확인 필요"
            values.getValue("LaneChangeNeedTorque")>=0->"대기 · 자동차선변경 OFF(-1) 필요"
            else->"자동변경 OFF · 깜빡이·조향력·제어 조건 별도"
        }
        if(name.startsWith("LateralTorque")&&name!="LateralTorqueCustom")return if(off("LateralTorqueCustom"))"대기 · 커스텀 토크 꺼짐" else "토크 제어기 경로·상위 설정 확인 필요"
        return "설정 조회값 · 실제 주행 적용 여부와 별개"
    }
    fun label(p:TileSetting)=when {
        p.name.matches(Regex("TFollowGap[1-4]"))->"차간거리 ${p.name.last()}단계 시간"
        p.name.matches(Regex("CruiseMaxVals[0-6]"))->"${listOf(0,10,40,60,80,110,140)[p.name.last()-'0']}km/h 가속 상한"
        else->p.title.trimStart('_','↳',' ').replace(Regex("\\([^)]*\\)\\s*$"),"").trim()
    }
    fun value(p:TileSetting,n:Int?):String=when {
        n==null->"조회 미확인"
        p.name.matches(Regex("TFollowGap[1-4]"))->"%.2f초".format(java.util.Locale.ROOT,n/100.0)
        p.name.matches(Regex("CruiseMaxVals[0-6]"))->"%.2fm/s²".format(java.util.Locale.ROOT,n/100.0)
        p.name=="StopDistanceCarrot"->"%.2fm".format(java.util.Locale.ROOT,n/100.0)
        p.name=="MyDrivingMode"->mapOf(1 to "연비",2 to "완만(안전)",3 to "일반",4 to "고속")[n] ?: n.toString()
        p.toggle->if(n==1)"켜짐" else if(n==0)"꺼짐" else n.toString()
        else->n.toString()
    }
}

/** One existing server write, read back, never clamp or automatically retry. */
class SettingsTilesApi(private val exchange:(String,String,JSONObject?)->JSONObject) {
    fun catalog()=SettingsTiles.parse(exchange("GET","/api/settings",null))
    fun values(names:List<String>):Map<String,Int> {
        require(names.all {it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))})
        val out=linkedMapOf<String,Int>()
        for(chunk in names.distinct().chunked(32)) {
            val r=exchange("GET","/api/params_bulk?names="+chunk.joinToString(","),null)
            require(r.opt("ok")==true) {"기기 설정 조회 실패"}
            val v=r.getJSONObject("values")
            for(n in chunk)SettingsTiles.integer(v.opt(n))?.let {out[n]=it}
        }
        return out
    }
    fun write(p:TileSetting,expected:Int,desired:Int,guard:()->Unit):TileWriteResult {
        require(p.editable&&desired in p.minimum!!..p.maximum!!) {"원래 단위·범위를 확인하세요."}
        guard()
        val before=values(listOf(p.name))[p.name] ?: error("현재값 조회 실패 · 변경하지 않았습니다.")
        require(before==expected) {"다른 곳에서 값이 바뀌었습니다. 새로 읽은 뒤 확인하세요."}
        guard()
        var acknowledged=false
        var problem=""
        try {
            val r=exchange("POST","/api/param_set",JSONObject().put("name",p.name).put("value",desired))
            acknowledged=r.opt("ok")==true&&r.opt("has_params")==true&&r.optString("name")==p.name&&SettingsTiles.integer(r.opt("value"))==desired
            if(!acknowledged)problem=r.optString("error","저장 응답 불일치")
        }catch(e:Exception){problem=e.message ?: "저장 응답 미확인"}
        val after=try {values(listOf(p.name))[p.name]}catch(_:Exception){null}
        return if(acknowledged&&after==desired)TileWriteResult(true,after,"기기 저장값 재확인 완료 · 실제 주행 효과는 별도")
        else TileWriteResult(false,after,"완료 미확인 · 자동 재시도 없음: $problem · 현재값 ${after ?: "조회 실패"}")
    }
    companion object {
        fun forDevice(d:Device)=SettingsTilesApi {method,path,body->
            val c=URL("http://${d.ip}:${d.port}$path").openConnection() as HttpURLConnection
            try {
                c.requestMethod=method;c.connectTimeout=2500;c.readTimeout=8000;c.instanceFollowRedirects=false;c.useCaches=false
                if(body!=null){val b=body.toString().toByteArray(Charsets.UTF_8);c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.size);c.outputStream.use {it.write(b)}}
                val code=c.responseCode;val stream=if(code in 200..299)c.inputStream else c.errorStream
                val r=JSONObject(stream?.use {it.readBytesLimited(2*1024*1024)}?.toString(Charsets.UTF_8) ?: "{}")
                require(code in 200..299&&r.opt("ok")==true) {r.optString("error","설정 요청 실패($code) · 기존 설정 화면을 이용할 수 있습니다.")};r
            }finally{c.disconnect()}
        }
    }
}
