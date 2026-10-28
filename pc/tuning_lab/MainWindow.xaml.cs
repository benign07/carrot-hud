using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Threading;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
namespace CarrotTuningLab;
public sealed class ProfilePack {
 public string Format{get;set;}="CarrotTuningLab-v1";public string SourceCommit{get;set;}="";public string BaselineLabel{get;set;}="";
 public Dictionary<string,int> Baseline{get;set;}=[];public Dictionary<string,int> A{get;set;}=[];public Dictionary<string,int> B{get;set;}=[];
 public Dictionary<string,string> Origins{get;set;}=[];
}
public static class Profiles {
 public static readonly JsonSerializerOptions Json=new(){WriteIndented=true,PropertyNameCaseInsensitive=true};
 public static ProfilePack ReadPack(string raw,Catalog c){
  var p=JsonSerializer.Deserialize<ProfilePack>(raw,Json)??throw new InvalidDataException("프로필 내용 없음");
  if(p.Format!="CarrotTuningLab-v1")throw new InvalidDataException("지원하지 않는 프로필 형식");
  foreach(var d in new[]{p.Baseline,p.A,p.B}) {
   if(d.Count!=c.Rows.Count||c.Rows.Any(m=>!d.TryGetValue(m.Name,out int v)||!m.Accept(v)))throw new InvalidDataException("설정 목록 또는 값의 범위가 맞지 않습니다.");
  }
  return p;
 }
 public static Dictionary<string,int> ReadValues(string raw,Catalog c){
  using var doc=JsonDocument.Parse(raw,new(){MaxDepth=32});var root=doc.RootElement;
  if(root.ValueKind!=JsonValueKind.Object)throw new InvalidDataException("values 객체 또는 이름:값 형태의 JSON이 필요합니다.");
  if(root.TryGetProperty("values",out var child))root=child;
  if(root.ValueKind!=JsonValueKind.Object)throw new InvalidDataException("values는 객체여야 합니다.");
  var known=c.Rows.ToDictionary(x=>x.Name);Dictionary<string,int> values=[];
  foreach(var field in root.EnumerateObject()) {
   if(!known.TryGetValue(field.Name,out var m))continue;
   int v;
   if(field.Value.ValueKind==JsonValueKind.Number&&field.Value.TryGetInt32(out v)){}
   else if(field.Value.ValueKind==JsonValueKind.Number&&field.Value.TryGetDecimal(out decimal nd)&&nd==decimal.Truncate(nd)&&nd>=int.MinValue&&nd<=int.MaxValue)v=(int)nd;
   else if(field.Value.ValueKind==JsonValueKind.String&&int.TryParse(field.Value.GetString(),NumberStyles.Integer,CultureInfo.InvariantCulture,out v)){}
   else if(field.Value.ValueKind==JsonValueKind.String&&decimal.TryParse(field.Value.GetString(),NumberStyles.Float,CultureInfo.InvariantCulture,out decimal dv)&&dv==decimal.Truncate(dv)&&dv>=int.MinValue&&dv<=int.MaxValue)v=(int)dv;
   else if(field.Value.ValueKind==JsonValueKind.True)v=1;
   else if(field.Value.ValueKind==JsonValueKind.False)v=0;
   else throw new InvalidDataException(field.Name+": 정수값이 필요합니다.");
   if(!m.Accept(v))throw new InvalidDataException(field.Name+": 범위 밖 값입니다. 원본 파일은 유지됩니다.");
   values[field.Name]=v;
  }
  if(values.Count==0)throw new InvalidDataException("알려진 설정값을 찾지 못했습니다.");
  return values;
 }
 public static int IgnoredKeys(string raw,Catalog c){using var doc=JsonDocument.Parse(raw,new(){MaxDepth=32});var root=doc.RootElement;if(root.TryGetProperty("values",out var child))root=child;var known=c.Rows.Select(r=>r.Name).ToHashSet();return root.EnumerateObject().Count(p=>!known.Contains(p.Name));}
 public static void WriteAtomic(string path,string raw){var full=Path.GetFullPath(path);Directory.CreateDirectory(Path.GetDirectoryName(full)!);var temp=full+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,raw,new UTF8Encoding(false));File.Move(temp,full,true);}finally{if(File.Exists(temp))File.Delete(temp);}}
}
public partial class MainWindow:Window {
 public Catalog Catalog{get;}=Catalog.Load();public ObservableCollection<SettingRow> Rows{get;}=[];
 bool ready=false,updating=false,queued=false,restoreFailed=false;string label="소스 기본값 · 기기 현재값 아님";
 static string Session=>Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CarrotTuningLab","session-v1.json");
 DispatcherTimer? animationTimer;
 public MainWindow(bool testMode=false){
  InitializeComponent();
  foreach(var m in Catalog.Rows){var row=new SettingRow(m);row.Edited+=QueueUpdate;Rows.Add(row);}
  SettingsGrid.ItemsSource=Rows;
  SettingsGrid.SizeChanged+=(_,_)=>SettingsGrid.Columns[0].Width=new DataGridLength(Math.Max(260,SettingsGrid.ActualWidth-374));
  Category.Items.Add("전체 설정");foreach(var g in Rows.Select(r=>r.Meta.Group).Distinct())Category.Items.Add(g);Category.SelectedIndex=0;
  Gap.SelectedIndex=0;Situation.SelectedIndex=0;SummaryTabs.SelectedIndex=1;
  VersionText.Text=$"v1.1 · 프로필 규격1 · 설정 {Rows.Count}개 · 기준 소스 {Catalog.SourceCommit[..8]}";
  bool restored=false;
  if(!testMode&&File.Exists(Session)){try{Apply(Profiles.ReadPack(File.ReadAllText(Session),Catalog));restored=true;}catch(Exception e){restoreFailed=true;StatusText.Text="이전 작업 파일을 유지하며 자동 저장을 중지했습니다. 불러오기 실패: "+e.Message;}}
  ready=true;RefreshAll();if(restored)StatusText.Text="지난 PC 시험안을 복원했습니다. 차량에는 전송하지 않습니다.";
  Loaded+=(_,_)=>{if(Rows.Count>0)SettingsGrid.SelectedItem=Rows[0];};
  if(!testMode){animationTimer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(80)};animationTimer.Tick+=(_,_)=>{if(SummaryTabs.SelectedIndex==3&&IsVisible)DrawAnimation((DateTime.UtcNow.TimeOfDay.TotalSeconds%12)/12);};animationTimer.Start();Closed+=(_,_)=>animationTimer.Stop();}
  if(!testMode)Closing+=(_,_)=>{if(!restoreFailed&&Rows.All(r=>r.Valid)){try{Profiles.WriteAtomic(Session,JsonSerializer.Serialize(Pack(),Profiles.Json));}catch(Exception e){MessageBox.Show("PC 작업 자동 저장 실패: "+e.Message);}}};
 }
 Context? CurrentContext(){if(!double.TryParse(Speed.Text,NumberStyles.Float,CultureInfo.InvariantCulture,out double s)||!double.IsFinite(s)||s<0||s>160)return null;return new(s,Math.Max(1,Gap.SelectedIndex+1),(Situation.SelectedItem as ComboBoxItem)?.Content?.ToString()??"앞차 접근·감속");}
 Dictionary<string,int> Values(bool b)=>Rows.ToDictionary(x=>x.Name,x=>x.Value(b));
 Dictionary<string,int> Baseline()=>Rows.ToDictionary(x=>x.Name,x=>x.Baseline);
 void QueueUpdate(){if(!ready||updating||queued)return;queued=true;Dispatcher.BeginInvoke(()=>{queued=false;RefreshAll();});}
 void ContextChanged(object sender,RoutedEventArgs e)=>QueueUpdate();
 void FilterChanged(object sender,RoutedEventArgs e){if(ready)RefreshFilter();}
 void RefreshFilter(){
  string text=Search.Text.Trim();string group=Category.SelectedItem?.ToString()??"전체 설정";
  CollectionViewSource.GetDefaultView(Rows).Filter=x=>{var r=(SettingRow)x;return(group=="전체 설정"||r.Meta.Group==group)&&(!ChangedOnly.IsChecked.GetValueOrDefault()||!r.Valid||r.Value(false)!=r.Baseline||r.Value(true)!=r.Baseline)&&(text.Length==0||(r.Name+" "+r.Title+" "+r.Description+" "+r.Category).Contains(text,StringComparison.OrdinalIgnoreCase));};
  int shown=CollectionViewSource.GetDefaultView(Rows).Cast<object>().Count();CountText.Text=$"전체 {Rows.Count}개 중 {shown}개 · 값 옆 설명 / 행 선택 시 상세 · 오른쪽 조건은 시험안 B 기준";
 }
 void RefreshAll(){
  if(!ready)return;BaselineLabel.Text=label;var ctx=CurrentContext();
  if(Rows.Any(x=>!x.Valid)||ctx==null){foreach(var p in new[]{SummaryA,SummaryB}){p.Children.Clear();p.Children.Add(Body("입력 범위를 확인해 주세요. 속도는 0~160km/h이며 설정값은 각 항목의 범위를 따릅니다. 원본 범위 밖의 특수 기본값은 그대로 보존할 수 있습니다.",true));}DifferenceText.Text="유효한 값으로 입력하면 비교 설명을 표시합니다.";RefreshFilter();return;}
  var a=Values(false);var b=Values(true);var baseline=Baseline();
  foreach(var row in Rows){row.State=Rules.State(row.Name,b,ctx);row.NotifyState();}
  Render(SummaryA,Rules.Explain(a,baseline,ctx));Render(SummaryB,Rules.Explain(b,baseline,ctx));
  var changes=Rows.Where(r=>r.Value(false)!=r.Value(true)).ToList();var sb=new StringBuilder();
  var ra=Rules.Reference(a,ctx);var rb=Rules.Reference(b,ctx);
  sb.AppendLine($"현재 조건: {ctx.Speed:0}km/h · {ctx.Gap}단계\n");
  sb.AppendLine($"가속표 상한: A {ra.accel:0.00} → B {rb.accel:0.00}m/s²");
  sb.AppendLine($"정속 참고 시간거리: A {ra.tf:0.00} → B {rb.tf:0.00}초");
  sb.AppendLine($"같은 속도 앞차 기준거리: A {ra.component:0.0} → B {rb.component:0.0}m\n");
  sb.AppendLine("위 숫자는 상태 보정 전 계산 참고값입니다. 실제 감속 시점·가속 명령·차간거리 예측값이 아닙니다.\n");
  sb.AppendLine(changes.Count==0?"A와 B의 설정값이 같습니다.":$"설정 차이 {changes.Count}개");
  foreach(var r in changes)sb.AppendLine($"\n{r.Title}\n{r.Name}: {r.Value(false)} → {r.Value(true)}\nB 조건: {r.State}"+(Rules.Interpreted.Contains(r.Name)?"":"\n종합 미해석 · 개별 설명을 확인해 주세요."));
  DifferenceText.Text=sb.ToString();RefreshFilter();UpdateDetail();if(SummaryTabs.SelectedIndex==3)DrawAnimation(0);
 }
 static TextBlock Body(string text,bool issue=false)=>new(){Text=text,TextWrapping=TextWrapping.Wrap,LineHeight=22,Margin=new(0,0,0,13),Foreground=new SolidColorBrush(issue?Color.FromRgb(166,57,45):Color.FromRgb(67,82,103))};
 static void Render(StackPanel panel,List<Finding> findings){panel.Children.Clear();foreach(var f in findings){panel.Children.Add(new TextBlock{Text=f.Section,FontSize=14,FontWeight=FontWeights.SemiBold,Margin=new(0,4,0,6),Foreground=new SolidColorBrush(Color.FromRgb(34,103,85))});panel.Children.Add(Body(f.Text));}}
 void SelectedSetting(object sender,SelectionChangedEventArgs e)=>UpdateDetail();
 void UpdateDetail(){if(SettingsGrid.SelectedItem is not SettingRow r)return;DetailTitle.Text=r.Title;DetailRange.Text=$"{r.Name}\n{r.Range}\n기준값 출처: {r.BaselineSource}";DetailText.Text=r.Description;DetailState.Text=$"시험안 B: {r.State}\n\n[적용 조건]\n{Rules.Conditions(r.Name)}"+(r.Meta.Default<r.Meta.Min||r.Meta.Default>r.Meta.Max?"\n원본 기본값이 표시 범위 밖에 있습니다. 기본값을 보존하며 그 외 값은 표시 범위를 따릅니다. 특수 기본값의 의미는 별도 코드 확인이 필요합니다.":"");DetailSource.Text=$"HUD 설정 정의 / {r.Category}\n기준 소스 {Catalog.SourceCommit[..8]} · 개별 설명은 원본 문구입니다. 실차 반응은 상태에 따라 달라집니다.";}

 void DrawAnimation(double phase){
  AnimationCanvas.Children.Clear();var ctx=CurrentContext();if(ctx==null||Rows.Any(r=>!r.Valid)){AnimationLabel.Text="유효한 값을 입력하면 안내를 표시합니다.";return;}
  double v=ctx.Speed*(.5-.5*Math.Cos(phase*2*Math.PI));double width=AnimationCanvas.ActualWidth>100?AnimationCanvas.ActualWidth:350;
  var a=Rules.Reference(Values(false),ctx with{Speed=v});var b=Rules.Reference(Values(true),ctx with{Speed=v});
  double flow=ctx.Speed==0?0:168*(phase-Math.Sin(phase*2*Math.PI)/(2*Math.PI));
  double[] centers=[width*.27,width*.73];var refs=new[]{a,b};Color[] colors=[Color.FromRgb(66,139,199),Color.FromRgb(222,142,59)];
  void Add(UIElement e,double x,double y){Canvas.SetLeft(e,x);Canvas.SetTop(e,y);AnimationCanvas.Children.Add(e);}
  void Text(string text,double x,double y,Brush brush,int size=12)=>Add(new TextBlock{Text=text,Foreground=brush,FontSize=size},x,y);
  void Car(double x,double y,Brush fill){Add(new System.Windows.Shapes.Rectangle{Width=32,Height=55,RadiusX=7,RadiusY=7,Fill=fill},x-16,y);Add(new System.Windows.Shapes.Rectangle{Width=24,Height=12,RadiusX=3,RadiusY=3,Fill=new SolidColorBrush(Color.FromRgb(211,227,235))},x-12,y+8);Add(new System.Windows.Shapes.Rectangle{Width=24,Height=7,Fill=Brushes.LightSlateGray},x-12,y+39);}
  for(int i=0;i<2;i++){
   double x=centers[i];var brush=new SolidColorBrush(colors[i]);var r=refs[i];
   Add(new System.Windows.Shapes.Rectangle{Width=106,Height=185,RadiusX=12,RadiusY=12,Fill=new SolidColorBrush(Color.FromRgb(226,232,238))},x-53,34);
   for(int j=0;j<6;j++){double y=38+(j*28+flow)%168;Add(new System.Windows.Shapes.Rectangle{Width=3,Height=18,Fill=Brushes.White},x-43,y);Add(new System.Windows.Shapes.Rectangle{Width=3,Height=18,Fill=Brushes.White},x+40,y);}
   double spacing=35+60*r.component/Math.Max(30,Math.Max(a.component,b.component));Car(x,155-spacing,new SolidColorBrush(Color.FromRgb(132,144,159)));Car(x,180,brush);
   Text(i==0?"시험안 A":"시험안 B",x-29,8,brush,14);
   Text($"기준 {r.component:0.0}m",x-43,240,Brushes.SlateGray,12);
   Add(new System.Windows.Shapes.Rectangle{Width=100,Height=7,Fill=new SolidColorBrush(Color.FromRgb(220,226,232))},x-50,260);
   Add(new System.Windows.Shapes.Rectangle{Width=Math.Clamp(r.accel/3,0,1)*100,Height=7,Fill=brush},x-50,260);
   Text($"상한 {r.accel:0.00}m/s²",x-48,272,brush,11);
  }
  AnimationLabel.Text=$"예시 속도 {v:0}km/h · 최대 {ctx.Speed:0}km/h까지 변화\nA 시간거리 {a.tf:0.00}초 / B {b.tf:0.00}초\n표시한 상한은 가속 명령이 아니며, 접근·감속 옵션의 상태별 효과는 종합 설명에서 확인합니다.";
 }
 internal object RunUiSmoke(string imagePath){
  bool Assert(bool ok,string message){if(!ok)throw new Exception(message);return ok;}
  var root=(System.Windows.Controls.Grid)Content;root.Background=Background;root.Measure(new Size(1580,980));root.Arrange(new Rect(0,0,1580,980));root.UpdateLayout();
  Search.Text="ApproachBrakeMargin";RefreshAll();Assert(CollectionViewSource.GetDefaultView(Rows).Cast<object>().Count()==1,"search exact key");
  var r=Rows.Single(x=>x.Name=="ApproachBrakeMargin");r.BText="80";RefreshAll();Assert(r.State.Contains("대기"),"parent disabled on UI");
  Rows.Single(x=>x.Name=="ApproachComfortEnabled").BText="1";RefreshAll();Assert(!r.State.Contains("대기"),"parent enabled on UI");
  Assert(DifferenceText.Text.Contains("40 → 80"),"A/B comparison updated");
  Speed.Text="nan";RefreshAll();Assert(((TextBlock)SummaryB.Children[0]).Text.Contains("입력 범위"),"invalid context blocks summary");
  Speed.Text="50";Search.Text="Approach";RefreshAll();SettingsGrid.SelectedItem=r;UpdateDetail();Assert(DetailTitle.Text==r.Title,"detail selection updated");
  SummaryTabs.SelectedIndex=3;root.UpdateLayout();DrawAnimation(.5);Assert(AnimationCanvas.Children.Count>20,"animation rendered");
  var bitmap=new RenderTargetBitmap(1580,980,96,96,PixelFormats.Pbgra32);root.UpdateLayout();bitmap.Render(root);var enc=new PngBitmapEncoder();enc.Frames.Add(BitmapFrame.Create(bitmap));using(var output=File.Create(imagePath))enc.Save(output);
  return new{passed=true,checks=7,rendered_preview=imagePath,scope="WPF own-control integration and render, not physical input or live screen capture"};
 }

 ProfilePack Pack()=>new(){SourceCommit=Catalog.SourceCommit,BaselineLabel=label,Baseline=Baseline(),A=Values(false),B=Values(true),Origins=Rows.ToDictionary(r=>r.Name,r=>r.BaselineSource)};
 void Apply(ProfilePack p){updating=true;foreach(var r in Rows){r.Baseline=p.Baseline[r.Name];r.BaselineSource=p.Origins.GetValueOrDefault(r.Name,"가져온 PC 프로필");r.AText=p.A[r.Name].ToString();r.BText=p.B[r.Name].ToString();}label=p.BaselineLabel+(p.SourceCommit!=Catalog.SourceCommit?" · 다른 소스의 프로필":"");updating=false;}
 static string? OpenJson(string title){var d=new OpenFileDialog{Title=title,Filter="JSON 설정 파일|*.json",CheckFileExists=true};return d.ShowDialog()==true?d.FileName:null;}
 static string ReadLimited(string path){if(new FileInfo(path).Length>4*1024*1024)throw new InvalidDataException("파일 크기는 4MB 이하로 제한합니다.");return File.ReadAllText(path);}
 void ImportBaseline(object sender,RoutedEventArgs e){var file=OpenJson("기준 설정값 가져오기 — 차량 연결 없음");if(file==null)return;try{var raw=ReadLimited(file);var values=Profiles.ReadValues(raw,Catalog);int ignored=Profiles.IgnoredKeys(raw,Catalog);if(Rows.Any(r=>!r.Valid||r.Value(false)!=r.Baseline||r.Value(true)!=r.Baseline)&&MessageBox.Show("가져온 기준값으로 현재 A/B 시험안을 초기화합니다. 계속할까요?","기준값 가져오기",MessageBoxButton.OKCancel)!=MessageBoxResult.OK)return;updating=true;foreach(var r in Rows){bool found=values.TryGetValue(r.Name,out int v);r.Baseline=found?v:r.Meta.Default;r.BaselineSource=found?Path.GetFileName(file):"누락 · 소스 기본값 대체";r.AText=r.BText=r.Baseline.ToString();}updating=false;label=$"가져옴 {values.Count}/{Rows.Count}개 · 미등록 {ignored}개 제외 · 누락은 기본값";RefreshAll();StatusText.Text=$"기준값 {values.Count}개 가져옴 / 미등록 {ignored}개 제외 / 누락 {Rows.Count-values.Count}개는 소스 기본값. 내보낸 시점을 확인해 주세요.";}catch(Exception ex){updating=false;MessageBox.Show(ex.Message,"가져오기 실패");}}
 void SaveProfile(object sender,RoutedEventArgs e){try{var pack=Pack();var d=new SaveFileDialog{Title="시험안 A/B 저장",Filter="JSON 프로필|*.json",FileName="Carrot-시험안-"+DateTime.Now.ToString("yyyyMMdd-HHmm")+".json"};if(d.ShowDialog()==true){Profiles.WriteAtomic(d.FileName,JsonSerializer.Serialize(pack,Profiles.Json));StatusText.Text="PC 프로필 저장 완료 · 차량에는 적용하지 않았습니다.";}}catch(Exception ex){MessageBox.Show(ex.Message,"저장 실패");}}
 void LoadProfile(object sender,RoutedEventArgs e){var file=OpenJson("시험안 A/B 불러오기");if(file==null)return;try{var raw=ReadLimited(file);using var doc=JsonDocument.Parse(raw);if(doc.RootElement.TryGetProperty("kind",out var kind)&&kind.GetString()=="CarrotTuningProfile"){var values=PortableProfiles.Read(raw,Catalog);updating=true;foreach(var r in Rows)if(values.TryGetValue(r.Name,out int v))r.BText=v.ToString();updating=false;RefreshAll();StatusText.Text=$"HUD 공통 프로필 {values.Count}개를 B로 불러왔습니다. 기준/A/나머지 설정은 유지됩니다.";}else{var pack=Profiles.ReadPack(raw,Catalog);Apply(pack);RefreshAll();StatusText.Text="프로필 불러오기 완료 · 원본 파일 유지";}}catch(Exception ex){MessageBox.Show(ex.Message,"불러오기 실패");}}
 void ExportPortableProfile(object sender,RoutedEventArgs e){try{if(Rows.Any(r=>!r.Valid))throw new InvalidDataException("입력값 확인 필요");string raw=PortableProfiles.Export(Values(true),Catalog);var d=new SaveFileDialog{Title="HUD에서 적용할 종방향 프로필 내보내기",Filter="JSON 프로필|*.json",FileName="Carrot-HUD-시험안B.json"};if(d.ShowDialog()==true){Profiles.WriteAtomic(d.FileName,raw);StatusText.Text="HUD 공통 프로필 내보냄 · 종방향27개 · 차량 적용 전 HUD에서 현재값과의 차이를 확인하세요.";}}catch(Exception ex){MessageBox.Show(ex.Message,"내보내기 실패");}}
 void ExportReport(object sender,RoutedEventArgs e){try{if(CurrentContext() is not Context ctx||Rows.Any(r=>!r.Valid))throw new InvalidDataException("유효한 입력값이 필요합니다.");var d=new SaveFileDialog{Title="종합 설명 저장",Filter="텍스트 보고서|*.txt",FileName="Carrot-설정비교-"+DateTime.Now.ToString("yyyyMMdd-HHmm")+".txt"};if(d.ShowDialog()!=true)return;StringBuilder s=new();s.AppendLine($"Carrot 설정 비교 · 소스 {Catalog.SourceCommit}\n기준: {label}\n차량 전송 없음. 실차 궤적/안전성 예측 아님.\n");foreach(bool b in new[]{false,true}){s.AppendLine(b?"=== 시험안 B ===":"=== 시험안 A ===");foreach(var f in Rules.Explain(Values(b),Baseline(),ctx))s.AppendLine(f.Section+"\n"+f.Text+"\n");}s.AppendLine("=== A/B 차이 ===\n"+DifferenceText.Text+"\n\n=== 전체 설정 및 개별 설명 ===");foreach(var r in Rows)s.AppendLine($"\n{r.Name} / {r.Title}\n기준 {r.Baseline} [{r.BaselineSource}] / A {r.Value(false)} / B {r.Value(true)}\n{r.Description}\nB 조건: {r.State}");Profiles.WriteAtomic(d.FileName,s.ToString());StatusText.Text="전체 설정과 종합 설명을 저장했습니다.";}catch(Exception ex){MessageBox.Show(ex.Message,"보고서 저장 실패");}}
 void ResetBoth(object sender,RoutedEventArgs e){updating=true;foreach(var r in Rows)r.AText=r.BText=r.Baseline.ToString();updating=false;RefreshAll();}
 void CopyAtoB(object sender,RoutedEventArgs e){updating=true;foreach(var r in Rows)r.BText=r.AText;updating=false;RefreshAll();}
}
public static class Program {
 [STAThread]public static int Main(string[] args){
  if(args.Length>0&&args[0]=="--self-test")return SelfTests.Run(args.Length>1?args[1]:"self-test.json");
  try{var app=new Application();if(args.Length>0&&args[0]=="--ui-self-test"){var path=System.IO.Path.GetFullPath(args[1]);var w=new MainWindow(true);var result=w.RunUiSmoke(System.IO.Path.ChangeExtension(path,".png"));Profiles.WriteAtomic(path,JsonSerializer.Serialize(result,Profiles.Json));return 0;}app.Run(new MainWindow());return 0;}
  catch(Exception e){MessageBox.Show(e.ToString(),"Carrot 설정 시뮬레이터 오류");return 1;}
 }
}
