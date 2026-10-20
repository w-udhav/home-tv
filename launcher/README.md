# Launcher

A small Apple TV-style home screen for Android TV, written for a Fastway / Netplus set-top box
(SEI Robotics FW_SMART_4K_Box, Android TV 14, 2 GB RAM) and usable on other Android TV boxes.
Plain Java, no AndroidX, no Gradle: about 90 KB.

- Pitch-black background, greeting, thin clock and date in [Outfit](https://github.com/Outfitio/Outfit-Fonts).
- A five-app dock in a slightly lighter band, with the focused app's name in large type and a glow
  in a colour sampled from its banner.
- Press **Down** for the full app grid (five columns). One translucent glass highlight springs from
  tile to tile; the focused tile grows and shows its name.
- Frosted pills top right: **Inputs** (HDMI/AV inputs the box reports, live-TV apps, and the stock
  launcher), **Wi-Fi** (shows the network name) and **Settings**.
- Deterministic D-pad: Up/Down move exactly one row, Up from the dock goes to the top bar, row ends
  don't wrap, and the last focused tile is remembered.
- **Black clock** screensaver: a dim clock and date that move every minute against burn-in.
- **Home button service:** some firmware starts its stock launcher by name on Home, whatever app
  holds the HOME role. An accessibility service (*Home button*) takes the Home key and bounces the
  stock launcher back to this one. It only watches that package's windows and reads no content.
  Set the stock package in `HomeKeyService.STOCK`.

## Build and install

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

Needs JDK 17, Android build-tools 34 and the API 34 platform (see the top of the script for how to
point at them) and `adb` connected to the TV. The script builds with aapt2, javac and d8, installs
with `pm install`, grants the location permission the Wi-Fi name needs, sets the launcher as Home,
and enables the Home-button service and the clock screensaver.

`launcher/build-and-install.sh --no-install` only builds. The first build creates `debug.jks`;
keep it, since Android refuses updates signed with a different key.

## Configure

Edit [`res/values/config.xml`](res/values/config.xml) and rebuild:

| Setting | What it does |
|---|---|
| `dock_apps` | Package names for the dock, left to right (up to five) |
| `live_tv_apps` | Apps listed under the Inputs pill |
| `hidden_apps` | Apps never shown in the grid |
| `greeting_name` | Appended to the greeting ("Good evening, Name") |
| `wallpaper_dim_percent` | How much to darken the optional wallpaper |

Dock size and grid columns are `COLS` in `HomeActivity.java`.

**Wallpaper:** push a JPEG and restart the launcher; delete it to go back to black.

```bash
adb push wall.jpg /sdcard/Android/data/com.clean.home/files/wallpaper.jpg
adb shell am force-stop com.clean.home
```

## Undo

```bash
adb shell settings delete secure enabled_accessibility_services
adb shell cmd package set-home-activity --user 0 <stock.launcher/.Activity>
adb uninstall com.clean.home
```
