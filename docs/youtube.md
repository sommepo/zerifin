# YouTube quick setup

YouTube is optional. Normal Jellyfin playback, dictionary lookup and AnkiDroid mining do not need
this companion.

Zerifin needs the companion running on another Windows, macOS or Linux computer while you watch.
It does not need to be a Linux server. The computer and phone only need to share a LAN or Tailscale
network.

## Start in three steps

1. Install [Python 3.10 or newer](https://www.python.org/downloads/) and the current
   [Node.js LTS](https://nodejs.org/). On Windows, enable Python's **Add python.exe to PATH** option.
2. Download and unzip this repository, then open Terminal or PowerShell in the unzipped folder.
3. Run one command:

   **Windows PowerShell**

   ```powershell
   py companion\youtube\start.py
   ```

   **macOS or Linux**

   ```sh
   python3 companion/youtube/start.py
   ```

The first run installs the pinned components and ffmpeg inside `companion/youtube/.runtime`; it does
not need administrator access. If Windows asks about network access, allow **Private networks**.
Keep the terminal open while using YouTube. The launcher prefers Tailscale when available and prints
the exact resolver address to enter in Zerifin.

This is a trusted-network service without account authentication. Never port-forward port `8767` or
expose it to the public internet.

## Use it in Zerifin

1. Open **YouTube** in the main side menu.
2. Expand **Resolver address** and enter the address printed by the companion. A Tailscale IP,
   `media-pc`, or a full MagicDNS name is accepted without `http://` or `:8767`.
3. Press **Test resolver**. Continue when it says **Resolver ready**.
4. Search for a Japanese video, paste a YouTube URL, or share a link to Zerifin from another Android app.
5. Choose a result. If YouTube has not labelled its audio language, confirm **Japanese** only when
   the speech is Japanese. Otherwise choose **Skip sentence audio**.
6. Japanese captions are selected automatically when available. Tap a word for the existing dictionary
   popup; use pronunciation, **EN**, or **+** as you would with a Jellyfin video.
7. Seek forward and backward to check caption alignment. Press Back to return and open another video.

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

## Resolver connection help

- Keep the companion terminal open. MagicDNS cannot reach a resolver that has stopped.
- Try the printed Tailscale or LAN IPv4 address first. This separates a DNS issue from a stopped
  companion or firewall problem.
- Confirm the phone and companion computer are both connected in Tailscale before using MagicDNS.
- Open `http://<address>:8767/health` in the phone browser. A healthy companion returns
  `{"status":"ok","sentenceAudio":true}`.
- On Windows, allow Python on Private networks when the firewall prompt appears.
- If automatic detection chooses the wrong interface, pass it directly, for example
  `python3 companion/youtube/start.py --bind 192.168.1.10`.

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

Advanced Linux users can still use `companion/youtube/run.sh`; it now performs first-run setup and
address selection automatically. Use `--bind` or `--port` only when the defaults are wrong.

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
