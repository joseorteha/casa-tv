# Casa

A small home screen (launcher) for Google TV. Full-screen photo, a dock of apps along the bottom, a second page with
every app, and a web panel to change wallpapers and the dock from a phone. About 30 KB, plain Android Views, no libraries.

Website: https://casa-tv-site.vercel.app

Tested on one TV only: Motorola Google TV (KTC `jagalchi`, MediaTek, Android 14, 0.9 GB RAM).

- `app/` the Android app (`dev.joseo.casa`, minSdk 26, Java 17)
- `site/` a static landing page, deployable as-is to Vercel (set the project's root directory to `site`)
- `wallpapers/` sample photos from Wikimedia Commons (CC BY-SA 4.0), credits in `wallpapers/CREDITS.md`

## How it takes over Home

It does not disable the stock launcher. On this TV the stock launcher cannot be disabled safely: the setup wizard's
`RecoveryActivity` then answers Home with a black screen, and it owns the saved Wi-Fi. Instead `HomeRedirectService`
(an accessibility service) sees the stock home or the wizard come to the front and starts Casa. With
`WRITE_SECURE_SETTINGS` (granted once over adb) Casa switches the service back on by itself, because Android turns
accessibility services off whenever the app is force-stopped or reinstalled.

## Build and install

JDK 17 and Android SDK platform 34.

    ./gradlew assembleRelease
    adb install -r app/build/outputs/apk/release/app-release.apk
    adb shell pm grant dev.joseo.casa android.permission.WRITE_SECURE_SETTINGS
    adb shell cmd notification allow_listener dev.joseo.casa/dev.joseo.casa.NowPlayingListener
    adb shell am start -n dev.joseo.casa/.HomeActivity

## Web panel and iPhone control

`WebServer` listens on port 8080 while the accessibility service runs. The page is public; every API call needs the
six-digit PIN shown on the TV (press Down on Home, next to a QR code that opens the panel already signed in). The Control tab plays, pauses, skips, changes the volume, sends Home/Back and opens apps; the Siri tab lists addresses
to paste into the Shortcuts action "Get Contents of URL". Eight wrong PINs lock it for a minute. It is plain HTTP on the local
network, so use it only on a network you trust.

## Debug build

`./gradlew assembleDebug` also includes `FakePlayerActivity` (only in `app/src/debug`), which starts a fake media session so the
now-playing pill can be checked without a music account:

    adb shell am start -n dev.joseo.casa/.FakePlayerActivity

## Removing it

    adb uninstall dev.joseo.casa

## AirPlay

Casa does not include an AirPlay receiver. The one tried on the test TV is the separate open-source
[AirPlay Server](https://github.com/jqssun/android-airplay-server) (GPL-3.0), installed on its own.

## Credits

Inspired by [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Casa is not affiliated with it, or with Google.
