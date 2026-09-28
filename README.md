# PlayInstaller

Tiny root APK installer with Google Play as both initiator and installer.

Supports APK, APKS, APKM, and XAPK.

## Commands

```sh
su UID -c "CHECK_UID; exec pm install-create -r -i com.android.vending --user 0 -S SIZE"
pm install-write -S SIZE SID app.apk APK
su UID -c "CHECK_UID; exec pm install-commit SID"
```

UID, SIZE, SID, and paths are runtime values. CHECK_UID verifies that su is actually running as the Play Store UID.

## Build

```sh
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## License

Copyright (C) 2026 alltechdev. [GPL-3.0](LICENSE). Forks and apps that bundle this code must publish their source under the same license.
