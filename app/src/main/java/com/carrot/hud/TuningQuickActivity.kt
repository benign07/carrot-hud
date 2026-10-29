package com.carrot.hud

import android.os.Bundle
import android.content.Intent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Local chooser only. Nothing is sent until a user explicitly taps a profile. */
class TuningQuickActivity:AppCompatActivity() {
    private lateinit var status:TextView
    private lateinit var list:LinearLayout
    private var opened:Device?=null
    private val busy=AtomicBoolean(false)
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12)}
        fun text(s:String)=TextView(this).also{it.text=s;it.setPadding(0,8,0,8);box.addView(it)}
        opened=DeviceStore.getActive(this)
        val expected=intent.getStringExtra("device_id")
        text("프로필 선택 · ${opened?.name ?: "기기 없음"}").textSize=20f
        status=text(TuningWidgetStatus.text(this))
        text("선택하면 추가 확인 없이 적용을 요청합니다. P·속도0·조향/가감속 제어 해제가 필요합니다. 저장 확인과 주행 효과는 별개입니다.").textSize=13f
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};box.addView(list)
        val catalog=TuningProfileStore.load(this)
        if(opened==null||expected!=opened?.id)status.text="대상 기기가 바뀌었습니다. 위젯을 새로고침하고 다시 여세요."
        else for(profile in catalog.profiles){
            val name=profile.getString("name")
            val short=profile.optString("description").substringAfter("[조합 의도] ",profile.optString("description")).substringBefore(". ").take(100)
            list.addView(Button(this).apply{text="$name\n$short";isAllCaps=false;setOnClickListener{startWork(true,profile)}})
        }
        if(catalog.problems.isNotEmpty())text(catalog.problems.joinToString("\n"))
        box.addView(Button(this).apply{text="적용 결과 다시 확인 · 재적용 없음";setOnClickListener{startWork(false,null)}})
        box.addView(Button(this).apply{text="전체 설명·직전 값 복원";setOnClickListener{startActivity(Intent(this@TuningQuickActivity,TuningProfilesActivity::class.java))}})
        box.addView(Button(this).apply{text="닫기";setOnClickListener{finish()}})
        setContentView(ScrollView(this).apply{addView(box)})
    }
    private fun resolveTarget():Device {
        val chosen=opened ?: error("기기 등록 필요")
        require(DeviceStore.getActive(this)?.id==chosen.id){"대상 기기가 바뀌었습니다."}
        val resolved=Net.resolve(applicationContext,chosen) ?: error("오파 연결을 확인하세요.")
        val saved=DeviceStore.getActive(this) ?: error("대상 기기가 없습니다.")
        require(saved.id==chosen.id&&!saved.identityNeedsCheck){"앱에서 대상 기기 식별을 확인하세요."}
        val bound=resolved.copy(dongle=saved.dongle,identityNeedsCheck=saved.identityNeedsCheck)
        checkTarget(bound);return bound
    }
    private fun checkTarget(d:Device,network:Boolean=true){
        val active=DeviceStore.getActive(this)
        require(active!=null&&active.id==d.id&&active.ip==d.ip&&active.port==d.port&&active.dongle==d.dongle&&!active.identityNeedsCheck){"대상 기기·주소가 바뀌었습니다."}
        require(OpPairingBinding.known(d.dongle)&&(!network||Net.fetchDongle(d.ip,d.port,1200)==d.dongle)){"기기 식별 확인 실패 · 앱에서 연결을 확인하세요."}
    }
    private fun startWork(apply:Boolean,profile:JSONObject?){
        if(!busy.compareAndSet(false,true))return
        if(!TuningOperationGate.enter()){busy.set(false);status.text="다른 프로필 작업 진행 중 · 자동 예약하지 않았습니다.";return}
        for(i in 0 until list.childCount)list.getChildAt(i).isEnabled=false
        status.text=if(apply)"${profile?.optString("name")} · 적용 요청 준비 중…" else "저장 결과 확인 중…"
        val c=applicationContext
        Thread{
            var d:Device?=null
            try{
                val target=resolveTarget();d=target;val api=TuningApi.forDevice(target)
                val pending=TuningWidgetStatus.pending(c,target.id)
                val prior=pending.getString("request_id",null)
                if(prior!=null){
                    val state=api.state();checkTarget(target)
                    val raw=pending.getString("profile_json",null)
                    val stored=try{raw?.let{TuningProfiles.parse(it)}}catch(_:Exception){null}
                    val resolution=TuningQuickApply.pendingResolution(stored,prior,pending.getString("serial",null),state)
                    if(!resolution.first){
                        TuningProfileHealth.observe(c,target,state)
                        if(!apply){show(resolution.second+"\n현재 저장 상태를 읽었습니다. 새 쓰기·재적용은 하지 않았습니다.");return@Thread}
                        error(resolution.second+" · 자동 재적용 없음")
                    }
                    require(pending.edit().clear().commit());TuningProfileHealth.observe(c,target,state)
                    TuningWidgetStatus.set(c,target,stored?.optString("name") ?: "프로필",resolution.second)

                }
                if(!apply){
                    val s=api.state();checkTarget(target);TuningProfileHealth.observe(c,target,s)
                    show("현재 저장 상태 확인 · "+TuningWidgetStatus.text(c));return@Thread
                }
                val selected=TuningProfiles.parse(profile!!.toString());val name=selected.getString("name")
                TuningWidgetStatus.set(c,target,name,"요청 준비 중 · 전송 전")
                val id=UUID.randomUUID().toString().replace("-","")
                val result=TuningQuickApply(api,{checkTarget(target)},{request,p,serial ->
                    TuningWidgetStatus.preservePending(c,target.id)
                    require(pending.edit().putString("request_id",request).putString("device_id",target.id).putString("profile_json",p.toString()).putString("serial",serial).commit()){"요청 기록 저장 실패"}
                    TuningWidgetStatus.set(c,target,name,"적용 요청 중 · 완료 확인 전")
                },{checkTarget(target,false)}).apply(selected,id)
                result.state?.let{TuningProfileHealth.observe(c,target,it)}
                if(result.clearPending&&pending.getString("request_id",null)==id)require(pending.edit().clear().commit())
                TuningWidgetStatus.set(c,target,name,result.message)
                TuningWidgetStatus.refreshReadOnly(c)
                show("$name\n${result.message}")
            }catch(e:Exception){
                d?.let{TuningWidgetStatus.set(c,it,profile?.optString("name") ?: "프로필","처리 확인 필요 · ${e.message}")}
                show("적용·결과 확인 실패: ${e.message}")
            }finally{
                TuningOperationGate.leave();busy.set(false)
                runOnUiThread{if(!isFinishing&&!isDestroyed)for(i in 0 until list.childCount)list.getChildAt(i).isEnabled=true}
            }
        }.start()
    }
    private fun show(s:String){runOnUiThread{if(!isFinishing&&!isDestroyed)status.text=s}}
}
