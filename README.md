# CouchMPV for Android

A port of [couchmpv](https://github.com/JayNinety/couchmpv) (a video player for TV, built with GTK + libmpv)
to Android, rewritten using **Kotlin + Jetpack Compose + Media3 (ExoPlayer)**.
Runs on **Android TV / Google TV / TV Boxes** (supporting remote controls and D-pads), as well as on phones and tablets (via touch).

## Controls (same as the original)

| Key | In the browser | In the player |
|---|---|---|
| ◀ ▶ | Navigate between directories | Rewind / Fast-forward 20s |
| ▲ ▼ | Navigate through the list | ▲ Cycle subtitles (or turn off) · ▼ Cycle audio tracks |
| OK / Enter / Space | Open | Play / Pause |
| Back / Esc / Backspace | Go up one level | Close video and return |
| Menu / Delete (or long-press) | Remove directory | — |
| Home | Exit app; playback position is saved | Pause and save |

Touch (mobile): Single tap = play/pause; double-tap on the sides = ±20s.

## Features Ported

- File browser: Directories (horizontal cards) → folders → video list, including an **Exit** card.
- **Posters** (`Name_Poster.jpg`, `poster.jpg`, `folder.jpg`, etc.), **banners** (`Name_Banner.jpg`, `banner.jpg`, `fanart.jpg`; seasons inherit the series banner), and a blurred background (native blur effect on Android 12+).
- **NFO files** (Kodi XML or plain text): Displays title, year, and synopsis.
- **History**: Resumes playback from where you left off; marks items with a ✓ once more than 90% has been watched; displays `▶ mm:ss` for videos currently in progress. Format compatible with the original `history.json`.
- External subtitles (`.srt`, `.vtt`, `.ass`, `.ssa`) located in the same folder are loaded automatically.
- OSD (On-Screen Display) showing time, resolution, and the current audio/subtitle track.
- Natural sorting (e.g., Ep2 comes before Ep10); hidden folders are ignored.

## Intentional Changes

- **Folders**: On Android, it is not possible to read `~/Videos`. Instead of using a `config` file, you select folders via the system picker (Storage Access Framework) using the **Add folder** option; permissions are then saved.
- **Player**: Uses ExoPlayer instead of `libmpv`; therefore, the options found in the `MPV SETTINGS` block of the configuration file are not available.
- User interface in Portuguese.

## Known Limitations

- ExoPlayer relies on the device's built-in decoders: certain audio formats (DTS, TrueHD, and sometimes E-AC3) as well as PGS/VobSub subtitles may not work on some devices. If this becomes an issue, the next step would be to switch the engine to **libmpv** (via `mpv-android`/JNI)—this would offer better compatibility, though it would significantly increase the project's size.
- The following features have not been ported: codec badges (via `ffprobe`), `target-resolution` (as Compose handles scaling automatically), and the animated "loading" screen.
- Some budget TV boxes do not include the system's folder picker; on these devices, the "Add folder" button will display a notification, but no picker will appear.
