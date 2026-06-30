# Carrot HUD (Android)

A tiny native Android app that shows your comma device's driving‑info HUD on
your phone — as a full‑screen view **and** as an always‑on‑top floating
overlay over other apps (maps, music, …). It also gives one‑tap access to the
full CarrotPilot settings (`:7000`).

It does **not** re‑implement the HUD. It loads the device's own web HUD
(`http://<device-ip>:7000/?view=hud`) inside a WebView, so the numbers always
match the device and stay in sync with any web updates.

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
3. Tap a device → its HUD opens. (Long‑press a row for 수정 / 삭제 / 설정 열기.)
4. In the HUD: **오버레이** → grant *"display over other apps"* → a floating HUD
   stays on top of other apps (drag to move, ✕ to close). **기기** returns to the
   list; **설정** opens the full CarrotPilot web app.

## Alternative: build with Android Studio

Install Android Studio (free), **Open** this folder, let it sync Gradle
(it will download the Gradle/SDK it needs), then **Build ▸ Build APK(s)**, or
press **Run** with your phone connected.

## Notes / limits

- The phone must be on the **same network** as the comma device (Wi‑Fi /
  hotspot). The app only displays the device's web HUD; it does not store data.
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
