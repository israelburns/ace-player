# HANDOFF — Ace Player Background Playback BROKEN
**Date:** 2026-03-29
**From:** Claude (The Engineer)
**To:** Droid / Next Agent
**Priority:** FIX THIS — app is broken for Ace

---

## THE PROBLEM
Music stops playing when the app goes to background (switch apps) or screen turns off.
The app process stays alive (PID persists), but the WebView renderer freezes and audio stops.
**This USED TO WORK.** Same code (v3.3.0) was deployed and working. Something external changed.

## WHAT WE TRIED (ALL FAILED)
1. **onStop() resume hack** — `webView.onResume()` + JS re-trigger play → didn't help
2. **Keep-alive ping loop** — 5s interval `evaluateJavascript` from coroutine → didn't help
3. **Samsung battery exemptions via ADB:**
   - `dumpsys deviceidle whitelist +com.yourapp.youtubeplayer` → added ✓
   - `cmd appops set RUN_IN_BACKGROUND allow` → set ✓
   - `cmd appops set RUN_ANY_IN_BACKGROUND allow` → set ✓
   - `am set-standby-bucket active` → set ✓
   - `cmd appops set TAKE_AUDIO_FOCUS allow` (was foreground-only) → set ✓
   - `cmd appops set CONTROL_AUDIO allow` (was foreground-only) → set ✓
4. **PiP (Picture-in-Picture)** — works technically but Ace doesn't want the PiP behavior, reverted
5. **Reverted to last known working commit (dc77692)** — STILL broken, confirming code isn't the issue

## ROOT CAUSE THEORY
- **WebView version 146.0.7680.120** — very recent update, likely changed background media policy
- Samsung One UI sets WebView frame rate to `-4.0` (frozen) when app goes to background
- `setRendererPriorityPolicy(IMPORTANT, false)` is already set — Samsung ignores it
- The app has PARTIAL_WAKE_LOCK in both Activity and Service — not enough
- Foreground service with mediaPlayback type is running — Samsung still kills WebView audio

## ARCHITECTURE (important for fix)
```
PlayerHostActivity (WebView)
    ↓ loads
https://ace-taskmaster.duckdns.org/player (HTML5 player)
    ↓ plays audio via
<audio> element (useDirectAudio=true, primary) OR YouTube IFrame API (fallback)
    ↓ communicates with
PlaybackService (MediaLibraryService, foreground service, media session, Android Auto)
    ↓ via
JS Bridge (AndroidBridge) + PlaybackService.commandListener callback
```

The audio lives INSIDE the WebView. When Samsung freezes the WebView renderer, audio dies.

## THE REAL FIX (not attempted yet)
**Move audio playback OUT of WebView and into native ExoPlayer/Media3.**

The player HTML already extracts direct audio URLs (`ap.src = d.url`). The fix:
1. When JS gets an audio URL, pass it to Android via `AndroidBridge.playNativeAudio(url)`
2. PlaybackService plays the URL via ExoPlayer (Media3 already in dependencies)
3. WebView stays for UI only — playlist browsing, track selection, visualizations
4. ExoPlayer in a foreground service = bulletproof background playback on any Samsung

This is the approach Spotify, YouTube Music, etc. all use. WebView for UI, native for audio.

## KEY FILES
- `app/src/main/java/com/yourapp/youtubeplayer/ui/PlayerHostActivity.kt` — WebView host
- `app/src/main/java/com/yourapp/youtubeplayer/service/PlaybackService.kt` — Media service
- `app/src/main/java/com/yourapp/youtubeplayer/player/StateProxyPlayer.kt` — Media3 proxy
- `app/src/main/AndroidManifest.xml` — permissions, service declarations
- Server: `ssh -i ~/.ssh/gcp_ace_key ace@35.209.155.144` → `/var/www/html/player.html`
- Player URL: `https://ace-taskmaster.duckdns.org/player`

## ADB CONNECTION
```bash
adb connect 10.0.0.144:5555
# Device: Samsung Galaxy S25 (SM_S921U)
# Package: com.yourapp.youtubeplayer
# Build: JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
# Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
# Launch: adb shell "am start -n com.yourapp.youtubeplayer/.ui.PlayerHostActivity"
# Logs: adb logcat --pid=$(adb shell pidof com.yourapp.youtubeplayer)
```

## CURRENT STATE
- Code is reverted to v3.3.0 (last known good commit dc77692)
- All Samsung ADB exemptions are set
- Nginx `/player` route is fixed and working
- App loads and plays fine — just dies on background

## ALSO IN THIS SESSION
- Filed Samsung TV repair: Ticket #4184552409 (98" DU9000, April 3 appointment)
- Repo: israelburns/samsung-tv-repair (private)
