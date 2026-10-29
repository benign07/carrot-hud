using System.IO;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.Json;
namespace CarrotTuningLab;
public sealed class Meta {
 public string Name{get;set;}="";public string Title{get;set;}="";public string Description{get;set;}="";public string Category{get;set;}="";public string Group{get;set;}="";
 public int Min{get;set;}public int Max{get;set;}public int Default{get;set;}public int Step{get;set;}public bool Virtual{get;set;}
 public bool Accept(int v)=>v>=Min&&v<=Max||v==Default;
 public string Range=>$"{Min} ~ {Max} · 기본 {Default}"+(Default<Min||Default>Max?" (원본 특수 기본값)":"");
}
public sealed class Catalog {
 public int Schema{get;set;}public string SourceCommit{get;set;}="";public string BuiltDate{get;set;}="";public string BaselineLabel{get;set;}="";
 public List<Meta> Rows{get;set;}=[];public Dictionary<string,string> SourceHashes{get;set;}=[];
 public static Catalog Load()=>JsonSerializer.Deserialize<Catalog>(ReadResource("settings-catalog.json"))!;
 public static string ReadResource(string tail){var a=Assembly.GetExecutingAssembly();using var stream=a.GetManifestResourceStream(a.GetManifestResourceNames().Single(n=>n.EndsWith(tail)))!;using var r=new StreamReader(stream);return r.ReadToEnd();}
}
public sealed class SettingRow : INotifyPropertyChanged {
 public Meta Meta{get;}public string Name=>Meta.Name;public string Title=>Meta.Title;public string Category=>Meta.Category;public string Description=>Meta.Description;public string Range=>Meta.Range;
 public int Baseline{get;set;}public string BaselineSource{get;set;}="소스 기본값";
 string a,b;public string AText{get=>a;set{a=value;Changed();}}public string BText{get=>b;set{b=value;Changed();}}
 public string Conditions=>Rules.Conditions(Name);
 public string State{get;set;}="";public string Delta=>Try(AText,out int av)&&Try(BText,out int bv)&&av==bv?"":"A ≠ B";
 public bool Valid=>Try(AText,out _)&&Try(BText,out _);
 public string Error=>Valid?"":"입력 범위 확인";
 public bool Try(string text,out int value)=>int.TryParse(text,NumberStyles.Integer,CultureInfo.InvariantCulture,out value)&&Meta.Accept(value);
 public int Value(bool useB){if(!Try(useB?b:a,out int v))throw new InvalidDataException(Name+": 범위 밖의 값");return v;}
 public SettingRow(Meta m){Meta=m;Baseline=m.Default;a=b=m.Default.ToString(CultureInfo.InvariantCulture);}
 public event PropertyChangedEventHandler? PropertyChanged;public event Action? Edited;
 void Changed(){PropertyChanged?.Invoke(this,new(nameof(AText)));PropertyChanged?.Invoke(this,new(nameof(BText)));PropertyChanged?.Invoke(this,new(nameof(Delta)));PropertyChanged?.Invoke(this,new(nameof(Error)));Edited?.Invoke();}
 public void NotifyState(){PropertyChanged?.Invoke(this,new(nameof(State)));PropertyChanged?.Invoke(this,new(nameof(Baseline)));}
}
public record Context(double Speed,int Gap,string Situation);
public record Finding(string Section,string Text);
public static class Rules {
 static readonly HashSet<string> Approach=["ApproachCoastStrength","ApproachBrakeMargin","ApproachStopSmooth","ApproachTrafficCalm","ApproachFlexibleGap"];
 static readonly HashSet<string> Margin=["YellowLineBiasCm","NearbyVehicleBiasCm","DriverPositionHold"];
 public static readonly HashSet<string> Interpreted=["MyDrivingMode","MyDrivingModeAuto","ApproachComfortEnabled","ApproachCoastStrength","ApproachBrakeMargin","ApproachStopSmooth","ApproachTrafficCalm","ApproachFlexibleGap","ApproachFlexibleGapMin","LaunchFollowStrength","TFollowGap1","TFollowGap2","TFollowGap3","TFollowGap4","EnableSpeedTF","TFollowDecelBoost","DynamicTFollow","StopDistanceCarrot","AChangeCostStarting","CruiseMaxVals0","CruiseMaxVals1","CruiseMaxVals2","CruiseMaxVals3","CruiseMaxVals4","CruiseMaxVals5","CruiseMaxVals6","UseLaneLineSpeed","UseLaneLineCurveSpeed","YellowLineBiasCm","NearbyVehicleBiasCm","DriverPositionHold","LateralMarginShadow","CruiseOffKeepLateral","CruiseStartCurrentSpeed","NotificationBorderPulse","C3xLiteBuzzerMute","C3xLiteHapticNotifications","EnableCornerRadar","LaneChangeNeedTorque","ManualSteerWithBlinker"];
 public static int V(Dictionary<string,int> v,string key,int fallback=0)=>v.GetValueOrDefault(key,fallback);
 public static string State(string name,Dictionary<string,int> v,Context c) {
  if(Approach.Contains(name)&&V(v,"ApproachComfortEnabled")==0)return "상위 기능 꺼짐 · 대기";
  if(name=="ApproachFlexibleGapMin"&&(V(v,"ApproachComfortEnabled")==0||V(v,"ApproachFlexibleGap")==0))return "간격 유연화 꺼짐 · 대기";
  if(Margin.Contains(name)) {
   if(V(v,name)==0)return "꺼짐 / 이동량 0";
   if(V(v,"UseLaneLineSpeed")==0)return name!="DriverPositionHold"&&V(v,"LateralMarginShadow")==1?"모델 기본 · 이동 대기 / 후보 기록":"모델 기본 모드 · 이동 대기";
   if(V(v,"LateralMarginShadow")==1&&name!="DriverPositionHold")return "기록 전용 · 이동 미적용";
   return "조건부 후보 · 차선/점유 확인 필요";
  }
  if(name=="LateralMarginShadow")return V(v,name)==1?"기록 전용":"실제 적용 후보 허용";
  if(name=="ManualSteerWithBlinker"){if(V(v,name)==0)return "꺼짐 · 조향 양보 옵션 대기";if(V(v,"LaneChangeNeedTorque")>=0)return "자동 차선변경 OFF 조건 필요 · 대기";return "설정상 준비 · 실제 조향 조건 필요";}
  if(name.StartsWith("C3xLiteHaptic")&&name!="C3xLiteHapticNotifications"&&V(v,"C3xLiteHapticNotifications")==0)return "일반 진동 상위 옵션 꺼짐 · 대기";
  if(name.StartsWith("LateralTorque")&&name!="LateralTorqueCustom")return V(v,"LateralTorqueCustom")==0?"커스텀 토크 꺼짐 · 대기":"토크 제어기 경로 조건 필요";
  if(name.StartsWith("LatMpc")&&V(v,"UseLaneLineSpeed")==0)return "차선 MPC 모드 조건 확인";
  if(name=="CruiseMaxVals0")return "가속표 0~10km/h 구간";
  if(Interpreted.Contains(name))return "종합 해석 지원 · 조건부";
  return "개별 설명 참조 · 종합 미해석";
 }
 public static string Conditions(string name){
  if(name=="ApproachComfortEnabled"||Approach.Contains(name)||name=="ApproachFlexibleGapMin")return "상위: ApproachComfortEnabled=1. 간격 유연화 비율은 ApproachFlexibleGap=1도 필요.\n주행: LX3 오파 종제어·일반 ACC·신선하고 유효한 차/레이더/모델, 페달 개입·차선변경·실험모드 제외. 억제/여유는 앞차 접근 확인, 매끄러움은 약한 저속 감속, 유연화는 안정된 동일 앞차 등의 별도 조건.\n값이 0이면 해당 추가 효과 없음. 다른 안전 제한과 MPC 제동은 우선합니다. [longitudinal_planner.py / approach_comfort.py / flexible_gap.py]";
  if(Margin.Contains(name)||name=="LateralMarginShadow")return "상위: 해당 이동량/유지 옵션 ON. 실제 이동은 명시적으로 선택되어 실제 활성인 차선 모드 필요. 노란선/주변차 이동은 LateralMarginShadow=0 필요. 위치 유지는 shadow와 독립.\n환경: 대략30~110km/h, 양쪽 차선·차폭 여유·유효 CAN/레이더·조향 권한, 깜빡이/운전자 개입/차선변경 해제 등. 노란선/주변차 편향은 |곡률|≤0.001 등의 조건도 필요. 각 기능의 원본 설명에 있는 추가 확인 조건을 따릅니다.\n모델 기본 모드에서 노란선/주변차 후보는 shadow가 켜져 있으면 기록할 수 있지만 이동은0. 위치 유지는 캡처 없이 대기. LFA 길게 누름의 실제 모드 전환은 이 PC에 입력하지 않았습니다. [lane_planner_2.py / lateral_planner.py]";
  if(name=="LaunchFollowStrength")return "상위: 별도 주행 보완 값>0. ApproachComfortEnabled의 하위 옵션은 아닙니다.\n주행: 정지 후 움직인 뒤 안정된 동일 레이더 앞차가 멀어짐·목표거리+1m 초과 등. 정상 종제어·신선한 입력·가속 가능 상태 필요. 접근 감속 보완이 작동 중이면 양립 조건에 따라 대기. 기존 저속 정지 판정 후25초 창 안에서만 후보입니다. 20→40km/h에서 감소하고40 도달 뒤에는 다시 저속 정지 판정 전까지 종료합니다. 40 이상에서는 추가 가속 상한을 높이지 않습니다. 기존 MPC·거리 보정·감속 보완은 그대로이며 정지 해제 권한을 바꾸지 않습니다. [approach_comfort.py / longitudinal_planner.py]";
  if(name.StartsWith("C3xLiteHaptic"))return "상위: C3xLiteHapticNotifications=1, 이벤트별 옵션도ON. 패턴은 이벤트 스위치가 아닌 공통 형태 설정.\n독립: HardwareC3xLite·일반 부저 끄기와 별개입니다.\n주행: 정상 CAN·활성 제어·해당 이벤트와 타이머 조건, 깜빡이 때 일반 요청 억제. 과속카메라 햅틱은 별도. 실제 모터/이벤트 반응은 정적 설정만으로 판정 불가. [lx3_haptic_notifications.py / carcontroller.py]";
  if(name=="C3xLiteBuzzerMute"||name=="NotificationBorderPulse")return "독립 설정: C3xLite 본체·일반 핸들 진동과 별개. 일반 소리 또는 비긴급 테두리에 적용합니다. 제어 해제·거부·주의·긴급 등 보호 경고음과 긴급 빨간 테두리를 전부 없애는 스위치가 아닙니다. [soundd.py / notification_preferences.py]";
  if(name.StartsWith("LateralTorque"))return "상위: 커스텀 계수는 LateralTorqueCustom ON 필요.\n제어기: 토크 제어기를 실제 선택한 차량 경로에 해당합니다. 각도 제어 차량에 값을 바꿨다고 같은 효과를 단정하지 않습니다. 현재 CarParams 제어기 선택은 이 도구에 입력되지 않았습니다. [latcontrol_torque.py]";
  if(name.StartsWith("LatMpc")||name=="UseLaneLineSpeed"||name=="UseLaneLineCurveSpeed")return "모드: 저장 선택과 실제 LFA 전환 상태, 속도·커브 조건·히스테리시스에 따라 실제 경로가 달라집니다. 모델 기본 모드의 조향은 차선 MPC 출력으로 정해지지 않습니다. 이 PC 화면은 실제 활성 판정이 아닙니다. [lateral_planner.py / controlsd.py]";
  if(name=="ManualSteerWithBlinker"||name=="LaneChangeNeedTorque")return "자동 차선변경 OFF 조건은 LaneChangeNeedTorque<0입니다. LaneChangeDelay가 아닙니다. 수동조향 양보에는 ManualSteerWithBlinker ON·정상 LX3 조향 권한 등도 필요합니다. 설정상 준비 상태와 실제 깜빡이·조향력·권한에 따른 동작은 구분합니다. [carrot_controls.py / manual_steering.py]";
  if(name=="CruiseOffKeepLateral")return "크루즈 버튼으로 속도 제어를 해제할 때 이미 요청되고 정상 권한이 있는 조향만 유지하는 옵션. 조향 OFF 상태에서 새로 켜거나 고장·Panda 검사를 우회하지 않습니다. [cruise.py / lx3_authority.py]";
  return "이 항목의 전체 코드 적용 조건은 아직 종합 규칙으로 확인하지 않았습니다. 아래 원본 설명을 확인하세요. 스위치를 켰다는 이유만으로 적용 중이라고 판정하지 않습니다.";
 }
 public static double Interp(double x,double[] bp,double[] values){if(x<=bp[0])return values[0];for(int i=1;i<bp.Length;i++)if(x<=bp[i])return values[i-1]+(values[i]-values[i-1])*(x-bp[i-1])/(bp[i]-bp[i-1]);return values[^1];}
 public static double SafeFactor(int mode)=>mode==1?.9:mode==2?.8:1.0;
 public static (double tf,double accel,double component,double reduction) Reference(Dictionary<string,int> v,Context c){
  double[] gaps=Enumerable.Range(1,4).Select(i=>V(v,"TFollowGap"+i,110)/100.0).ToArray();int mode=V(v,"MyDrivingMode",3),speedTf=V(v,"EnableSpeedTF");
  double tf=gaps[c.Gap-1];
  if(speedTf<0){double max=speedTf==-2?120:speedTf==-3?150:90;tf=Interp(c.Speed,[0,max/3,max*2/3,max],gaps)*new double[]{1.0,1.3,1.6,2.0}[c.Gap-1];}
  if(speedTf>0)tf*=1-speedTf*.01+speedTf*.01*Math.Clamp(c.Speed/100,0,1);
  tf=Math.Clamp(tf*SafeFactor(mode),Math.Max(.3,gaps.Min()),Math.Max(Math.Max(.3,gaps.Min()),gaps.Max()));
  var table=Enumerable.Range(0,7).Select(i=>V(v,"CruiseMaxVals"+i,100)/100.0).ToArray();
  double cap=Interp(c.Speed,[0,10,40,60,80,110,140],table)*(mode==4?1.2:SafeFactor(mode));
  double stop=V(v,"StopDistanceCarrot",600)/100.0,ms=c.Speed/3.6;
  double component=stop+ms*tf+ms*ms/2*(1/(2.4*SafeFactor(mode))-1/2.5);
  double reduction=c.Speed<=0||component<=stop?0:Math.Min(component-stop,component*.1*Math.Min(1,c.Speed/100));
  return(tf,cap,component,reduction);
 }
 public static List<Finding> Explain(Dictionary<string,int> v,Dictionary<string,int> baseline,Context c){
  List<Finding> f=[];int mode=V(v,"MyDrivingMode",3);string[] modes=["미정","연비","완만/안전","일반","고속"];
  var r=Reference(v,c);
  f.Add(new("해석 조건",$"{c.Situation} · {c.Speed:0}km/h · 차간거리 {c.Gap}단계. 저장 설정의 정상 ACC를 가정하며 실제 인게이지·LFA 모드 전환·상태 이력은 입력하지 않았습니다."));
  f.Add(new("전체 성향",$"{modes[Math.Clamp(mode,0,4)]} 모드입니다. 가속표 배율은 {(mode==4?1.2:SafeFactor(mode)):0.0#}배, ComfortBrake 배율은 {SafeFactor(mode):0.0#}배입니다. 시간거리에는 모드 배율과 최소·최대 클리핑이 함께 작용합니다."));
  if(V(v,"MyDrivingModeAuto")>0)f.Add(new("상태에 따른 변동","자동 모드 전환이 켜져 있습니다. 아래 숫자는 저장된 모드를 고정한 참고값이며 실제 선택 모드는 달라질 수 있습니다."));
  f.Add(new("출발·가속",$"현재 속도의 가속표 상한은 약 {r.accel:0.00}m/s²입니다. 실제 가속 명령은 앞차·MPC·커브·기존 제한에 따라 더 낮을 수 있습니다. 출발 가속 변화 비용은 {V(v,"AChangeCostStarting",10)}입니다. 비용을 줄이는 것은 출발 허가를 앞당기는 것과 다릅니다."));
  int launch=V(v,"LaunchFollowStrength");
  f.Add(new("재출발",launch==0?"재출발 추종 추가 보완은 꺼져 있습니다. 기존 출발 허가와 가속표를 사용합니다.":$"재출발 추종 보완 {launch}%: 움직인 뒤 같은 앞차가 안정적으로 멀어지고 목표보다 1m 넘게 벌어지는 등의 조건에서 기존 가속표를 보완합니다. 20→40km/h에서 추가 상한을 줄이고40 도달 시 이번 출발 보완을 종료합니다. 이후 감속해도 다시 저속 정지 판정 전에는 재개하지 않습니다. 40 미만에서는 기존25초 창 안에서 대기·재개할 수 있습니다. 정지 유지 해제 시점과 현재 기본 가속곡선은 바꾸지 않습니다. MPC·거리 보정·접근 보완은 그대로입니다."));
  f.Add(new("차간거리 참고",$"상태 이력이 없는 정속 참고 시간거리는 약 {r.tf:0.00}초입니다. 앞차가 같은 속도일 때 정적 기준 거리는 약 {r.component:0.0}m입니다. 이 값은 소스의 desired_follow_distance 식에 같은 속도를 넣은 참고값입니다. 감속 유지·동적 TF·변화율·MPC 예측·신호정지의 추가 ComfortBrake 보정은 제외합니다. 보장 거리가 아닙니다."));
  f.Add(new("속도별 거리 보정",$"유효한 LX3 종제어 조건에서 별도 속도 보정이 만드는 최대 감소 후보는 정적 기준 거리에서 약 {r.reduction:0.0}m입니다. 정지 때 100%, 100km/h에서 90%를 향하지만 정차거리 하한과 MPC 조건이 우선합니다. 간격 유연화와 감소량을 더하지 않고 큰 쪽을 사용합니다."));
  if(V(v,"ApproachComfortEnabled")==0)f.Add(new("앞차 접근·정지","접근·정차 편안함이 꺼져 있어 가속 억제·감속 여유·정차 매끄러움·정체 차분함·간격 유연화는 대기합니다. 아래 값을 바꿔도 상위 옵션을 켜기 전에는 이 보완이 적용되지 않습니다."));
  else {
   f.Add(new("앞차 접근",$"가속 억제 {V(v,"ApproachCoastStrength")}, 감속 여유 {V(v,"ApproachBrakeMargin")}. 감속 여유는 접근 예측 창과 추가 시간거리 후보를 늘립니다. 가속 억제는 양의 가속 상한을 낮추며 직접 브레이크를 거는 값은 아닙니다. 레이더·앞차·제어 조건을 통과해야 합니다."));
   f.Add(new("마지막 정지·정체",$"정차 매끄러움 {V(v,"ApproachStopSmooth")}, 정체 차분함 {V(v,"ApproachTrafficCalm")}. 전자는 약한 저속 감속에서 jerk 비용을 높이는 후보이고, 후자는 정체 재가속 상한을 낮춥니다. 차분함을 낮추면 억제가 줄 수 있지만 출발 반응 개선은 로그로 확인해야 합니다. 급한 접근은 매끄러움보다 제동 조건이 우선합니다."));
   if(V(v,"ApproachFlexibleGap")>0)f.Add(new("간격 유연화",$"같은 앞차의 안정 접근이 확인되면 이동 시간거리 성분을 선택 비율 {V(v,"ApproachFlexibleGapMin",70)}%까지 완화하는 후보입니다. 전체 거리의 비율이 아니며 정속 1.25초 하한과 저속 감속의 별도 조건이 있습니다. 적용·도달 여부는 상태 없이 확정할 수 없습니다."));
   else f.Add(new("간격 유연화","추가 간격 유연화는 꺼져 있습니다. 별도의 속도별 거리 보정과는 다른 기능입니다."));
  }
  f.Add(new("동적 보정",$"감속 시간거리 보정 {V(v,"TFollowDecelBoost")}, 앞차 jerk 기반 동적 시간거리 {V(v,"DynamicTFollow")}. 앞차와 자차의 감속 이력에 따라 결과가 달라집니다. 정적인 설정표만으로 감속 시작 시각이나 최종 간격을 확정하지 않습니다."));
  if(V(v,"UseLaneLineSpeed")==0)f.Add(new("차선 추종·측방 여유","저장 설정은 모델 기본 모드입니다. 미세 위치 조절과 수동 위치 유지 옵션이 이 모드를 자동으로 바꾸지 않습니다. 노란선·옆차 편향과 위치 유지는 기본적으로 대기합니다. LFA 길게 누름으로 실제 차선 모드를 별도 선택했다면 조건을 다시 확인해야 합니다."));
  else f.Add(new("차선 추종·측방 여유",$"차선 모드 선택 속도 {V(v,"UseLaneLineSpeed")}km/h, 커브 설정 {V(v,"UseLaneLineCurveSpeed")}입니다. 실제 활성은 속도·곡률·히스테리시스·차선 신뢰도 등에 따라 달라집니다. 선택 값만으로 활성이라고 판정하지 않습니다. 이 차선 모드에서는 이전 커브 문제 구간의 잘못된 명령이 재현됐으며 근본 해결·실차 검증 전입니다."));
  if(V(v,"LateralMarginShadow")==1)f.Add(new("편향 기록","측방 여유는 기록 전용입니다. 노란선·주변차량 후보를 계산해도 해당 편향을 조향 경로에 적용하지 않습니다."));
  if(V(v,"DriverPositionHold")>0)f.Add(new("수동 위치 유지","명시 차선 모드와 관측 조건을 통과한 위치 조정에 한해 3초 유지 후 5초 복귀하는 후보입니다. 위험 회피 조작을 선호 위치로 학습한 것으로 보지 않습니다. 조향 힘의 0.2초 복귀와 별개입니다."));
  f.Add(new("차선변경 설정",$"LaneChangeNeedTorque {V(v,"LaneChangeNeedTorque")}: 음수이면 자동차선변경 OFF 조건입니다. 깜빡이 수동조향은 별도의 ManualSteerWithBlinker 옵션과 LX3 정상 제어 조건이 필요합니다. LaneChangeDelay는 시간 지연 항목이며 자동차선변경 OFF 스위치가 아닙니다. 수동조향 옵션 ManualSteerWithBlinker 저장값은 {V(v,"ManualSteerWithBlinker")}입니다. 실제 깜빡이·조향 힘·권한 조건은 입력되지 않았습니다."));
  f.Add(new("버튼·표시",$"크루즈 재개는 {(V(v,"CruiseStartCurrentSpeed",1)>0?"현재속도 시작 요청":"기존 재개 속도 설정 참조")}, 크루즈 해제 시 조향은 {(V(v,"CruiseOffKeepLateral")>0?"이미 요청된 조향 유지 선택":"함께 해제 선택")}입니다. 주변 대상 표시 {V(v,"EnableCornerRadar")}: 0은 오파 영상 오버레이 끄기, 1은 켜기, 2는 기존 끼어들기 검출도 유지합니다. 화면 좌표를 측방 제어 입력으로 쓰지는 않습니다."));
  f.Add(new("알림",$"일반 소리 끄기 {V(v,"C3xLiteBuzzerMute")}, 일반 진동 {V(v,"C3xLiteHapticNotifications")}, 비긴급 테두리 {V(v,"NotificationBorderPulse",1)}. 일반 알림 출력은 독립적이며 기존 보호 경고음·긴급 테두리는 유지됩니다. 정상 CAN·활성 제어 등 조건이 필요하고 깜빡이 때는 일반 진동 요청을 억제합니다. 속도카메라 햅틱은 별도 설정입니다. 인게이지 시 실제 진동은 소유자가 확인했으나 다른 이벤트·반복 패턴 전체가 검증된 것은 아닙니다."));
  var changed=v.Where(x=>baseline.TryGetValue(x.Key,out int b)&&b!=x.Value).ToList();
  var pending=changed.Where(x=>State(x.Key,v,c).Contains("대기")||State(x.Key,v,c).Contains("기록 전용")).ToList();
  if(pending.Count>0)f.Add(new("변경했지만 대기하는 항목",string.Join("\n",pending.Select(x=>$"{x.Key}: {baseline[x.Key]} → {x.Value} · {State(x.Key,v,c)}"))));
  var notCovered=changed.Where(x=>!Interpreted.Contains(x.Key)).ToList();
  f.Add(new("해석 범위",$"변경 {changed.Count}개 중 종합 규칙 지원 {changed.Count-notCovered.Count}개. 전체 184개 설정의 개별 설명을 보존하지만 모든 조합의 실제 거동을 해석한 것은 아닙니다. 곡선 궤적·인지 오류·물리 제동·Panda 권한·계기판 경고·SPI는 이 도구로 판정하지 않습니다."));
  if(notCovered.Count>0)f.Add(new("종합 미해석 변경 · 개별 설명 확인",string.Join("\n",notCovered.Select(x=>$"{x.Key}: {baseline[x.Key]} → {x.Value}"))));
  return f;
 }
}
