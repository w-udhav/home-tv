# TV Remote

A phone remote for an Android TV box, styled after Apple Music and the Apple TV Remote. It runs
as a tiny Python web server on a computer on the same Wi-Fi, and drives the TV over ADB.

- **Now Playing:** title, artist and YouTube thumbnail, with the background tinted from the
  artwork. Play/pause, previous/next, ±10 s, and a drag-anywhere volume bar.
- **Up Next:** YouTube's suggestions for the current video. Tap one to play it.
- **Remote:** swipe touchpad, Back, Home, Play/Pause, Mute, volume rocker, power, and typing into
  the TV's text fields.
- **Search:** YouTube results on the phone; tapping one plays that exact video on the TV.
- **Apps:** open any app on the TV.

One file of Python (standard library only) and one HTML page. No build step, no dependencies.

## Requirements

- An Android TV / Google TV box with **network debugging** on (Developer options → USB debugging,
  and Network/ADB debugging if listed), reachable at `ip:5555`.
- A computer on the same network with Python 3.9+ and `adb`
  ([platform-tools](https://developer.android.com/tools/releases/platform-tools)).

## Run

```bash
adb connect 192.168.1.2:5555        # accept the prompt on the TV the first time
TV=192.168.1.2:5555 python3 server.py
```

It prints two links. Open the phone link once (same Wi-Fi), then add it to the home screen:
Safari → Share → Add to Home Screen, or Chrome → ⋮ → Add to Home screen.

| Variable | Default | Meaning |
|---|---|---|
| `TV` | `192.168.1.2:5555` | The box's IP and ADB port |
| `ADB` | `~/.local/share/platform-tools/adb` | Path to `adb` |
| `PORT` | `8765` | Port the remote is served on |
| `HIDE_APPS` | none | Optional file of package names to leave out of the Apps tab |

If the computer has a firewall, allow the port on your home network, e.g. with ufw:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 8765 proto tcp
```

## Pairing and security

The first run creates a random pairing code in `.token` (never committed). The phone link carries
it after `#k=`, and the server refuses every request without it, so other devices on the Wi-Fi
can't drive the TV. To unpair every phone, delete `.token` and restart.

The connection is plain HTTP on your LAN, and ADB network debugging stays open while you use the
remote. Both are fine on a trusted home network; don't expose either to the internet.

## How it gets its information

- Playback state and title come from `dumpsys media_session` on the TV.
- YouTube on Android TV reports only title and artist, never the video or its queue. The server
  looks the title up on YouTube (public endpoints, no account or API key) for the thumbnail and
  the Up Next suggestions. So Up Next is what YouTube suggests next, not a guaranteed queue, and a
  thumbnail may occasionally be a different upload of the same song.
- Some firmware ignores "set volume to N" from the shell; the slider steps Volume Up/Down instead,
  so the TV shows its volume bar.
- YouTube doesn't publish playback position, so there is no scrubber, only ±10 s.

App names and colours for the Apps tab are in `APPS` near the top of `server.py`.

## Credits

[Outfit](https://github.com/Outfitio/Outfit-Fonts) typeface, SIL Open Font License (`OFL.txt`).
