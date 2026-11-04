# Current state

Active product repository. On 2026-11-04 the checkout was moved from a detached older blur-area
commit (`5a47a373`, kept as branch `archive/blur-area-old-base`) to `main`, which is the single
current line: every subtitle/pause option plus the blur area. Work continues on
`claude/single-build-v0.1.2`. The SER9 resolver deployment still pins `1dace256`; `companion/` is
unchanged since then, so it needs no redeploy.

The one distributed build is the signed Libre release `0.1.2`
(`io.github.sommepo.zerifin`, retained release key), offered on the SER9 homepage Downloads page
as `Zerifin-android.apk`. It updates v0.1.1 in place. The earlier debug build
(`io.github.sommepo.zerifin.debug`) is no longer offered; it installs as a separate app.

# Working

- Jellyfin and YouTube playback with interactive Japanese text subtitles.
- Pause on lookup, screen-tap play/pause via the native player lock, EN reveal, previous-line seek,
  a movable learning panel with a configurable hide timeout, and stable paused lookups.
- Local dictionary import/lookup and configurable AnkiDroid card mining.
- The Python YouTube companion; its SER9 systemd instance was active.
- The integrated player has a persisted movable/resizable blur area for obscuring hard-coded
  subtitles. It samples the Media3 video texture on Android 8.0+; Android 6–7 show a black mask.

# In progress

v0.1.2 needs device verification: the blur area on hardware video surfaces and during
rotation/PiP transitions, and an in-place update from v0.1.1. It has not been published as a
GitHub release.

# Known issues

- Interactive lookup does not cover styled ASS or image subtitles.
- YouTube extraction depends on upstream sites/tools and has the limits in `README.md`.
- The full build gate has known nonfatal lint/detekt findings (lint: DirectSystemCurrentTimeMillis,
  DuplicateCrowdInStrings, ImpliedQuantity; detekt: ~750). The one NewApi finding (PiP check on
  Android 6) was fixed for 0.1.2.

# Planned / likely next work

Unknown beyond the documented current limits.

# Important recent architectural decisions

- Zerifin has its own application ID/signing identity and keeps the upstream Kotlin namespace.
- The optional YouTube resolver is a trusted-LAN/tailnet companion, not a public service.
- Android source work and SER9 deployment configuration are kept in separate repositories.
