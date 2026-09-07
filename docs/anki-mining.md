# AnkiDroid subtitle mining

Zerifin adds words from the player to an existing AnkiDroid deck and note type. Open **Anki mining**
in the main side menu, grant the AnkiDroid permission, select the deck and note type, then choose
which value goes in each field. The same value can be mapped to multiple fields. Existing text-only
presets remain compatible. The shortcut opens the full-screen mapping form directly. Rounded rows
contain each choice and its arrow; **Save** applies the preset. The connection panel checks the
local AnkiDroid API and offers permission/retry actions. AnkiConnect is not needed. A failed add
shows its specific cause in the player and retains it as **Last card error** in this screen.

| Value | Card content |
| --- | --- |
| Unused | Empty field |
| Word | Dictionary expression |
| Reading | Japanese reading |
| Definition | Selected dictionary definition |
| Full subtitle | Frozen Japanese sentence |
| English sentence | English subtitle overlapping the Japanese cue, when available |
| Japanese sentence audio | Clip from a Japanese audio track |
| Word audio | The same pronunciation audio offered in the popup |
| Picture | Current video frame |
| Show / source title | Episode, movie or YouTube video title |
| Frequency | Imported dictionary frequency labels and ranks |

Use a text source for the first Anki field; **Word** is the usual choice. AnkiDroid checks duplicates
against that field within the selected note type. Media cannot be the first field because AnkiDroid
assigns imported files new names. Optional tags and duplicate blocking are the only mining options.

Tap a Japanese subtitle word to open the dictionary card. Each entry checks its duplicate status
before mining, including when duplicate blocking is disabled. A known existing word uses a book
icon; other entries retain the **+** mining action without a text status label. An unavailable check
does not claim the word is new, and the gateway checks again when mining. Tap **+** to create a note. The sentence and
entry are frozen before background work, so cue changes cannot change the requested note. The
target word is bold by default in the Japanese sentence field, using the actual tapped surface
form even when the dictionary entry is a different conjugation. Other field values are unchanged.

Only mapped media is prepared and imported. An absent English subtitle or unavailable Japanese
audio leaves that field empty; the player explains unavailable mapped content. A failed sentence
clip or Anki media import prevents note creation. Word
audio, sentence audio and pictures become files in AnkiDroid's collection, with standard sound or
image field markup, and can sync with Anki media. Text is escaped as HTML; credentials and media
URLs never become field content.

Sentence timing, English matching and Japanese sentence audio work with Jellyfin streams and
[YouTube companion](youtube.md) sources. Downloaded local files do not provide those sources. Sentence
audio requests explicit raw PCM and packages it into a bounded local WAV clip, up to 30 seconds
per subtitle cue; longer cues cannot be clipped. Explicit sample-rate parameters support older
Jellyfin PCM transcoders as well as corrected server versions.
For Jellyfin, the clip uses the original media clock and a Japanese-tagged track even when another
audio language is playing. YouTube prefers a Japanese audio track; if language metadata is absent,
the app asks you to confirm Japanese before enabling sentence clips. Cancellation closes the clip request and stops its separate server encoding session.

The gateway refreshes the live deck, note type and ordered field names before each note. Stored
names recover changed IDs, dynamic decks are excluded, and a missing first-field mapping fails
safely. Requests are serialized and duplicate blocking runs before importing media. Zerifin never
creates or renames a deck or note type. Notes are created only by the mining button.

## Platform integration

This implementation uses the pinned official dependency
`com.github.ankidroid:Anki-Android:api-v1.1.0` and its documented
[Instant-Add and content-provider APIs](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API).
It requests `com.ichi2.anki.permission.READ_WRITE_DATABASE` and declares package/provider visibility.
An unexported Android FileProvider grants AnkiDroid read access to one temporary file at a time,
limited to `cacheDir/anki-media`; each grant is revoked when import returns. Local temporary media
is removed when mining completes. The Anki API does not offer transactional media-and-note insertion;
if a later import or note add fails, AnkiDroid can retain an unreferenced imported media file.

## Acceptance checks

- Configure arbitrary field names and confirm all eleven choices and live field order.
- Reopen a text-only preset and confirm mappings, tags and duplicate preference survive unchanged.
- Grant or deny AnkiDroid permission and confirm the popup reports the resulting state.
- Mine a sentence with Japanese and English subtitles, picture, Japanese sentence audio, word audio,
  title and frequency mapped; inspect and play the resulting note in AnkiDroid.
- Repeat a word and confirm the visible duplicate indicator and duplicate blocking behavior.
- Use a source without English subtitles or a Japanese audio track and check the unavailable notice.
- Interrupt media download and confirm a note is not created and the media request is cancelled.
- Change or delete the configured deck, note type or first field and confirm safe recovery or failure.
