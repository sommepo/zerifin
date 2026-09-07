# Building Zerifin

## Requirements

- JDK 21 (`.tools-versions`).
- Android SDK Platform 37 and SDK command-line/build tools. The SDK manager may present the
  platform package as `platforms;android-37.0`.
- A supported Android Studio installation, or a shell with `JAVA_HOME` and `ANDROID_HOME` set.
- Access to the dependency repositories on the first build.

The Gradle wrapper and dependencies are pinned in this repository. Do not substitute a globally
installed Gradle. Set the SDK location through `ANDROID_HOME` or your own untracked `local.properties`.

## Build and install

From your clone's root:

```sh
./gradlew assembleLibreDebug
./gradlew installLibreDebug
```

The APK is under `app/build/outputs/apk/libre/debug/`. Use `assembleProprietaryDebug` for the build
with Chromecast. The default version is `0.0.0-dev.1`; the upstream-compatible version property is
still named `jellyfin.version`, for example `./gradlew -Pjellyfin.version=0.1.0 assembleLibreDebug`.

The app label is Zerifin. The debug application ID remains `org.jellyfin.mobile.debug`; release uses
`org.jellyfin.mobile`. Application ID migration is deliberately outside this publication change.
Different signing keys cannot update the same installed application. Keep your local debug key
if you need to update your own development installation without removing its data.

## Checks

```sh
./gradlew test detekt lintLibreDebug assembleLibreDebug
python3 -m unittest discover -s companion/youtube -v
./scripts/check-web-menu.sh
```

The Python tests use the standard library and mocked extraction; they do not need YouTube access,
yt-dlp or ffmpeg. The menu checker downloads a checksum-pinned DOM test dependency to a temporary
directory and removes it afterward.

The repository currently has nonfatal lint/detekt findings, including existing translation
quantity errors. Task success does not mean those reports are empty. See `app/build/reports/`.

GitHub Actions runs the Android gate, companion tests and menu checks. Android artifacts are
**unsigned release APKs**, intended for inspection or signing locally. The workflow does not publish
releases, use repository secrets or upload anything to Jellyfin infrastructure.

## Release signing

Stable signed releases are not configured yet. `assembleLibreRelease` produces an unsigned APK
unless all four signing properties are supplied. Existing Gradle support accepts these environment
variables: `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Keep keys outside the repository and supply secrets through a secure local environment or a future
maintainer-controlled release job. Never distribute a signing key or include it in a source archive.

A public release needs a maintained signing identity, a chosen version and update testing. Do not
present a newly generated runner debug key as an update channel for existing installations.
