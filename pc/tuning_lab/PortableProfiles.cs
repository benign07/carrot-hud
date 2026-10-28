using System.IO;using System.Text.Json;using System.Text.RegularExpressions;
namespace CarrotTuningLab;
public static class PortableProfiles {
 public static readonly Dictionary<string,string> Units=LoadUnits();
 static Dictionary<string,string> LoadUnits(){using var d=JsonDocument.Parse(Catalog.ReadResource("tuning_profiles_v1.json"));return d.RootElement.GetProperty("profiles")[0].GetProperty("units").EnumerateObject().ToDictionary(x=>x.Name,x=>x.Value.GetString()!);}
 public static Dictionary<string,int> Read(string raw,Catalog catalog){
  if(System.Text.Encoding.UTF8.GetByteCount(raw)>32768)throw new InvalidDataException("프로필 크기 초과");
  using var d=JsonDocument.Parse(raw);var p=d.RootElement;
  void Unique(JsonElement e){if(e.ValueKind==JsonValueKind.Object){var names=new HashSet<string>();foreach(var x in e.EnumerateObject()){if(!names.Add(x.Name))throw new InvalidDataException("중복 JSON 키");Unique(x.Value);}}else if(e.ValueKind==JsonValueKind.Array)foreach(var x in e.EnumerateArray())Unique(x);else if(e.ValueKind==JsonValueKind.Number&&e.GetRawText()=="-0")throw new InvalidDataException("음수 영 미지원");}
  Unique(p);
  var allowed=new HashSet<string>{"kind","schema","family","scope","id","name","description","values","units","source_commit","behavior_revision"};if(p.EnumerateObject().Any(x=>!allowed.Contains(x.Name)))throw new InvalidDataException("미지원 최상위 프로필 필드");
  if(p.GetProperty("kind").GetString()!="CarrotTuningProfile"||p.GetProperty("schema").GetInt32()!=1||p.GetProperty("family").GetString()!="LX3"||p.GetProperty("scope").GetString()!="longitudinal")throw new InvalidDataException("미지원 HUD 프로필 규격");
  if(!Regex.IsMatch(p.GetProperty("id").GetString()??"","^[a-z0-9][a-z0-9-]{0,63}$")||(p.GetProperty("name").GetString()?.Trim().Length??0) is <1 or >80)throw new InvalidDataException("프로필 이름·ID 오류");
  var values=p.GetProperty("values");var units=p.GetProperty("units");var rows=catalog.Rows.ToDictionary(x=>x.Name);
  var names=values.EnumerateObject().Select(x=>x.Name).ToArray();if(names.Length<1||names.Distinct().Count()!=names.Length||!names.Order().SequenceEqual(units.EnumerateObject().Select(x=>x.Name).Order()))throw new InvalidDataException("값·단위 목록 불일치");
  Dictionary<string,int> result=[];
  foreach(var x in values.EnumerateObject()){
   if(!Units.TryGetValue(x.Name,out var expected)||units.GetProperty(x.Name).GetString()!=expected||!rows.TryGetValue(x.Name,out var meta)||!x.Value.TryGetInt32(out int v)||v<meta.Min||v>meta.Max)throw new InvalidDataException(x.Name+": 현재 규격·범위에서 지원하지 않습니다. 자동 보정하지 않았습니다.");
   result[x.Name]=v;
  }return result;
 }
 public static string BehaviorRevision(){using var d=JsonDocument.Parse(Catalog.ReadResource("tuning_profiles_v1.json"));return d.RootElement.GetProperty("profiles")[0].GetProperty("behavior_revision").GetString()!;}
 public static string Export(Dictionary<string,int> values,Catalog c){
  var data=new{kind="CarrotTuningProfile",schema=1,family="LX3",scope="longitudinal",id="pc-"+Guid.NewGuid().ToString("N"),name="PC 시험안 B",description="PC에서 편집한 B값. 실기기 현재값 스냅샷이 아니며 적용 전 HUD에서 변경값을 확인하세요.",source_commit=c.SourceCommit,behavior_revision=BehaviorRevision(),units=Units,values=Units.Keys.ToDictionary(k=>k,k=>values[k])};
  string raw=JsonSerializer.Serialize(data,Profiles.Json);Read(raw,c);return raw;
 }
}
