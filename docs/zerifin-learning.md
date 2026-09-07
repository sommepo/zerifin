# Zerifin learning workflow

Zerifin keeps the existing Jellyfin server and Android installation compatible. The client name,
connection screen, launcher, splash screen and web shell use Zerifin with a light-green-to-emerald
logo gradient. The package remains `org.jellyfin.mobile.debug` for the debug build. Server protocol
names, legal acknowledgements, upstream links and library content are preserved.

## Setup and use

1. Open **Dictionaries** directly from the main side menu. Import a Yomitan term ZIP, then any
   frequency ZIPs with the toolbar **+**. Term and Frequency tabs list installed dictionaries with
   enable switches. Reimporting preserves each dictionary’s enabled state. Importing a title again
   replaces that dictionary atomically. Existing installed definitions survive the database upgrade.
2. Open **Anki mining** beside it. Choose an existing deck and note type, then map the fields once.
   The connection panel verifies local AnkiDroid access and shows the last card error. AnkiConnect
   is not required. The full-screen form uses rounded selectors; tap a row to choose its value, then **Save**.
   Word is recommended for the first field because AnkiDroid uses that field for duplicate checks.
   [The mapping guide](anki-mining.md) lists every available value.
3. Play a video in the integrated player with Japanese SRT or simple WebVTT subtitles. Tap a word
   to pause and open the anchored dictionary. The card shows definitions, readings, dictionary names,
   frequencies and a book icon for a known Anki word. Use the pronunciation button to hear word audio or **+** to
   mine the selected definition.

The compact popup stays hidden while lookup is pending.
Lookup pauses by default without opening player controls. **Dictionaries → Subtitle popup** contains
**Pause on lookup** and **Popup appearance** (System, Light, Dark); System is the default. Disabling
pause keeps playback running while preserving the tapped sentence for mining.

The player’s **EN** button reveals the current English subtitle and remains in the same upper-right
position during word lookup. Tap it again to hide the translation. English text follows playback or
the frozen lookup position and uses a small on-demand memory cache.

Closing the popup resumes playback only when the lookup paused it. Selecting another word replaces
the card and stops the previous pronunciation. Audio callbacks are tied to the active card. No new
track-selection, clipping, caching or dictionary-category settings are required.

## Data and performance

Definitions and frequency data live in app-private SQLite with expression/reading indexes. ZIP
imports are bounded and transactional; a malformed import preserves all installed dictionaries.
Frequency metadata supports numeric values, formatted display labels and reading-specific values,
including both rank and occurrence ordering. Candidate generation handles common conjugations and
Unicode normalization while retaining original subtitle offsets for highlights.

Lookup performs no network audio request. Pronunciation is fetched anonymously from JapanesePod101
only when played or mapped for mining, and is cached privately with a 16 MiB bound. No Jellyfin
credentials accompany those requests. Missing-audio responses are rejected instead of attached to
cards. Structured glossary content becomes readable paragraphs and lists; ruby readings and image
alternative text remain available as text. Embedded dictionary images, pitch/kanji metadata, offline
pronunciation databases and full morphological analysis are not implemented.

Media mining uses the original cue's timing. [YouTube](youtube.md) uses the same lookup and mining
flow through an optional companion; confirm Japanese speech when YouTube leaves audio unlabelled.
For Jellyfin streams, the player automatically chooses a non-forced English
track when present and a track explicitly tagged Japanese for sentence audio. A dubbed or untagged
track is never substituted. Translation is drawn from substantially overlapping cues. Sentence clips
request bounded raw 16-bit mono PCM from the connected Jellyfin server and wrap the exact sentence
in a local WAV file. This also works around older server PCM header/sample-rate bugs, with a separate
encoding session and cancellation cleanup. Pictures capture the video surface without controls or
the dictionary overlay. Only mapped media is prepared.

Temporary mining files are shared with AnkiDroid through a narrow, unexported FileProvider and deleted
after the request. No authenticated URLs, access tokens or server responses enter card fields or
logs. AnkiDroid imports media into its own collection for playback and synchronization.

## Current limits

- Tap lookup still requires ordinary centered text cues in the integrated player; styled/positioned
  ASS and image subtitles retain the normal renderer.
- Sentence timing, translation and sentence audio require a Jellyfin stream or resolved YouTube source.
  Offline downloaded media does not yet provide these values. A sentence audio clip is at most
  30 seconds. Unknown Japanese audio language tags leave the field unavailable.
- A picture needs an available video surface and Android 7+ for SurfaceView capture. A changed frame
  after an interrupted permission flow is not substituted. TextureView capture is also supported.
- The app reports unavailable optional mapped content when a note is added. Failed extraction or
  Anki media import prevents note creation. AnkiDroid's provider cannot roll back an earlier media
  import if a later import or note insert fails.
- Main-menu integration is local to the embedded web client and handles current drawer rebuilding.
  A server web-client redesign that removes its navigation markers may require an adapter update.

## Authoritative API references

- [AnkiDroid API](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API)
- [Jellyfin subtitle controller](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/SubtitleController.cs)
- [Jellyfin audio controller](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/AudioController.cs)
- [Android PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
- [Yomitan dictionary format](https://yomitan.wiki/development/dictionaries/)
