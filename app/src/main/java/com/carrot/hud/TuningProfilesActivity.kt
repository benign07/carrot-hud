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
    private lateinit var archiveButton:Button
    private var profiles= mutableListOf<JSONObject>()
    private var targetDevice:Device?=null
    private var state:JSONObject?=null
    private var preview:JSONObject?=null
    private var previewProfile:JSONObject?=null
    private var previewDevice:Device?=null
    private var exportRaw:String?=null
    private val busy=AtomicBoolean(false)
    private fun pending()=getSharedPreferences("tuning_pending_v1_"+(targetDevice?.id ?: DeviceStore.getActive(this)?.id ?: "none"),MODE_PRIVATE)
    private fun users()=File(filesDir,"tuning-profiles-v1.json") // Read-only legacy prototype store
    private fun userDir()=File(filesDir,"tuning-profiles-v1")
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) try {
            require(!busy.get()) {"통신 작업 완료 후 다시 가져오세요."}
            val p=TuningProfiles.parse(contentResolver.openInputStream(uri)!!.use {it.readBytesLimited(32768)}.toString(Charsets.UTF_8))
            val dir=userDir();require(dir.isDirectory||dir.mkdirs())
            require((dir.listFiles()?.count {it.extension=="json"} ?: 0)<40) {"가져온 프로필은40개까지입니다."}
            val id=UUID.randomUUID().toString().replace("-","");p.put("id","user-"+id)
            val temp=File(dir,id+".tmp");val dest=File(dir,id+".json")
            java.io.FileOutputStream(temp).use {it.write(p.toString(2).toByteArray(Charsets.UTF_8));it.fd.sync()};require(temp.renameTo(dest))
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
        text("다섯 통합 조합은 접근 감속·부드러운 정지·조건부 간격 유연화·출발 후 추종을 공통 사용하고 강도·시간거리·가속 상한을 다르게 조합합니다. 개선 보장이 아니며 P·속도0·제어 해제 후 변경합니다. 조향·DM·카메라는 보존합니다.",14f)
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
        button("현재 저장값 프로필 내보내기") {runDevice {api,_ ->val snapshot=api.state();require(!snapshot.optBoolean("in_progress")&&!snapshot.optBoolean("recovery_required")) {"미완료 적용부터 확인하세요."};val p=TuningProfiles.fromValues("현재 저장 설정",snapshot.getJSONObject("values")).put("behavior_revision",snapshot.optString("behavior_revision"));runOnUiThread {exportRaw=p.toString(2);exportFile.launch("carrot-tuning-current.json")}}}
        button("직전 프로필 적용 전 값 확인·복원") {val s=state?.optJSONObject("receipt");if(s?.optString("phase")!="complete")status.text="완료된 적용 기록이 없습니다." else showPreview(TuningProfiles.fromValues("직전 프로필 적용 전",s.getJSONObject("before")))}
        recoverButton=button("미완료 프로필 적용 복구") {confirmRecovery()}.apply {isEnabled=false}
        archiveButton=button("복구 기록 문제 · 원본 보존 후 다시 확인") {confirmArchive()}.apply {isEnabled=false}
        text("복원은 직전1건입니다. 기록 보존은 Params 복원이 아니며, 현재 설정을 유지한 채 이전 결과를 미확인으로 남깁니다.",14f)
        details=text("프로필 선택 후 변경값을 확인하세요.",14f)
        setContentView(ScrollView(this).apply {addView(box)})
        loadProfiles();refresh()
    }
    private fun selected()=profiles.getOrNull(selector.selectedItemPosition)
    private fun loadProfiles() {
        val list= mutableListOf<JSONObject>();val problems= mutableListOf<String>()
        try {
            val built=JSONObject(assets.open("tuning_profiles_v1.json").bufferedReader().use {it.readText()}).getJSONArray("profiles")
            for(i in 0 until built.length())list.add(TuningProfiles.parse(built.getJSONObject(i).toString()))
        } catch(e:Exception){problems.add("내장 프로필: ${e.message}")}
        if(users().exists())try {
            require(users().length()<=1_310_720)
            val a=JSONArray(users().readText());for(i in 0 until a.length())try{list.add(TuningProfiles.parse(a.getJSONObject(i).toString()))}catch(_:Exception){problems.add("기존 사용자 프로필${i+1}: 호환 안 됨·원본 보존")}
        } catch(_:Exception){problems.add("기존 사용자 저장소: 손상/미지원·원본 보존")}
        for(f in userDir().listFiles()?.filter {it.extension=="json"}?.sortedBy {it.name} ?: emptyList())try {
            require(f.length()<=32768);list.add(TuningProfiles.parse(f.readText()))
        } catch(_:Exception){problems.add("${f.name}: 호환 안 됨·원본 보존")}
        profiles=list;selector.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,list.map {it.getString("name")})
        if(problems.isNotEmpty())status.text=problems.joinToString("\n")
    }
    private fun runDevice(work:(TuningApi,Device)->Unit) {
        if(!busy.compareAndSet(false,true))return
        applyButton.isEnabled=false
        selector.isEnabled=false
        val original=DeviceStore.getActive(this)
        Thread {
            var apiForCall:TuningApi?=null
            try {
                require(original!=null) {"기기 등록이 필요합니다."}
                val d=Net.resolve(applicationContext,original) ?: error("오파 연결을 확인하세요.")
                require(DeviceStore.getActive(this)?.id==original.id && !d.identityNeedsCheck) {"대상 기기 식별을 확인하세요."}
                targetDevice=d
                val api=TuningApi.forDevice(d)
                apiForCall=api
                work(api,d)
            }catch(e:Exception){
                try {val receipt=apiForCall?.receipt();val d=targetDevice;if(receipt!=null&&d!=null){TuningProfileHealth.observe(applicationContext,d,receipt);runOnUiThread {if(receipt.optBoolean("recovery_required")){state=receipt;recoverButton.isEnabled=receipt.optJSONObject("receipt")?.optString("phase") in listOf("applying","recovery_required");archiveButton.isEnabled=receipt.optString("journal_sha256").matches(Regex("[a-f0-9]{64}"))}}}}catch(_:Exception){}
                runOnUiThread {status.text="완료 미확인 · 자동 재적용 없음: ${e.message}"}
            }
            finally{busy.set(false);runOnUiThread {selector.isEnabled=true}}
        }.start()
    }
    private fun loadState(s:JSONObject) {
      val target=targetDevice ?: return
      if(DeviceStore.getActive(this)?.id!=target.id)return
      TuningProfileHealth.observe(applicationContext,target,s)
      runOnUiThread {
        if(DeviceStore.getActive(this)?.id!=target.id)return@runOnUiThread
        state=s;recoverButton.isEnabled=s.optBoolean("recovery_required") && s.optJSONObject("receipt")?.optString("phase") in listOf("applying","recovery_required")
        archiveButton.isEnabled=s.optBoolean("recovery_required") && s.optString("journal_sha256").matches(Regex("[a-f0-9]{64}"))
        val p=pending();val id=p.getString("request_id",null);val r=s.optJSONObject("receipt")
        val confirmation=if(id!=null && r?.optString("request_id")==id && r.optString("phase")=="complete") {p.edit().clear().commit();"직전 요청 저장 완료 확인"} else if(id!=null && r?.optString("request_id")==id && r.optString("phase")=="rolled_back") {p.edit().clear().commit();"이전 적용 실패 · 적용 전 값 복원 확인"} else if(id!=null) "이전 요청 완료 미확인 · 기록을 확인하세요" else "연결·프로필 규격1 확인"
        status.text="$confirmation · 기기 ${s.getJSONObject("context").getString("serial")}\n${s.optString("note")}"
        if(r?.optString("phase")=="complete")status.append("\n마지막 적용: ${r.optString("profile_name")} · "+if(s.optBoolean("current_matches_last")) "현재 저장값 일치" else "이후 개별 설정 변경 감지")
        if(s.optBoolean("unverified_previous"))status.append("\n보존한 이전 결과 미확인 · 전체 프로필 저장 검증 전까지 표시 유지")
        if(s.optBoolean("in_progress"))status.append("\n적용 진행 중 · 완료 상태를 다시 확인합니다.")
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
    private fun confirmRecovery()=runDevice {api,d ->
        val s=api.receipt();require(!s.optBoolean("in_progress")) {"진행 중인 적용을 기다려 주세요."}
        val r=s.optJSONObject("receipt") ?: error("미완료 기록 없음")
        val before=r.getJSONObject("before");val summary=before.keys().asSequence().joinToString("\n") {"$it → ${before.get(it)}"}
        runOnUiThread {AlertDialog.Builder(this).setTitle("미완료 적용 전 값 복원").setMessage("기기 ${s.getJSONObject("context").getString("serial")}\n우리 적용값으로 확인되는 항목만 복원합니다.\n$summary")
            .setNegativeButton("취소",null).setPositiveButton("P 정차 후 복원") {_,_->runDevice {api2,current ->require(current.id==d.id);api2.recover(r.getString("request_id"));pending().edit().clear().commit();loadState(api2.state())}}.show()}
    }
    private fun confirmArchive()=runDevice {api,d ->
        val receipt=api.receipt();require(!receipt.optBoolean("in_progress")) {"적용 진행 중입니다."}
        require(receipt.optBoolean("recovery_required")) {"보존할 오류 기록이 없습니다."}
        val hash=receipt.getString("journal_sha256");val serial=receipt.getJSONObject("context").getString("serial")
        val current=try {api.state().getJSONObject("values").let {v->v.keys().asSequence().joinToString("\n") {"$it = ${v.get(it)}"}}}catch(_:Exception){"현재 값 전체를 읽지 못했습니다. 설정 점검이 필요합니다."}
        runOnUiThread {AlertDialog.Builder(this).setTitle("기록 원본 보존 · 설정값 유지").setMessage("기기 $serial\n이전 적용 결과는 확인할 수 없습니다. Params를 바꾸지 않고 오류 기록만 별도 파일로 보존합니다.\n$current\n보존 후 반드시 새 미리보기로 현재 설정을 확인하세요.")
            .setNegativeButton("취소",null).setPositiveButton("현재 값 유지·기록 보존") {_,_->runDevice {api2,target ->require(target.id==d.id);api2.archive(hash,serial);TuningProfileHealth.observe(applicationContext,target,api2.receipt());pending().edit().clear().commit();loadState(api2.state())}}.show()}
    }
    private fun confirmApply() {
        val p=previewProfile ?: return;val v=preview ?: return;val d=previewDevice ?: return
        AlertDialog.Builder(this).setTitle("${p.getString("name")} 적용").setMessage("P·속도0·제어 해제 상태에서 표시된 종방향 설정을 변경합니다. 저장 확인과 실제 주행 효과는 별개입니다.")
            .setNegativeButton("취소",null).setPositiveButton("적용") {_,_ ->runDevice {api,current ->
                require(current.id==d.id) {"대상 기기가 바뀌었습니다. 다시 미리보기하세요."}
                val id=UUID.randomUUID().toString().replace("-","")
                require(pending().edit().putString("request_id",id).putString("device_id",d.id).commit())
                try {val result=api.apply(p,v,id);if(result.optString("phase")=="unchanged")pending().edit().clear().commit()}finally {try{loadState(api.state())}catch(_:Exception){}}
                val s=api.state();require(TuningProfiles.matches(p,s)) {"저장값 불일치 · 자동 재적용 없음"}
                runOnUiThread {preview=null;previewProfile=null;applyButton.isEnabled=false;status.text="요청한 설정의 저장값 일치 확인 · 실제 주행 효과는 진단에서 확인합니다."}
            }}.show()
    }
}
