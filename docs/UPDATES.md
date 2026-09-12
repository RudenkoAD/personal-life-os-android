# Android updates

The updater reads `GET /android/latest.json` from the configured service origin. The response is public and contains no account data:

```json
{
  "schemaVersion": 1,
  "versionCode": 3,
  "versionName": "0.3.0",
  "apkPath": "/android/releases/3/Life-OS-0.3.0.apk",
  "sha256": "64 lowercase hexadecimal characters",
  "sizeBytes": 12345678,
  "minSdk": 26
}
```

`apkPath` is relative to the service origin and must remain an HTTPS same-origin path. Android checks the package and version, downloads the signed APK, verifies the declared SHA-256 and size, and lets the platform installer enforce the existing signing key. The signing keystore is never uploaded or stored in this repository.

Prepare a release without building it:

```sh
scripts/prepare-update.py app/build/outputs/apk/release/app-release.apk
```

The script requires `aapt2` (or `aapt`) and `apksigner` from the Android SDK. It writes `outputs/android-update/latest.json` and `outputs/android-update/releases/<versionCode>/Life-OS-<versionName>.apk`.

Publication is a separate explicit action. It uses the configured SSH client and an external deployment key; it does not print key contents:

```sh
scripts/publish-update.sh --artifact app/build/outputs/apk/release/app-release.apk --output outputs/android-update
scripts/publish-update.sh --publish --artifact app/build/outputs/apk/release/app-release.apk --output outputs/android-update --host HOST --user USER --ssh-key /path/to/deploy-key --known-hosts /path/to/known_hosts
```

The remote root is `/srv/personal-life-os/android`. An administrator must create it, its `releases/` subdirectory, and grant the deployment user write access before the first publication; the Caddy container only needs read access. Publication rejects a decreasing `versionCode`, replacing an immutable version with a different digest, and partial metadata. It leaves older versioned APKs in place and replaces `latest.json` atomically. The Yandex Caddy container mounts that directory read-only at `/srv/android`; the route is configured in `deploy/yandex/Caddyfile`.
