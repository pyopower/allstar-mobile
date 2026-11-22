# Google Play – Mobile ASL Client

Hand-off for publishing. Everything below is ready except what is marked **TODO**.

## Build

- Package: `ovh.adan.allstar` · version **1.0.0 (versionCode 1)** · minSdk 24 · targetSdk 36
- AAB: `./gradlew bundleRelease` on the dl360 → `app/build/outputs/bundle/release/app-release.aab`
  (copy kept as `out/mobile-asl-client-1.0.0.aab` on the raspi).
- Upload key (Play App Signing: Google keeps the app signing key): dl360 `~/keystores/allstarmobile/upload.jks`,
  alias `allstarmobile`, passwords in `passwords.txt` next to it (also in `~/.gradle/gradle.properties`).
  Upload certificate SHA-256: `CA:27:C8:EE:97:AC:F5:A6:BD:9E:C5:75:21:4B:35:D3:D0:2D:17:16:68:EF:BA:0D:2E:EC:67:2F:F6:8F:53:FB`
- Release build is minified (R8). Tested on the ZUK (Android 15): IAX connect, node list, EchoLink list.

## Console (web) – only once

1. **Create app**: name *Mobile ASL Client*, default language **es-ES**, App, Free.
2. After that, the API (service account `fastlane@adn-play-api-16645…`, whole-account access) can do the rest:
   - listings: `python3 ~/adn-poc-playapi.py listings ovh.adan.allstar <repo>/fastlane/metadata/android --commit` (dl360)
   - AAB: `python3 ~/adn-poc-playapi.py upload ovh.adan.allstar <aab> production es-ES "Primera versión." --commit`
   - Images (icon, feature graphic, screenshots) are in `metadata/android/<lang>/images/`; upload in the web UI
     if the script does not handle images.

## Store listing

- Titles / short / full descriptions: `metadata/android/{en-US,es-ES,pt-PT,it-IT,fr-FR,de-DE}`
- Icon 512×512, feature graphic 1024×500, 5 phone screenshots per language (es-ES in Spanish, the rest in English).
- Category: **Communication**. Tags: amateur radio, AllStarLink, EchoLink.
- **TODO** contact e-mail (and optional website: the GitHub repo once it is public).
- Privacy policy: **https://descargas.adan.ovh/mobile-asl-privacy.html** (source: `fastlane/privacy-policy.html`).

## App content

- Ads: **No**.
- Target audience: **18+** (licensed radio amateurs).
- Content rating questionnaire: category *Communication*; users can communicate with each other (voice over
  amateur radio links); no violence, sexual content, gambling, etc.; no user-generated content shared publicly by the app.
- News app: No. Government app: No. Financial features: None. Health: No.
- **Data safety**:
  - Collected: *Audio → Voice or sound recordings*: processed ephemerally (streamed live to the user's own node,
    not stored), not shared with third parties, required for the app to work, purpose *App functionality*.
  - Nothing else is collected (no account, no analytics, no crash reporting, no location).
  - Encrypted in transit: **No** (IAX2 is not encrypted).
  - Users cannot request deletion because nothing is stored by the developer.
- **App access** (**TODO**, decide): connecting needs the user's own AllStarLink node. The node list, live status,
  EchoLink list and settings work without any login. Either explain that, or give reviewers a demo IAX login.
- **Foreground service declarations** (Android 14+), with a short video for each (**TODO** record):
  - `microphone`: "Push-to-talk voice to the user's AllStarLink node keeps working when the app is in the
    background or the screen is off (hardware/Bluetooth PTT buttons)."
  - `mediaPlayback`: "Continuous playback of the radio audio received from the user's AllStarLink node while the
    app is in the background or the screen is off."
  - `specialUse` (subtype in the manifest): "Optional 'start when the phone is switched on': at boot the app opens
    the IAX link to the user's own node in listen-only mode; Android does not allow microphone/media services
    from BOOT_COMPLETED. The microphone is added the first time the user opens the app."
- Permissions used: RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE(+_MICROPHONE, _MEDIA_PLAYBACK,
  _SPECIAL_USE), RECEIVE_BOOT_COMPLETED, WAKE_LOCK, INTERNET, ACCESS_NETWORK_STATE, MODIFY_AUDIO_SETTINGS.
  Cleartext HTTP is allowed because Allmon3 on a home LAN is usually plain `http://`.

## Before publishing

- **TODO** make the GitHub repo public: the one-command node setup shown in the app downloads
  `tools/setup-node.sh` from `raw.githubusercontent.com`, which fails while the repo is private.
- The developer account is old (same as ADN-SYSTEMS LIVE): no mandatory 14-day closed test, production is allowed.
