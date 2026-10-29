# Carrot HUD (Android)

A tiny native Android app that shows your comma device's driving‑info HUD on
your phone — as a full‑screen view **and** as an always‑on‑top floating
overlay over other apps (maps, music, …). It also gives one‑tap access to the
full CarrotPilot settings (`:7000`).

It does **not** re‑implement the HUD. It loads the device's own web HUD
(`http://<device-ip>:7000/?view=hud`) inside a WebView, so the numbers always
match the device and stay in sync with any web updates.

This preparation branch adds a boot-started OP connection service and independent
authenticated phone-to-PC uploads over Tailscale. It retains the existing immutable
record archive and ZIP export. See [boot and PC transfer setup](BOOT_PC_SYNC.md).
The archive format is described in [automatic recording](AUTOMATIC_RECORDING.md).
The phone was updated to 1.0.20 on 2026-09-28. Actual phone-to-PC receipt of 23 files
and their hashes was verified. Phone reboot/HyperOS lifecycle validation remains pending.
See [openpilot update setup](OP_UPDATES.md) for the new update reservation screen.

| Button | Action |
|--------|--------|
| **오버레이** | Start the floating always‑on‑top mini‑HUD (asks for "display over other apps" once). Drag to move, ✕ to close. |
| **설정** | Open the full CarrotPilot web app (settings / tools / terminal / logs). |
| **오파 업데이트** | Read release notes, view history, and reserve an update for P/standstill with controls inactive. Initial device-side setup is required. |
| **기기** | Open the device list (add / select / edit / remove devices by IP). |
| **↻** | Reload the HUD. |

---

## Build the APK in the cloud (no tools to install) — recommended

Your PC has no Android build tools, so build it on GitHub for free:

1. Create a new GitHub repository, e.g. `carrot-hud` (private is fine).
2. Put **all these files** in it (drag‑drop upload on github.com works, or `git push`).
3. Open the **Actions** tab → the **Build APK** workflow runs automatically
   (or click *Run workflow*). Wait ~3–5 min.
4. Open the finished run → **Artifacts** → download **CarrotHud-debug-apk**
   → unzip → you get **`app-debug.apk`**.
5. Copy `app-debug.apk` to your phone (USB / KakaoTalk to yourself / Drive),
   open it, allow *"install unknown apps"*, install.

> The build is a normal **debug** APK signed with the standard debug key —
> installable directly, no Play Store needed.

## First run

1. Open **Carrot HUD** → the **기기 선택** (device list) screen.
2. Tap **기기 추가**, enter a name (e.g. 펠리세이드) and the device **IP**
   (same Wi‑Fi — the address shown bottom‑right on the comma screen / your
   router), then save. Add as many devices as you like.
3. Each row shows the device's **online / offline** status (reachable on
   `:7000` — true even when the **car is not connected**, since the device is
   still on Wi‑Fi). Tap a row → HUD; tap the row's **설정** button → go straight
   into the device settings (works with the car off). Long‑press → 수정 / 삭제.
4. In the HUD: **오버레이** → grant *"display over other apps"* → a floating HUD
   stays on top of other apps (drag to move, ✕ to close). **기기** returns to the
   list; **설정** opens the full CarrotPilot web app.

## Updates (in-app)

Each main-branch CI build publishes a **GitHub Release** (tag `v<build#>`, with versionCode =
build#) and attaches the APK. In the app, the **업데이트** button (device-list
header) checks the latest release; if it's newer than the installed version it
downloads and installs it.

Requirements:
- The repo must be **public** so the release + APK are reachable without a token.
- Every build is signed with a **fixed keystore** (`app/keystore.p12`, committed)
  so an update installs over the previous version. **One-time:** if you already
  installed an earlier, differently-signed build, **uninstall it once**, then
  install a release build — after that the 업데이트 button updates in place.

## Alternative: build with Android Studio

