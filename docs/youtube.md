# YouTube setup

YouTube is optional. Jellyfin playback, dictionary lookup and AnkiDroid mining work without this
companion. YouTube videos use the same native player and learning UI once resolved.

## Start the companion

On a Linux x86_64 server with Python 3.10+ and Node 22+ installed, run these commands from the
repository root:

```sh
python3 companion/youtube/install.py
companion/youtube/run.sh --bind 192.168.1.10
```

**Replace `192.168.1.10` with your server's LAN or Tailscale IPv4 address.** The installer downloads
checksum-pinned yt-dlp, its EJS helper and ffmpeg into the ignored `companion/youtube/.runtime/`
directory. It does not need sudo or pip, and makes no system package changes. Node is supplied
by your system. The server listens on port **8767**; use `--port` to change it.

Without `--bind`, it listens only on `127.0.0.1`. Wildcard and public-address binds are rejected.
Keep it running while watching; Ctrl+C stops it. No boot-time service is installed. To keep a
manually started instance alive after closing the terminal:

```sh
nohup companion/youtube/run.sh --bind 192.168.1.10 > companion/youtube/youtube.log 2>&1 &
```

This is a **trusted-network service without account authentication**. Use a LAN or Tailscale address
reachable by your phone. Do not port-forward it or expose it as a public internet service. It
rejects browser cross-origin requests and does not accept arbitrary non-YouTube URLs.

On other platforms, use a Python virtual environment with `companion/youtube/requirements.txt`,
then run `python companion/youtube/resolver.py --bind <private-IPv4>`. Install Node 22+ separately.
An existing `ffmpeg` is preferred; `ZERIFIN_FFMPEG=/path/to/ffmpeg` overrides discovery. Otherwise
the bundled binary is used where supported. A healthy server answers `GET /health` with
`{"status":"ok","sentenceAudio":true}` when ffmpeg is available.

## Use it in Zerifin

1. Open **YouTube** in the main side menu.
2. Expand **Resolver address** and enter your companion's address, for example
   `http://192.168.1.10:8767`. It is saved when **Search / Open** is pressed.
   The default is your Jellyfin hostname on port 8767; change it if the companion runs elsewhere.
3. Search for a Japanese video, paste a YouTube URL, or share a link to Zerifin from another Android app.
4. Choose a result. If YouTube has not labelled its audio language, confirm **Japanese** only when
   the speech is Japanese. Otherwise choose **Skip sentence audio**.
5. Japanese captions are selected automatically when available. Tap a word for the existing dictionary
   popup; use pronunciation, **EN**, or **+** as you would with a Jellyfin video.
6. Seek forward and backward to check caption alignment. Press Back to return and open another video.

AnkiDroid must be configured in **Anki mining** for card creation. AnkiConnect is not used.
YouTube sentence media, word audio, picture and title use the same [field mappings](anki-mining.md).

## Playback and captions

Resolution prefers a progressive video with audio, or combines separate video/audio streams in
Media3. Quality is capped at 720p, with AVC preferred for separate video. There is no YouTube quality
selector yet. The phone first plays signed YouTube URLs directly; on failure it retries once through
the companion's byte-range relay at the same position. If that also fails, reopen the video.

Manual Japanese captions have priority over original Japanese auto-captions. Automatic translations
are not treated as original Japanese captions. The companion normalizes JSON3 timed text into
absolute WebVTT, merging adjacent duplicate windows and clipping rolling overlaps. The Android
subtitle renderer and mining timeline then handle ordinary VTT cues. English is optional and must
overlap the mined Japanese cue.

Missing Japanese captions produces a notice and still allows playback. Interactive Japanese lookup
needs a Japanese track. Captions and pronunciation remain dependent on source availability.

Japanese sentence audio is limited to 30 seconds. Only that clip is transcoded to mono 24 kHz PCM,
then wrapped in WAV by the existing Android mining helper. ffmpeg reads through the local range
relay for compatibility with the bundled binary's HTTPS implementation. No full video is permanently
stored or transcoded. Android imports mapped media into AnkiDroid and cleans its temporary files.

## Limits and troubleshooting

- Recorded videos only; live/upcoming videos, accounts, subscriptions, playlists and history are not supported.
- Search returns up to ten results with title, channel and duration; thumbnails are omitted.
- Sessions live only in memory, expire after six hours and are capped at 32. Restarting the companion
  expires them. Signed YouTube media URLs may expire earlier; reopen the video to resolve fresh URLs.
- Login, age, region and network restrictions may prevent extraction. The companion does not import
  cookies or bypass account restrictions. Deliberately update pinned dependencies if YouTube changes.
- If audio language is unlabelled, the per-open Japanese confirmation enables sentence clips.
  Explicitly non-Japanese audio is not automatically relabelled.
- The companion needs to remain reachable for captions, sentence clips and relay playback.
  YouTube support currently starts from the app's normal connected-server flow.

For a problem report, include the public video URL, exact message and relevant companion log lines.
Logs contain video IDs, format choices, caption selection/normalization and categorized errors;
they omit signed stream URLs, session tokens and raw extractor payloads. Avoid sending a full
logcat or private library/card data. For a failed **+** action, also check **Anki mining → Last card error**.

## API and implementation

`GET /health`; JSON `POST /search` and `/resolve` accept `{"query":"..."}`.
Resolution returns metadata, direct media URLs and an in-memory session capability.
`GET /session/<capability>/ja.vtt`, `en.vtt`, `video`, `audio`, and
`sentence.pcm?start=<seconds>&end=<seconds>` serve the requested resource.
`POST /session/<capability>/japanese-audio` records viewer confirmation for an unlabelled track.

- `companion/youtube/`: extraction, normalization, bounded sessions, range relay, clip extraction and tests.
- `app/src/main/java/org/jellyfin/mobile/youtube/`: search/open UI, client and models.
- `player/source/YouTubeMediaSource.kt`: adapter to the existing player and subtitle stream model.
- `player/mining/PlayerMiningMedia.kt`: shared cue matching, English alignment and bounded WAV preparation.

The YouTube HTTP data source is separate from Jellyfin authentication and its download cache.
The dictionary, subtitle touch handling, popup, Anki gateway and note builder are shared.

Run `python3 -m unittest discover -s companion/youtube -v` for the offline companion tests.
For a full manual check, play a public Japanese video with captions, tap a word, test pronunciation
and **EN**, create a card with your own preset, seek in both directions, and open a second video.
