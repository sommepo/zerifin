# Current state

Active product repository. The checkout was clean but detached at `1dace256`
on 2026-09-07; that commit is also pinned by the SER9 resolver deployment.

# Working

- Jellyfin and YouTube playback with interactive Japanese text subtitles.
- Local dictionary import/lookup and configurable AnkiDroid card mining.
- The Python YouTube companion; its SER9 systemd instance was active.
- The integrated player has a persisted movable/resizable blur area for obscuring hard-coded
  subtitles. It samples the Media3 video texture on Android 8.0+; Android 6–7 show a black mask.

# In progress

Blur-area implementation is present locally and needs device verification, especially on hardware
video surfaces and during rotation/PiP transitions.

# Known issues

- Interactive lookup does not cover styled ASS or image subtitles.
- YouTube extraction depends on upstream sites/tools and has the limits in `README.md`.
- The full build gate has known nonfatal lint/detekt findings.

# Planned / likely next work

Unknown beyond the documented current limits.

# Important recent architectural decisions

- Zerifin has its own application ID/signing identity and keeps the upstream Kotlin namespace.
- The optional YouTube resolver is a trusted-LAN/tailnet companion, not a public service.
- Android source work and SER9 deployment configuration are kept in separate repositories.
