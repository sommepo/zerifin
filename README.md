<p align="center">
  <img src="app/src/main/assets/native/zerifin-logo.svg" alt="Zerifin" width="128" />
</p>
<h1 align="center">Zerifin</h1>
<p align="center"><strong>Japanese subtitle lookup and Anki mining for Jellyfin and YouTube.</strong></p>
<p align="center">
  <a href="#get-started">Get started</a> ·
  <a href="docs/anki-mining.md">Anki setup</a> ·
  <a href="docs/youtube.md">YouTube setup</a> ·
  <a href="docs/building.md">Build from source</a>
</p>

Zerifin is an independent fork of [Jellyfin for Android](https://github.com/jellyfin/jellyfin-android)
for learning Japanese while watching videos. Tap a subtitle word, look it up, hear its pronunciation,
and save it to AnkiDroid with the sentence and media.

## What's added

### Tap subtitles to look up Japanese

- Tap Japanese words in SRT and ordinary WebVTT subtitles in the integrated player.
- See definitions, readings and frequency information in a compact popup beside the word.
- Pause automatically on lookup without opening the player controls, or keep playback running.
- Reveal the matching English subtitle with **EN**, including while a dictionary popup is open.

The popup stays hidden until a lookup finishes. Closing it resumes playback only if the lookup paused it.

### Bring your dictionaries

Import Yomitan dictionary ZIPs directly from **Dictionaries** in the side menu. Term and Frequency
tabs show your installed dictionaries; enable or disable each one independently. Multiple
dictionaries can coexist, and reimporting a title replaces it without clearing the others.

Definitions and frequency data are stored locally. Word pronunciation is fetched on demand and
cached; the same audio can be included on a card. 

### Mine complete Anki cards

Connect to AnkiDroid on the same device. Choose an existing deck and note type, then map your
fields once.
| Map a field to… | What you get |
| --- | --- |
| Word / Reading / Definition | The selected dictionary entry |
| Full subtitle | Japanese sentence, with the selected surface word **bolded automatically** |
| English sentence | The overlapping English subtitle, when available |
| Japanese sentence audio | A clip aligned to the original subtitle cue |
| Word audio | The pronunciation offered in the popup |
| Picture | The video frame, without the popup or controls |
| Show / source title | The current video's title |
| Frequency | Imported frequency labels and ranks |
| Unused | An empty field |

Each field is mapped independently, including multiple fields using the same value. The popup
shows **+** for mining and a **book icon** when the word is already in the selected Anki note type.
Duplicate blocking, optional tags, connection status and the last card error live in **Anki mining**.

Sentence text and media stay tied to the tapped cue. Audio and pictures are imported into AnkiDroid,
so cards can play their media and sync normally. [Full mapping guide →](docs/anki-mining.md)

### Watch and mine YouTube

Search YouTube, paste a video URL, or share a link to Zerifin from another Android app. Videos open
in the same native player and use the same subtitles, dictionary popup and mining actions.

A small optional **yt-dlp companion** runs on your server. It selects manual Japanese captions first,
then original Japanese auto-captions, and supplies normalized WebVTT to the existing renderer.
Playback uses direct media URLs where possible, with a server relay fallback. Only short sentence
clips are transcoded; videos are not permanently downloaded.

YouTube is an early feature: recorded videos, up to 720p, basic search and no account features.
The companion is intended for a trusted LAN or Tailscale network. [Set up YouTube →](docs/youtube.md)

## Get started

1. Download the Zerifin APK from this repository’s **Releases** section, install it, and connect to your
   Jellyfin server. If you prefer to build it yourself, follow the optional [build guide](docs/building.md).
2. Open **Dictionaries** in the main side menu and import a Japanese term dictionary. Add frequency
   dictionaries if you want frequency information.
3. Open **Anki mining**, grant AnkiDroid access, choose your deck and note type, and map your fields.
   **Word** is a useful first-field mapping for duplicate checks.
4. Select the **integrated player** in client playback settings. Play a video with Japanese SRT or
   WebVTT subtitles, tap a word, and press **+** to mine it.
5. For YouTube, start the optional companion and enter its address in the **YouTube** screen.

Android 6.0 or newer is required. The **libre** build omits Chromecast; the **proprietary** build
includes it. Stable signed Zerifin releases and store listings are not configured yet. Upstream
Jellyfin store builds do not contain these features.

## Current limits

- Interactive lookup needs ordinary text subtitles in the integrated player. Styled ASS and image
  subtitles retain normal playback but do not provide word taps.
- Sentence timing, translation and audio are available for Jellyfin streams and resolved YouTube videos;
  downloaded offline media does not yet supply them. Sentence clips are limited to 30 seconds.
- Japanese audio must be identified by its language tag, or confirmed when YouTube leaves it unlabelled.
  Captions, translations and pronunciation are only available when the source provides them.
- Dictionary image rendering, pitch/kanji dictionaries and full morphological analysis are not implemented.
- YouTube extraction can be affected by login, age, region or network restrictions and upstream changes.

## Development and feedback

[Build and test](docs/building.md) · [Learning workflow](docs/zerifin-learning.md) ·
[Contributing](CONTRIBUTING.md) · [Privacy and security](SECURITY.md)

Report Zerifin-specific issues in **this fork's issue tracker**, with the app version and steps to
reproduce. For a mining error, include **Anki mining → Last card error**. For YouTube, include the
public video URL and relevant companion log lines, with private data removed.

## Upstream and license

Zerifin builds on the work of the Jellyfin Android contributors and preserves the upstream Git
history and [GNU GPL v2 license](LICENSE.md). Its green gradient logo is adapted from the existing
Jellyfin app artwork. See [NOTICE.md](NOTICE.md) for the fork's relationship to upstream.

Zerifin is independently maintained and is not an official Jellyfin release.