Install Android Studio (free), **Open** this folder, let it sync Gradle
(it will download the Gradle/SDK it needs), then **Build ▸ Build APK(s)**, or
press **Run** with your phone connected.

## Notes / limits

- The phone needs a working network route to the comma device (Wi-Fi/hotspot or
  the device's Tailscale address). Completed automatic-recording chunks are kept
  locally in this branch. With automatic connection enabled, the connection service
  and persistent jobs can continue without the HUD/overlay; OS restrictions can delay work.
- HTTP (not HTTPS) is used on the LAN — this is why a native app is needed for
  the always‑on‑top overlay (browsers block that over plain HTTP).
- `minSdk 26` (Android 8.0+), `targetSdk 33`.

## Project layout

```
app/src/main/
  AndroidManifest.xml          permissions (overlay, internet, FGS), activity, service
  java/com/carrot/hud/
    DeviceListActivity.kt      launcher: add / select devices by IP
    MainActivity.kt            full-screen WebView of <device>/?view=hud + controls
    OverlayService.kt          always-on-top floating HUD (SYSTEM_ALERT_WINDOW)
    DeviceStore.kt             stores the device list + the active device
  res/...                      layouts, icon, theme
.github/workflows/android.yml  cloud build -> APK artifact
```


## 튜닝 프로필 · 규격1
메인 화면 **튜닝**에서 후보5종 선택, 변경값 미리보기, P 정차 후 적용·읽기 확인, 적용 전 설정 복원을 제공합니다.
HUD용 JSON은 `CarrotTuningProfile/schema1/familyLX3/scope longitudinal`이며 종방향27개만 포함합니다. PC의184키 A/B 작업 파일과 구분합니다.
Windows는 `pc/tuning_lab`에서 공통 JSON을 불러오고 B값을 HUD용으로 내보냅니다. 실제 차량 시뮬레이션/안전성 판정 도구는 아닙니다.
오파에 `/api/tuning_profiles` 지원 업데이트가 필요합니다. 구버전·롤백에서 미지원이면 적용만 차단하고 기존 HUD는 유지합니다.
미완료 적용은 앱·플로팅 HUD·위젯에 표시하며, 연결 불가 때 경고를 지우지 않습니다. 응답을 잃어도 쓰기를 자동 재전송하지 않습니다.
프로필 적용은 모드3 등 포함된 종방향 값 전체를 지정값으로 맞춥니다. 이후 바꾼 시간거리도 덮어쓸 수 있으므로 실제 변경 목록을 확인하세요.
휴대폰 사용자 프로필은 앱 데이터에, 오파 적용 기록은 `/data/community/tuning_profiles/transaction-v1.json`에 보관하여 소스 업데이트와 분리합니다.


### 통합 강도 조합 (2026-10-29)
다섯 내장 후보는 접근 가속 억제·감속 여유·저속 정차 매끄러움·조건부 간격 유연화·출발 후 추종을 모두 켜고 시간거리·가속 상한·비용 강도를 조합합니다. 각 기능의 발동 조건과 기존 제어 한계는 유지하며 항상 동시에 개입한다는 뜻은 아닙니다.
- 일상 균형 / 매끄러운 전환 / 거리 여유 / 간격 회복 / 기존 거리·순항.
- 마지막 조합은 기존 시간거리 표를 보존합니다. 거리 1단계의 정속 유연화는 1.25초 하한 때문에 대기하고, 고속에서는 기존 속도별 축소와 중복 적용하지 않습니다.
- Windows1.2의 통합 프리셋 메뉴에서 B로 불러오고 설계 의도와 조건을 읽을 수 있습니다. 휴대폰 내보내기/가져오기와 규격1을 공유하며 예전 사용자 파일은 보존합니다.
- 이름은 목표입니다. PC 비교에서는 일부 상황의 jerk가 증가했고 첫 움직임 지연은 같았습니다. 앱 설명에 불리한 비교 지표도 표시합니다. 실차 적합성을 입증하지 않았고 업데이트 후 자동 적용하지 않습니다.
- 변경은 프리셋/클라이언트 표시/호환성 시험뿐입니다. 오파 프로필 API20을 이미 설치했다면 이 후보를 쓰기 위해 조향·제동 제어코드를 다시 설치할 필요가 없습니다.


### 위젯 프로필 빠른 선택
홈 HUD 위젯·운행모드 위젯의 `프로필 선택 ▾` 또는 플로팅 HUD의 프로필 줄을 누르면 같은 선택창을 엽니다. 프로필 행을 누르면 추가 확인창 없이 바로 미리보기 검증·적용을 요청합니다. 창을 열거나 위젯을 새로고침하는 것만으로 설정을 쓰지 않습니다. 기존 운행모드 전환 클릭은 보존합니다.
- P·속도0·조향/가감속 해제 및 기기 식별·버전·단위·범위 검사는 그대로입니다. 주행 중 요청을 예약하거나 나중에 자동 적용하지 않습니다.
- 동일 요청1회, POST전 기기별 영구 기록, 적용 후 우리 완료 기록+저장값을 읽어 확인합니다. 응답 유실은 GET으로만 확인하고 재POST하지 않습니다.
- 화면과 위젯에는 요청 중/저장 확인/거절/미확인을 구분합니다. 시간 표시된 마지막 확인이며 실시간 제어 적용·주행 품질 증명이 아닙니다.
- 내장/사용자 프로필을 공유합니다. 이전 요청이 불확실하면 결과를 읽기만 할 수 있고, 전체 메뉴에서 복구 또는 원본을 보존한 로컬 표시 정리가 가능합니다. 로컬 표시 정리는 차량 설정 변경·전송 요청 취소가 아닙니다.
- 새 후보 비교 시작점은01 일상 균형입니다. 최적 또는 실차 개선이 입증된 추천이 아니며, 첫 움직임 지연은 PC비교에서 개선되지 않았습니다.

### 오파 Wi-Fi 자동 준비·주행 후 주차 설치
오파 업데이트 화면에서 기기 자동 준비를 켜면, 폰의 설치 버튼을 누르지 않아도 기기가 Wi-Fi(휴대폰 핫스팟 포함)에서15분 간격·재연결 시 게시 버전을 확인하고 서명된 파일을 캐시합니다. 주행 중에는 실행 파일 교체·백업·재부팅을 하지 않습니다. 캐시는 설치 때 서명·순번·선택 버전·현재 파일 해시를 다시 확인합니다.
- 자동 예약은 해당 차량 시동에서 실제 주행이 관측된 뒤 P·속도0·제어해제가10초 유지되고 취소 안내10초가 지난 뒤 적용합니다. 시동 직후 P 대기만으로는 자동 재부팅하지 않습니다. 설치 버튼을 직접 누르면 같은 고정 버전을 수동 예약으로 전환할 수 있습니다.
- 예약 취소는 같은 버전과 그보다 오래된 버전을 자동으로 재예약하지 않도록 기록합니다. 자동 준비 끄기는 자동 예약을 취소하지만 기존 수동 요청까지 지우지 않습니다. 실패·실행 확인 경고·복원 처리 중에는 자동으로 다른 버전을 덮어 예약하지 않습니다.
- 이 기능은 **업데이트기 자체의 최초 갱신**이 필요합니다. 기존 업데이트기에서는 자동 스위치를 사용할 수 없다고 표시하며 수동 기능은 유지합니다. 업데이트기 자기 자신은 일반 소스 OTA 허용 목록에 넣지 않고, 별도로 서명·검증한 정차 설치 도구를 사용합니다.
- 업데이트와 프로필 적용은 별개입니다. 개인 설정·프로필·적용 기록·기존 업데이트 복원 자료를 보존하며, 소프트웨어 갱신 후 튜닝 프로필을 자동 재적용하지 않습니다.
