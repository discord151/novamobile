# Nova Mobile (earbud triple-click -> voice call, works on lock screen)

Triple-click your earbuds -> beep -> say "call <contact name>" (or "call nine eight ...").
The phone is NOT unlocked; only the call is placed. Nothing listens until you click.

## Setup
1. Open this folder in Android Studio and let Gradle sync.
2. Download https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   Unzip, copy the CONTENTS into app/src/main/assets/model-en-us/ and add a text file
   named `uuid` there (any content). Delete README_PUT_MODEL_HERE.txt.
3. Run on the phone. Tap Start and grant permissions.
4. Tap "Learn my earbud triple-click", then triple-click the earbuds when it asks.
   (Do this in HeyMelody first: set triple-tap to a media action like Previous track,
   NOT "voice assistant", so the phone receives a normal media key.)
5. Tap "Allow background running". Lock the app in Recents.

## Notes
- After reboot: open the app once and tap Start.
- If commands sound cut off or are not recognised via the buds mic, set USE_BUDS_MIC = false
  in ListenerService.kt to use the phone mic instead.
- `adb logcat -s NovaMobile` shows each earbud key and what Vosk heard.
- If music apps stop reacting to play/pause, set KEEP_SILENT_AUDIO = false.

## No Android Studio? Build the APK on GitHub
Push this folder to a GitHub repo -> Actions tab -> "Build APK" (runs automatically) ->
open the finished run -> download the `NovaMobile-debug-apk` artifact -> unzip -> install app-debug.apk.
The workflow downloads the Vosk model for you, so skip step 2 of Setup.
