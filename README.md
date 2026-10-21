# WANG Radio — Android companion app

Plays your HomeRadio station on your phone and in Android Auto. It shows album artwork, and star ratings are saved on the station PC and copied to Plex.

## Get the APK (no Android Studio needed)
1. Create a free GitHub account, then make a new **private** repository named `wang-radio`.
2. Upload everything in this folder to that repository. On the repository page, choose **Add file → Upload files** and drag the folder contents in. Include the hidden `.github` folder, because that is what builds the app.
3. Open the **Actions** tab. The **Build WANG Radio APK** job runs automatically and takes about 5 minutes.
4. Open the finished run and download **WANG-Radio-apk** under *Artifacts*. Unzip it to get `WANG-Radio.apk`.
5. Copy the APK to your phone and tap it to install. Android asks you to allow installs from that source; allow it.

You can also build it in Android Studio: **Open** this folder, then choose **Build → Build APK(s)**.

## Stations
The app opens on the **station list**. It shows every station the server runs, one per music folder set up in `[[channels]]` in `radio.toml`, each with its current song. Tap one to tune in. The station you're tuned to has an orange **Your station** tag and full-colour artwork; the others show in black and white. On the player screen, tap the back arrow or the station name to go back to the list. In Android Auto, the stations appear as a list, and you pick one there.

## Connect the app to your station
- **Easiest:** when the station starts, tap **Open in WANG Radio** on the ntfy notification on your phone. The app fills in the station address and password, then opens the station list. Do this again whenever the Cloudflare link changes.
- **By hand:** tap the settings button (top right), fill in **This phone** with the station address and the `password` from `radio.toml`, then tap **Save connection**.

## Station settings
The settings button (top right on every screen) opens **Settings**:
- **This phone:** the station address and password this phone uses.
- **Every setting in `radio.toml`**, grouped by section the same way as the file, with any comments from the file shown as help. On/off settings are switches; lists like `music = [...]` are edited as text. Changed settings turn orange; tap **Save changes** at the bottom. Passwords and tokens are never shown; leave one blank to keep it.

Saving writes `radio.toml` on the station PC (the old one is kept as `radio.toml.bak`). Changes apply after you **restart the station**. If a change would break the file, it isn't saved and the app says why.

Who can change settings:
- **At home (same Wi-Fi as the station):** it just works.
- **Away from home** (through the Cloudflare link): add `admin_password = "something"` under `[stream]` in `radio.toml` and restart the station. The app asks for it once and remembers it. Without it, the app shows *Settings open only on your home network*.

This needs the updated `radio.py` (the one with the settings page in the web player).

## Android Auto
Android Auto only shows apps installed from the Play Store unless you turn on unknown sources. Do this once on your phone:
1. Open **Settings → Apps → Android Auto → Additional settings in the app**.
2. Tap **Version** about 10 times until developer mode turns on.
3. Open **⋮ → Developer settings** and turn on **Unknown sources**.
4. Restart Android Auto. WANG Radio then appears in the car's app list.

In the car you get artwork, song title and artist, play/stop, five ★ buttons, ⏮ back and ⏭ next. The stars come first, so Android Auto puts them in the main spots next to play/stop. Back and next come after the stars, and on smaller car screens they may sit behind the ⋯ (more) button. Steering-wheel next and back buttons still work.

**Rating in the car:** tap a star to rate the song; it's saved right away. ★ (1 star) plays the record scratch and skips the song.

## Controls
- **⏭ Next:** the station plays `next.mp3`, then the next song.
- **⏮ Back:** the station plays `back.mp3`, then the previous song. Press it again to keep going further back.
- **1 star:** saves the rating, plays `record_scratch.mp3`, then skips to the next song.
- **Previously / Up next:** shown under the controls, with artwork.

The three sound effects go in a folder called `assets` next to `radio.py`. These controls change the station itself, so anyone else listening hears the skip too. You'll hear the change about 2–5 seconds after you press the button, because of stream buffering.

## Star ratings and Plex
Tap 1–5 stars in the app or the web player (or the ★ buttons in Android Auto). Tapping the same star again clears the rating. The station saves the stars in `ratings.json` (every rating is also logged in `ratings_log.csv`) and copies them to Plex. Songs rated 1–2 stars come up less often and 4–5 stars a bit more; turn that off with `[ratings] influence_rotation = false`. This is one way only: ratings you change in Plex aren't read back.

To connect Plex, fill in the `[plex]` section of `radio.toml` with your Plex address and token. The station log shows "Plex connected" at startup and "Plex: rated …" each time a rating goes through. The station finds each song in Plex by its file path. If Plex sees your music under a different path, for example through a network share, it matches by file name instead.

## Look
The app uses the same parchment-and-charcoal design as the web player: square corners, thick black borders, hard shadows, one orange accent (the play button, your stars, your station, changed settings), Fraunces serif titles and spaced DM Sans capitals. The fonts are built into the app.

## Artwork
For each song, the station uses the cover art embedded in the music file. If there isn't any, it uses `cover.jpg`, `folder.jpg` or `front.jpg` from the song's folder. During station breaks, or when a song has no art, the app shows the WANG Radio logo. You can also set your own fallback image with `logo = "C:/path/logo.png"` in `radio.toml`.
