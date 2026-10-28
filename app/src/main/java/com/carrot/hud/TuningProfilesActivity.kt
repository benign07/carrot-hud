package com.carrot.hud

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TuningProfilesActivity:AppCompatActivity() {
    private lateinit var status:TextView
    private lateinit var details:TextView
    private lateinit var selector:Spinner
    private lateinit var applyButton:Button
    private lateinit var recoverButton:Button
    private var profiles= mutableListOf<JSONObject>()
    private var targetDevice:Device?=null
    private var state:JSONObject?=null
    private var preview:JSONObject?=null
    private var previewProfile:JSONObject?=null
    private var previewDevice:Device?=null
    private var exportRaw:String?=null
    private val busy=AtomicBoolean(false)
    private fun pending()=getSharedPreferences("tuning_pending_v1",MODE_PRIVATE)
    private fun users()=File(filesDir,"tuning-profiles-v1.json")
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) try {
            val p=TuningProfiles.parse(contentResolver.openInputStream(uri)!!.use {it.readBytesLimited(32768)}.toString(Charsets.UTF_8))
            val old=if(users().exists())JSONArray(users().readText()) else JSONArray()
            require(old.length()<40) {"저장 프로필은40개까지입니다."}
            p.put("id","user-"+UUID.randomUUID().toString().replace("-","").take(20));old.put(p)
            // Existing bad store is never overwritten by an import.
            val temp=File(filesDir,"tuning-profiles-v1.tmp");temp.writeText(old.toString());require(temp.renameTo(users()))
            loadProfiles();status.text="프로필 가져오기 완료 · 차량에는 적용하지 않았습니다."
        } catch(e:Exception) {status.text="가져오기 실패 · 기존 프로필 보존: ${e.message}"}
    }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri!=null && exportRaw!=null) try {contentResolver.openOutputStream(uri)!!.use {it.write(exportRaw!!.toByteArray(Charsets.UTF_8))};status.text="HUD·시뮬레이터 공통 프로필 내보내기 완료"}catch(e:Exception){status.text="내보내기 실패: ${e.message}"}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20)}
        fun text(s:String,size:Float=16f)=TextView(this).also {it.text=s;it.textSize=size;it.setPadding(0,12,0,12);box.addView(it)}
        fun button(s:String,action:()->Unit)=Button(this).also {it.text=s;it.setOnClickListener {action()};box.addView(it)}
        text("튜닝 프로필",24f)
        status=text("오파 연결·프로필 규격 확인 전")
        text("A/B 후보 목표는 개선 보장이 아닙니다. 정차·P·조향/가감속 해제 후 변경합니다. 프로필은 종방향 설정만 다루며 조향·DM·카메라 설정은 보존합니다.",14f)
        selector=Spinner(this);box.addView(selector)
        selector.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,position:Int,id:Long){preview=null;previewProfile=null;applyButton.isEnabled=false;details.text=profiles.getOrNull(position)?.optString("description","") ?: ""}
            override fun onNothingSelected(p:android.widget.AdapterView<*>?){}
        }
        button("연결·업데이트 후 호환성 확인") {refresh()}
        button("선택 프로필 변경값 확인") {selected()?.let {showPreview(it)}}
        applyButton=button("확인한 프로필 적용") {confirmApply()}.apply {isEnabled=false}
        button("선택 프로필 JSON 내보내기") {selected()?.let {exportRaw=it.toString(2);exportFile.launch("carrot-tuning-${it.getString("id")}.json")}}
        button("HUD·시뮬레이터 프로필 가져오기") {importFile.launch(arrayOf("application/json","text/plain"))}
        button("현재 저장값 프로필 내보내기") {runDevice {api,_ ->val snapshot=api.state();val p=TuningProfiles.fromValues("현재 저장 설정",snapshot.getJSONObject("values")).put("behavior_revision",snapshot.optString("behavior_revision"));runOnUiThread {exportRaw=p.toString(2);exportFile.launch("carrot-tuning-current.json")}}}
        button("직전 프로필 적용 전 값 확인·복원") {val s=state?.optJSONObject("receipt");if(s?.optString("phase")!="complete")status.text="완료된 적용 기록이 없습니다." else showPreview(TuningProfiles.fromValues("직전 프로필 적용 전",s.getJSONObject("before")))}
        recoverButton=button("미완료 프로필 적용 복구") {runDevice {api,_ ->val s=api.state();val r=s.optJSONObject("receipt") ?: error("미완료 기록 없음");api.recover(r.getString("request_id"));runOnUiThread {pending().edit().clear().commit()};loadState(api.state())}}.apply {isEnabled=false}
        details=text("프로필 선택 후 변경값을 확인하세요.",14f)
        setContentView(ScrollView(this).apply {addView(box)})
        loadProfiles();refresh()
    }
    private fun selected()=profiles.getOrNull(selector.selectedItemPosition)
    private fun loadProfiles() {
        try {
            val built=JSONArray(JSONObject(assets.open("tuning_profiles_v1.json").bufferedReader().use {it.readText()}).getJSONArray("profiles").toString())
            val list= mutableListOf<JSONObject>()
            for(i in 0 until built.length())list.add(TuningProfiles.parse(built.getJSONObject(i).toString()))
            if(users().exists()){val a=JSONArray(users().readText());for(i in 0 until a.length())list.add(TuningProfiles.parse(a.getJSONObject(i).toString()))}
            profiles=list;selector.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,list.map {it.getString("name")})
        } catch(e:Exception){status.text="프로필 파일 오류 · 원본 보존: ${e.message}"}
    }
    private fun runDevice(work:(TuningApi,Device)->Unit) {
        if(!busy.compareAndSet(false,true))return
        applyButton.isEnabled=false
        val original=DeviceStore.getActive(this)
        Thread {
            try {
                require(original!=null) {"기기 등록이 필요합니다."}
                val d=Net.resolve(applicationContext,original) ?: error("오파 연결을 확인하세요.")
                require(DeviceStore.getActive(this)?.id==original.id && !d.identityNeedsCheck) {"대상 기기 식별을 확인하세요."}
                targetDevice=d
                work(TuningApi.forDevice(d),d)
            }catch(e:Exception){runOnUiThread {status.text="완료 미확인 · 자동 재적용 없음: ${e.message}"}}
            finally{busy.set(false)}
        }.start()
    }
    private fun loadState(s:JSONObject) {
      val target=targetDevice ?: return
      if(DeviceStore.getActive(this)?.id!=target.id)return
      TuningProfileHealth.observe(applicationContext,target,s)
      runOnUiThread {
        if(DeviceStore.getActive(this)?.id!=target.id)return@runOnUiThread
        state=s;recoverButton.isEnabled=s.optBoolean("recovery_required") && s.optJSONObject("receipt")?.optString("phase")!="corrupt"
        val p=pending();val id=p.getString("request_id",null);val r=s.optJSONObject("receipt")
        val confirmation=if(id!=null && r?.optString("request_id")==id && r.optString("phase")=="complete") {p.edit().clear().commit();"직전 요청 저장 완료 확인"} else if(id!=null) "이전 요청 완료 미확인 · 기록을 확인하세요" else "연결·프로필 규격1 확인"
        status.text="$confirmation · 기기 ${s.getJSONObject("context").getString("serial")}\n${s.optString("note")}"
        if(r?.optString("phase")=="complete")status.append("\n마지막 적용: ${r.optString("profile_name")} · "+if(s.optBoolean("current_matches_last")) "현재 저장값 일치" else "이후 개별 설정 변경 감지")
        if(s.optBoolean("recovery_required"))status.append("\n이전 미완료 적용 복구가 필요합니다. 새 적용은 차단됩니다.")
    }}
    private fun refresh()=runDevice {api,_ ->loadState(api.state())}
    private fun showPreview(p:JSONObject)=runDevice {api,d ->
        val s=api.state();TuningProfiles.validateFor(p,s);require(!s.optBoolean("recovery_required")) {"이전 적용 복구 필요"}
        val v=api.preview(p);runOnUiThread {
            state=s;preview=v;previewProfile=p;previewDevice=d;applyButton.isEnabled=true
            val changes=v.getJSONArray("changes")
            details.text=p.getString("name")+"\n"+p.optString("description","")+"\n"+v.optString("note","")+"\n\n"+(0 until changes.length()).joinToString("\n") {i ->val c=changes.getJSONObject(i);"${c.getString("key")}: ${c.get("before")} → ${c.get("after")}"}
            status.text="${changes.length()}개 변경 예정 · 대상 ${v.getJSONObject("context").getString("serial")} · 아직 적용 전"
        }
    }
    private fun confirmApply() {
        val p=previewProfile ?: return;val v=preview ?: return;val d=previewDevice ?: return
        AlertDialog.Builder(this).setTitle("${p.getString("name")} 적용").setMessage("P·속도0·제어 해제 상태에서 표시된 종방향 설정을 변경합니다. 저장 확인과 실제 주행 효과는 별개입니다.")
            .setNegativeButton("취소",null).setPositiveButton("적용") {_,_ ->runDevice {api,current ->
                require(current.id==d.id) {"대상 기기가 바뀌었습니다. 다시 미리보기하세요."}
                val id=UUID.randomUUID().toString().replace("-","")
                require(pending().edit().putString("request_id",id).putString("device_id",d.id).commit())
                try {api.apply(p,v,id)}finally {try{loadState(api.state())}catch(_:Exception){}}
                val s=api.state();require(TuningProfiles.matches(p,s)) {"저장값 불일치 · 자동 재적용 없음"}
                runOnUiThread {preview=null;previewProfile=null;applyButton.isEnabled=false;status.text="요청한 설정의 저장값 일치 확인 · 실제 주행 효과는 진단에서 확인합니다."}
            }}.show()
    }
}
