# Contributing to Zerifin

Keep the app focused on watching Japanese video, looking up subtitles and mining useful cards.
Prefer small changes and reuse the existing player, subtitle renderer and Anki workflow. Discuss
larger changes in this fork's issue tracker before starting them.

## Changes and tests

1. Build the libre debug variant using [the build guide](docs/building.md).
2. Keep ordinary Jellyfin playback working as well as the learning features.
3. Add focused tests for behavior changes. For player changes, check seeking, pause/resume, subtitle
   selection, lookup dismissal and returning to the previous screen on a device.
4. Run `./gradlew test detekt lintLibreDebug assembleLibreDebug`. For companion changes, also run
   `python3 -m unittest discover -s companion/youtube -v`.
5. Explain the problem, resulting behavior and validation in the pull request. Mention any parts you
   could not test. Existing lint/detekt findings are nonfatal; avoid adding unrelated formatting changes.

Never commit signing keys, credentials, dictionary archives, private videos, app databases or full
device logs. Use small synthetic fixtures or public examples you are permitted to share. Keep
upstream copyrights and dependency license notices intact.

## Reporting issues

Use this fork's issue forms. Include the Zerifin version, Android version, relevant media/subtitle
formats and reproducible steps. A minimal public video or subtitle example is more useful than a
full private library export. Use a redacted error message rather than an entire logcat dump.

For behavior that also occurs in the unmodified official client, an upstream report may be
appropriate after confirming it there. Do not send Zerifin-only problems to the Jellyfin maintainers.
