# DeadData

DeadData is an open-source Android client for browsing public Deadlock player and
match data.

## Features

- Search by SteamID64, Steam profile URL, or Deadlock account ID.
- Keep multiple account records locally with notes and a default account.
- Player overview with recent win rate, K/D/A, hero damage, rank, and match count.
- Recent matches with Chinese win/loss badges, hero, mode, duration, and K/D/A.
- Match details with both teams, player profiles, heroes, levels, economy, K/D/A,
  last hits, denies, MVP rank, damage, healing, and other available fields.
- Hero statistics aggregated from in-game match data, including K/D/A, damage,
  damage taken, per-minute values, accuracy, critical-hit rate, and MVP counts.
- In-app updates from the latest GitHub Release.

Account records are stored only in the Android app's private preferences. The app
does not store historical match snapshots, Steam credentials, or a remote account
list.

## Data Source

DeadData reads public data from the community-maintained
[`deadlock-api.com`](https://deadlock-api.com/) API. It is not a Valve service.
The available fields and match-history coverage depend on that API.

Steam vanity profile links are resolved directly by the app through Steam's
public profile page. DeadData has no server component and does not implement
Steam login or account binding.

## Build

Requirements:

- Android Studio with Android SDK 36
- JDK 17 or newer

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

The app requires Android 8.0 or newer.

## Release And In-App Updates

The app checks:

```text
https://api.github.com/repos/DDWking/DeadData/releases/latest
```

Each public release must contain:

- `DeadData-X.Y.Z.apk`
- `update.json`

`update.json` has this shape:

```json
{
  "versionCode": 9,
  "versionName": "1.4.0",
  "apkUrl": "https://github.com/DDWking/DeadData/releases/download/v1.4.0/DeadData-1.4.0.apk",
  "sha256": "64-character-lowercase-sha256",
  "releaseNotes": "Release notes"
}
```

To preserve upgrade compatibility, releases must keep:

- application ID `app.deaddata.mobile`
- the same release signing certificate
- a larger `versionCode`

The signing keystore and `release-signing.properties` are intentionally excluded
from this repository. The included GitHub Actions workflow expects these secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create a tag such as `v1.4.0` after updating `versionCode` and `versionName`.
The workflow builds the signed APK, creates `update.json`, and publishes both
files to the GitHub Release.

For a local release, copy
`release-signing.properties.example` to `release-signing.properties`, fill in
the private values, and run:

```powershell
.\gradlew.bat assembleRelease
```

Never commit the keystore, signing properties, or APK files.

## License

MIT. See [LICENSE](LICENSE).
