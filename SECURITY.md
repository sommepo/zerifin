# Privacy and security

## Where data goes

- Jellyfin playback connects to the server you configure, using its normal authenticated APIs.
- Dictionary definitions, frequencies and settings are stored locally on the Android device.
- Word pronunciation is requested from JapanesePod101's public audio service and its HTTPS CDN
  when you play or map it. These requests include the word/reading, never your Jellyfin credentials.
- Mining shares selected text and temporary media with the local AnkiDroid app through its API.
  AnkiDroid controls subsequent collection synchronization.
- YouTube searches and video IDs go to your companion, which contacts YouTube using yt-dlp.
  Direct playback contacts YouTube media hosts. The companion does not use Jellyfin credentials
  or import YouTube cookies.

The YouTube companion has **no account authentication**. Bind it to a trusted LAN, Tailscale or
loopback address. It is not intended to be exposed as a public internet service. Playback sessions
use temporary capability URLs; treat those URLs as private while they are valid.

## Reporting a vulnerability

Use this repository's private vulnerability reporting feature if it is enabled. If it is unavailable,
open an issue asking for a private reporting channel without including exploit details, credentials
or personal data. Do not assume the upstream Jellyfin security team maintains this fork.

There is no guaranteed response time or supported release window while Zerifin is experimental.
