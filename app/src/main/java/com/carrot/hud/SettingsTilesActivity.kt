package com.carrot.hud

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

/** Native, device-backed tile settings. Does not change any values when opened. */
class SettingsTilesActivity:AppCompatActivity() {
    private var catalog:TileCatalog?=null
    private var values:Map<String,Int> = emptyMap()
    private var target:Device?=null
    private var category="home"
    private var section:String?=null
    private var query=""
    private var message="기기 설정 조회 전 · 프로필5개는 바로 열 수 있습니다."
    private val busy=AtomicBoolean(false)
    @Volatile private var started=false
    @Volatile private var generation=0
    private lateinit var root:LinearLayout
    private val ink=Color.rgb(230,239,233)
    private val muted=Color.rgb(156,177,163)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int)=GradientDrawable().apply {setColor(color);cornerRadius=dp(12).toFloat();setStroke(dp(1),Color.rgb(49,67,55))}
    private fun column()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
    private fun label(s:String,size:Float=14f)=TextView(this).apply {text=s;textSize=size;setTextColor(ink);setPadding(0,dp(5),0,dp(5))}
    private fun button(s:String,action:()->Unit)=Button(this).apply {text=s;textSize=13f;isAllCaps=false;setTextColor(ink);setOnClickListener {action()}}
    override fun onCreate(state:Bundle?) {super.onCreate(state);category=state?.getString("tile_category") ?: "home";section=state?.getString("tile_section");render()}
    override fun onStart(){super.onStart();started=true;DeviceStore.getActive(this)?.let {TuningProfileHealth.refresh(applicationContext,it)};refresh()}
    override fun onStop(){started=false;generation++;super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putString("tile_category",category);out.putString("tile_section",section);super.onSaveInstanceState(out)}
    override fun onConfigurationChanged(c:Configuration){super.onConfigurationChanged(c);render()}
    private fun current(token:Int)=started&&!isFinishing&&!isDestroyed&&generation==token
    private fun bound(original:Device,d:Device) {
        val registered=DeviceStore.getById(this,original.id)
        require(DeviceStore.getActive(this)?.id==original.id&&registered!=null&&!registered.identityNeedsCheck&&!d.identityNeedsCheck) {"대상 기기 확인이 필요합니다."}
        require(registered.dongle.isNotBlank()&&registered.dongle!="UnregisteredDevice") {"기기 식별을 확인한 뒤 다시 연결하세요."}
        require(d.dongle==registered.dongle) {"조회한 기기 식별이 바뀌었습니다. 다시 읽어주세요."}
        require(Net.fetchDongle(d.ip,d.port,1500)==registered.dongle) {"기기 식별 불일치 · 설정 변경 없음"}
    }
    private fun refresh() {
        if(!busy.compareAndSet(false,true))return
        val token=++generation
        val original=DeviceStore.getActive(this)
        message="기기 설정 읽는 중…";render()
        Thread {
            try {
                require(original!=null) {"기기를 먼저 등록하세요."}
                val resolved=Net.resolve(applicationContext,original) ?: error("오파 연결을 확인하세요. 기존 설정과 프로필은 보존됩니다.")
                val d=resolved.copy(dongle=DeviceStore.getById(this,original.id)?.dongle ?: resolved.dongle)
                bound(original,d)
                val api=SettingsTilesApi.forDevice(d);val c=api.catalog();val v=api.values(c.definitions.keys.toList())
                runOnUiThread {if(current(token)&&DeviceStore.getActive(this)?.id==original.id){catalog=c;values=v;target=d;message="${d.name} · ${v.size}/${c.definitions.size}개 기기 조회값 · 미설정 항목은 서버 기본값 · 실제 작동 여부와 별개"}}
            }catch(e:Exception){runOnUiThread {if(current(token)){catalog=null;values=emptyMap();target=null;message="조회 미완료: ${e.message}"}}}
            finally{busy.set(false);runOnUiThread {if(current(token))render()}}
        }.start()
    }
    private fun navigate(id:String,s:String?=null){category=id;section=s;query="";render()}
    private fun profiles(id:String?=null) {startActivity(Intent(this,TuningProfilesActivity::class.java).apply {if(id!=null)putExtra(TuningProfilesActivity.EXTRA_PROFILE_ID,id)})}
    private fun legacy() {startActivity(Intent(this,MainActivity::class.java).putExtra(MainActivity.EXTRA_LEGACY_SETTINGS,true));finish()}
    private fun rowButtons(box:LinearLayout,items:List<Pair<String,()->Unit>>) {
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        items.forEach {(s,f)->row.addView(button(s,f))}
        box.addView(HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;addView(row)})
    }
    private fun grid(box:LinearLayout,items:List<View>,minimum:Int) {
        val columns=((resources.configuration.screenWidthDp-32)/minimum).coerceIn(1,4)
        val grid=GridLayout(this).apply {columnCount=columns;alignmentMode=GridLayout.ALIGN_BOUNDS}
        items.forEachIndexed {i,view->grid.addView(view,GridLayout.LayoutParams().apply {width=0;height=GridLayout.LayoutParams.WRAP_CONTENT;columnSpec=GridLayout.spec(i%columns,1f);rowSpec=GridLayout.spec(i/columns);setMargins(dp(4),dp(4),dp(4),dp(4))})}
        box.addView(grid,LinearLayout.LayoutParams(-1,-2))
    }
    private fun tile(title:String,description:String,count:String,action:()->Unit):View=column().apply {
        background=shape(Color.rgb(27,39,31));setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(135)
        addView(label(title,18f).apply {setTypeface(null,Typeface.BOLD)})
        addView(label(description,12f).apply {setTextColor(muted)})
        addView(label(count,12f).apply {setTextColor(Color.rgb(188,219,198))})
        isClickable=true;isFocusable=true;setOnClickListener {action()}
    }
    private fun render() {
        root=column().apply {setPadding(dp(12),dp(10),dp(12),dp(24));setBackgroundColor(Color.rgb(16,23,18))}
        setContentView(ScrollView(this).apply {addView(root)})
        rowButtons(root,listOf("‹ HUD" to {finish()},"전체 설정" to {navigate("home")},"프로필5개" to {navigate("profiles")},"새로 읽기" to {refresh()},"기존 설정 화면" to {legacy()}))
        root.addView(label(if(category=="home")"설정" else if(category=="profiles")"튜닝 프로필" else catalog?.categories?.find {it.id==category}?.title ?: "설정",24f))
        root.addView(label(message,12f).apply {setTextColor(muted)})
        val warning=TuningProfileHealth.text(this)
        if(warning.isNotBlank())root.addView(label(warning,14f).apply {setTextColor(Color.rgb(255,203,124))})
        val search=EditText(this).apply {hint="설정 이름·설명·파라미터 검색";setTextColor(ink);setHintTextColor(muted);textSize=14f;isSingleLine=true;setText(query)}
        root.addView(search)
        val body=column();root.addView(body)
        fun content(){body.removeAllViews();if(query.isNotBlank()){
            val found=catalog?.definitions?.values?.filter {("${it.name} ${it.title} ${it.description}").contains(query,true)} ?: emptyList()
            body.addView(label("검색 ${found.size}개 · 접힌 고급 계수 포함",13f));grid(body,found.map {settingCard(it)},260)
        }else if(category=="home")home(body) else if(category=="profiles")profileCards(body) else categoryCards(body)}
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString();content()};override fun afterTextChanged(s:Editable?){} })
        content()
    }
    private fun home(box:LinearLayout) {
        val c=catalog
        val cards=mutableListOf<View>()
        c?.categories?.find {it.id=="development"}?.let {cards.add(tile(it.title,it.description,"${it.count}개 옵션"){navigate(it.id)})}
        cards.add(tile("튜닝 프로필5개","일상 균형·매끄러운 전환·거리 여유·간격 회복·기존 거리·순항","27개 종방향 값 · 변경값 확인 후 적용"){navigate("profiles")})
        c?.categories?.filter {it.id!="development"}?.forEach {cards.add(tile(it.title,it.description,"${it.count}개 옵션"){navigate(it.id)})}
        grid(box,cards,170)
    }
    private fun profileCards(box:LinearLayout) {
        val loaded=TuningProfileStore.load(this)
        if(loaded.problems.isNotEmpty())box.addView(label(loaded.problems.joinToString("\n"),12f))
        box.addView(label("선택하면 기존 프로필 적용 화면으로 이동합니다. 변경값 미리보기·정차 확인·저장값 재확인을 유지하며 자동 적용하지 않습니다.",13f))
        grid(box,loaded.profiles.map {p->column().apply {
            background=shape(Color.rgb(28,43,33));setPadding(dp(16),dp(12),dp(16),dp(12))
            addView(label(p.getString("name"),18f))
            val description=p.optString("description");addView(label(description.split("\n\n").getOrElse(1){description},13f))
            val v=p.getJSONObject("values")
            addView(label("감속 여유 ${v.optInt("ApproachBrakeMargin")} · 정지 부드러움 ${v.optInt("ApproachStopSmooth")}\n정체 차분함 ${v.optInt("ApproachTrafficCalm")} · 출발 추종 ${v.optInt("LaunchFollowStrength")}",12f))
            addView(button("변경값 확인·적용 화면"){profiles(p.getString("id"))})
        }},260)
    }
    private fun categoryCards(box:LinearLayout) {
        val c=catalog?.categories?.find {it.id==category} ?: return
        if(c.sections.none {it.id==section})section=c.sections.firstOrNull()?.id
        rowButtons(box,c.sections.map {s->s.title.substringAfterLast(" > ")+" (${s.names.size})" to {navigate(c.id,s.id)}})
        val s=c.sections.find {it.id==section} ?: return
        box.addView(label(s.title,18f))
        val p=s.names.mapNotNull {catalog?.definitions?.get(it)}
        val advanced=p.filter {isAdvanced(it.name)};val basic=p.filterNot {isAdvanced(it.name)}
        grid(box,basic.map {settingCard(it)},260)
        if(advanced.isNotEmpty()) {
            val expanded=column().apply {visibility=View.GONE};grid(expanded,advanced.map {settingCard(it)},260)
            box.addView(button("고급 계수 ${advanced.size}개 펼치기/접기"){expanded.visibility=if(expanded.visibility==View.GONE)View.VISIBLE else View.GONE})
            box.addView(expanded)
        }
    }
    private fun isAdvanced(n:String)=listOf("LatMpc","LateralTorque","CustomSteer","LongTuning").any {n.startsWith(it)}||n in setOf("CustomSR","SteerRatioRate","LongActuatorDelay","SteerActuatorDelay","CanfdDebug")
    private fun settingCard(p:TileSetting):View=column().apply {
        background=shape(Color.rgb(27,39,31));setPadding(dp(14),dp(10),dp(14),dp(10))
        addView(label(SettingsTiles.label(p),16f).apply {setTypeface(null,Typeface.BOLD)})
        addView(label(p.description.lineSequence().firstOrNull {it.isNotBlank()}?.take(140) ?: "원본 설명을 확인하세요.",12f).apply {setTextColor(muted)})
        addView(label(SettingsTiles.value(p,values[p.name]),23f))
        addView(label(SettingsTiles.condition(p.name,values),11f).apply {setTextColor(Color.rgb(200,197,161))})
        addView(button("설명·값 변경"){edit(p)}.apply {isEnabled=!busy.get()})
    }
    private fun edit(p:TileSetting) {
        if(busy.get())return
        val before=values[p.name]
        val box=column();box.addView(label(p.description,13f));box.addView(label("${p.name}\n원래 단위 범위 ${p.minimum}~${p.maximum} · 증감 단위 ${p.step}\n${SettingsTiles.condition(p.name,values)}",12f))
        val input=EditText(this).apply {inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED;setText(before?.toString() ?: "");isSingleLine=true}
        val editable=p.editable&&before!=null&&catalog?.hasParams==true&&target!=null
        if(editable){box.addView(label("원래 단위 값 (초·m 등 표시로 환산하기 전 값)",12f));box.addView(input)}
        val dialog=AlertDialog.Builder(this).setTitle(SettingsTiles.label(p)).setView(ScrollView(this).apply {setPadding(dp(16),0,dp(16),0);addView(box)}).setNegativeButton("닫기",null)
        if(editable)dialog.setPositiveButton("기기에 저장",null)
        val shown=dialog.show()
        if(editable)shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=input.text.toString().toIntOrNull()
            if(value==null||value !in p.minimum!!..p.maximum!!){input.error="${p.minimum}~${p.maximum} 원래 단위 정수값";return@setOnClickListener}
            shown.dismiss()
            if(value==before)return@setOnClickListener
            AlertDialog.Builder(this).setTitle("${SettingsTiles.label(p)} 저장 확인").setMessage("${target?.name}\n${SettingsTiles.value(p,before)} → ${SettingsTiles.value(p,value)}\n기존 서버 변경 조건을 확인하고 저장 후 다시 읽습니다. 프로필은 자동 재적용하지 않습니다.")
                .setNegativeButton("취소",null).setPositiveButton("저장"){_,_->write(p,before!!,value)}.show()
        }
    }
    private fun write(p:TileSetting,expected:Int,value:Int) {
        val d=target ?: return;val original=DeviceStore.getActive(this) ?: return
        if(original.id!=d.id||!busy.compareAndSet(false,true))return
        if(!TuningOperationGate.enter()){busy.set(false);message="다른 프로필/설정 작업을 먼저 마쳐주세요.";render();return}
        val token=++generation
        message="기기 저장·재확인 중…";render()
        Thread {
            try {
                val result=SettingsTilesApi.forDevice(d).write(p,expected,value){require(started&&generation==token) {"화면이 바뀌어 저장 요청을 중단했습니다."};bound(original,d)}
                runOnUiThread {if(current(token)&&DeviceStore.getActive(this)?.id==d.id){message=result.message;values=values.toMutableMap().apply {if(result.current==null)remove(p.name)else put(p.name,result.current)}}}
            }catch(e:Exception){runOnUiThread {if(current(token)){message="저장 미완료 · 자동 재시도 없음: ${e.message}";values=values-p.name}}}
            finally{TuningOperationGate.leave();busy.set(false);runOnUiThread {if(current(token))render()}}
        }.start()
    }
}
