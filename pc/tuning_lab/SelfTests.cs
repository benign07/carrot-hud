using System.Text.Json;
namespace CarrotTuningLab;
public static class SelfTests {
 public static int Run(string output){int count=0;List<string> details=[];
  void Check(bool ok,string why){count++;if(!ok)throw new Exception(why);}
  try{
   var c=Catalog.Load();var v=c.Rows.ToDictionary(r=>r.Name,r=>r.Default);var context=new Context(50,1,"비교");
   Check(c.Rows.Count==184,"184 HUD definitions");Check(v.Count==184,"No duplicate names");
   foreach(var m in c.Rows){Check(m.Accept(m.Default),m.Name+" preserves actual source default");Check(!string.IsNullOrWhiteSpace(m.Description),m.Name+" description or explicit missing label");Check(new SettingRow(m).Error=="","original default never gets invalid-step badge "+m.Name);Check(!m.Accept(int.MinValue),"invalid low "+m.Name);Check(!m.Accept(int.MaxValue),"invalid high "+m.Name);}
   Check(c.Rows.Count(m=>m.Default<m.Min||m.Default>m.Max)==7,"7 original exceptional defaults preserved");
   Check(c.Rows.Count(m=>m.Description.StartsWith("원본 설정에 개별 설명이 없습니다"))==10,"10 missing original descriptions labelled");
   var pack=new ProfilePack{SourceCommit=c.SourceCommit,Baseline=v,A=new(v),B=new(v)};
   Check(Profiles.ReadPack(JsonSerializer.Serialize(pack),c).B.Count==184,"profile roundtrip");
   var partial=Profiles.ReadValues("{\"values\":{\"MyDrivingMode\":\"3\",\"ApproachComfortEnabled\":true,\"unknown\":7}}",c);
   Check(partial.Count==2&&partial["MyDrivingMode"]==3&&partial["ApproachComfortEnabled"]==1,"partial HUD values supported; unrelated keys not copied");
   foreach(var invalid in new[]{"{\"values\":{\"MyDrivingMode\":99}}","{\"values\":{\"MyDrivingMode\":3.5}}","{\"values\":[]}","{\"unknown\":1}"}){bool rejected=false;try{Profiles.ReadValues(invalid,c);}catch{rejected=true;}Check(rejected,"reject invalid import");}
   using(var portable=JsonDocument.Parse(Catalog.ReadResource("tuning_profiles_v1.json"))){foreach(var item in portable.RootElement.GetProperty("profiles").EnumerateArray()){var values=PortableProfiles.Read(item.GetRawText(),c);Check(values.Count==27,"portable full-domain preset");var combined=new Dictionary<string,int>(v);foreach(var kv in values)combined[kv.Key]=kv.Value;var roundtrip=PortableProfiles.Read(PortableProfiles.Export(combined,c),c);Check(values.All(kv=>roundtrip[kv.Key]==kv.Value),"portable HUD export roundtrip");Check(!values.ContainsKey("DisableDM")&&!values.ContainsKey("UseLaneLineSpeed"),"portable excludes DM/lateral");}}
   var bad=new ProfilePack{Baseline=v,A=new(v),B=new(v)};bad.B.Remove("MyDrivingMode");bool reject=false;try{Profiles.ReadPack(JsonSerializer.Serialize(bad),c);}catch{reject=true;}Check(reject,"reject incomplete A/B before mutation");
   var setting=new SettingRow(c.Rows.Single(x=>x.Name=="ApproachBrakeMargin"));setting.BText="nan";Check(!setting.Valid,"invalid edit does not produce summary value");setting.BText="80";Check(setting.Valid&&setting.Value(true)==80,"correct edit restored");
   foreach(string key in new[]{"NearbyVehicleBiasCm","YellowLineBiasCm","DriverPositionHold"}){var test=new Dictionary<string,int>(v){[key]=key=="DriverPositionHold"?1:5,["UseLaneLineSpeed"]=0};Check(Rules.State(key,test,context).Contains("대기"),"model mode dormancy "+key);}
   var waiting=new Dictionary<string,int>(v){["ApproachComfortEnabled"]=0,["ApproachBrakeMargin"]=100};Check(Rules.State("ApproachBrakeMargin",waiting,context).Contains("대기"),"disabled parent dependency");
   waiting["ApproachComfortEnabled"]=1;Check(!Rules.State("ApproachBrakeMargin",waiting,context).Contains("대기"),"parent enabled");
   var manual=new Dictionary<string,int>(v){["ManualSteerWithBlinker"]=1,["LaneChangeNeedTorque"]=-1};Check(!Rules.State("ManualSteerWithBlinker",manual,context).Contains("OFF 조건 필요"),"actual negative NeedTorque permits manual branch");manual["LaneChangeNeedTorque"]=0;Check(Rules.State("ManualSteerWithBlinker",manual,context).Contains("OFF 조건 필요"),"nonnegative NeedTorque keeps manual branch ineligible");
   Check(Profiles.ReadValues("{\"TFollowGap1\":\"110.0\"}",c)["TFollowGap1"]==110,"integral decimal string accepted");Check(Profiles.IgnoredKeys("{\"values\":{\"MyDrivingMode\":3,\"unrelated\":1}}",c)==1,"unknown key count exposed");
   foreach(var row in c.Rows)Check(!string.IsNullOrWhiteSpace(Rules.Conditions(row.Name)),"explicit known or unreviewed conditions for "+row.Name);
   Check(Profiles.ReadValues("{\"TFollowGap1\":110.0}",c)["TFollowGap1"]==110,"integral decimal JSON number accepted");
   Check(c.Rows.Any(r=>r.Name=="ManualSteerWithBlinker"),"manual steering option exists in catalog");manual["ManualSteerWithBlinker"]=0;Check(Rules.State("ManualSteerWithBlinker",manual,context).StartsWith("꺼짐"),"manual switch off precedes eligibility");
   var shadow=new Dictionary<string,int>(v){["UseLaneLineSpeed"]=30,["NearbyVehicleBiasCm"]=5,["LateralMarginShadow"]=1,["DriverPositionHold"]=1};Check(Rules.State("NearbyVehicleBiasCm",shadow,context).Contains("기록 전용"),"shadow no actuation");Check(!Rules.State("DriverPositionHold",shadow,context).Contains("기록 전용"),"hold separate from margin shadow");
   var unreviewed=new Dictionary<string,int>(v){["ShowDebugUI"]=v["ShowDebugUI"]==0?1:0};Check(Rules.Explain(unreviewed,v,context).Any(f=>f.Section.StartsWith("종합 미해석")&&f.Text.Contains("ShowDebugUI")),"uninterpreted change explicitly listed");
   using var refs=JsonDocument.Parse(Catalog.ReadResource("reference-cases.json"));int refsCount=0;
   Check(refs.RootElement.GetProperty("SourceCommit").GetString()==c.SourceCommit,"production reference commit binding");
   foreach(var f in refs.RootElement.GetProperty("Cases").EnumerateArray()){
    var values=new Dictionary<string,int>(v);foreach(var x in f.GetProperty("Overrides").EnumerateObject())values[x.Name]=x.Value.GetInt32();
    var calc=Rules.Reference(values,new(f.GetProperty("Speed").GetDouble(),f.GetProperty("Gap").GetInt32(),"production reference"));
    foreach(var pair in new[]{("Tf",calc.tf),("Accel",calc.accel),("Component",calc.component),("Reduction",calc.reduction)})Check(Math.Abs(f.GetProperty(pair.Item1).GetDouble()-pair.Item2)<1e-9,"production parity "+pair.Item1);
    refsCount++;
   }
   Check(refsCount==480,"480 production reference combinations");details.Add("All definitions/default exceptions, import validation, profile roundtrip, mode/parent/shadow dormancy, unmodelled changes and 480 direct-production static references");
   Profiles.WriteAtomic(output,JsonSerializer.Serialize(new{passed=true,checks=count,production_reference_cases=refsCount,catalog_count=c.Rows.Count,source_commit=c.SourceCommit,details,scope="Static settings explanation and reference math. No native MPC, physical plant, vehicle connection or road qualification."},Profiles.Json));return 0;
  }catch(Exception e){Profiles.WriteAtomic(output,JsonSerializer.Serialize(new{passed=false,checks=count,error=e.ToString()},Profiles.Json));return 1;}
 }
}
