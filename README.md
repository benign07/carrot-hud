# Carrot HUD (Android)

A tiny native Android app that shows your comma device's driving‑info HUD on
your phone — as a full‑screen view **and** as an always‑on‑top floating
overlay over other apps (maps, music, …). It also gives one‑tap access to the
full CarrotPilot settings (`:7000`).

It does **not** re‑implement the HUD. It loads the device's own web HUD
(`http://<device-ip>:7000/?view=hud`) inside a WebView, so the numbers always
match the device and stay in sync with any web updates.

This preparation branch also archives completed passive driving records while
the HUD or overlay is in use. Transfers resume after reconnecting and are verified
by length and SHA-256. Use **기록 내보내기** to export a ZIP for PC analysis.
See [automatic recording details and limits](AUTOMATIC_RECORDING.md).
It does not change steering/braking settings. Installation and phone validation
are pending; the device-side automatic recorder is required.

| Button | Action |
|--------|--------|
| **오버레이** | Start the floating always‑on‑top mini‑HUD (asks for "display over other apps" once). Drag to move, ✕ to close. |
| **설정** | Open the full CarrotPilot web app (settings / tools / terminal / logs). |
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

Each CI build publishes a **GitHub Release** (tag `v<build#>`, with versionCode =
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
  locally in this branch. Closing both HUD and overlay pauses phone transfers;
  device recording continues, and reopening catches up.
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
