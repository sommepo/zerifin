# Current state

Active product repository. On 2026-11-04 the checkout was moved from a detached older blur-area
commit (`5a47a373`, kept as branch `archive/blur-area-old-base`) to `main`, which is the single
current line: every subtitle/pause option plus the blur area. Work continues on
`claude/single-build-v0.1.2` (0.1.2 consolidation, then the 0.1.3 features). The SER9 resolver deployment still pins `1dace256`; `companion/` is
unchanged since then, so it needs no redeploy.

The one distributed build is the signed Libre release (`io.github.sommepo.zerifin`, retained
release key), offered on the SER9 homepage Downloads page as `Zerifin-android.apk`; it updates
v0.1.x in place. 0.1.3 (2026-11-04) added ASS lookup, the subtitle line list, known-word tinting,
quick/per-show blur, offline mining and the update check. GitHub's latest release is still
v0.1.1. The earlier debug build (`io.github.sommepo.zerifin.debug`) is no longer offered; it
installs as a separate app.

# Working

- Jellyfin and YouTube playback with interactive Japanese text subtitles.
- Pause on lookup, screen-tap play/pause via the native player lock, EN reveal, previous-line seek,
  a movable learning panel with a configurable hide timeout, and stable paused lookups.
- Local dictionary import/lookup and configurable AnkiDroid card mining.
- The Python YouTube companion; its SER9 systemd instance was active.
- The integrated player has a persisted movable/resizable blur area for obscuring hard-coded
  subtitles. It samples the Media3 video texture on Android 8.0+; Android 6–7 show a black mask.
  Long-press its button or tap with two fingers to toggle; area and on/off are kept per series.
- ASS/SSA dialogue is tappable (bottom-centred cues; signs stay on Media3's renderer). The
  subtitle MIME type follows the delivery URL, so server-converted ASS→SRT parses correctly.
- Subtitle line list (whole Japanese timeline, tap to seek) and known-word tinting from the Anki
  note type's Word field plus an imported word list.
- Downloads save Japanese/English/untagged text subtitles as WebVTT (`DownloadFileType.SUBTITLE`);
  offline mining reads them and decodes sentence audio from the file with MediaCodec.
- Release builds check GitHub releases (or a custom downloads `index.json`) at most daily.

# In progress

0.1.3 was smoke-tested on the AYN Thor (Android 13) via YouTube only (the Thor is not signed in
to Jellyfin): launch, subtitle line list and seek, long-press blur toggle, word lookup. Not yet
verified on a device: ASS lookup on a real Jellyfin MKV, offline download subtitles and local
sentence audio, known-word tinting with a configured Anki note type, the two-finger tap (adb
cannot send multi-touch), the update prompt, blur on rotation/PiP, and updating from v0.1.1.

# Known issues

- Interactive lookup does not cover image subtitles, ASS signs/vertical text, or ASS while the
  libass renderer option is on. Downloads made before 0.1.3 have no saved subtitles.
- YouTube extraction depends on upstream sites/tools and has the limits in `README.md`.
- The full build gate has known nonfatal lint/detekt findings (lint: DirectSystemCurrentTimeMillis,
  DuplicateCrowdInStrings, ImpliedQuantity; detekt: ~750). The one NewApi finding (PiP check on
  Android 6) was fixed for 0.1.2.

# Planned / likely next work

Device checks above. Publish 0.1.3 as a GitHub release so the default update source offers it.

# Important recent architectural decisions

- Zerifin has its own application ID/signing identity and keeps the upstream Kotlin namespace.
- The optional YouTube resolver is a trusted-LAN/tailnet companion, not a public service.
- Android source work and SER9 deployment configuration are kept in separate repositories.
