# LightBeer

A tilt-to-drink virtual pint for the **Light Phone III** — the one full-colour tool in an
otherwise greyscale collection. Package `com.gios.lightbeer`, launcher label **Beer**.

## Why this exists

It's a joke. Every other tool in the `gi-os` Light Phone III collection matches LightOS's
black-and-white chrome on purpose — this is the one deliberate exception, because a
monochrome pint of beer just looks like an X-ray. Colour is the bit, and the glass fills
the entire screen with no title bar or button chrome sitting on top of it.

## How it works

The glass is a short, pre-rendered pour, scrubbed by seek position instead of played back
— the same trick real "tilt to drink" apps have always used on filmed footage. Position 0
of the clip is a full glass, its end is empty, and the app just picks a frame:

- **Tilt to drink.** The accelerometer drives a smoothed tilt angle; past a dead zone the
  glass pours, faster the further past vertical you go.
- **Shake to refill**, but only once the glass is nearly empty — it animates back up to
  full over about a second rather than snapping, so a refill actually looks like one.
- **Double-tap to chug** — drains in about a second regardless of how the phone is held,
  and still counts as a finished beer.
- **Single tap** is just a haptic clink.
- **Beers today** persists across relaunches (`SharedPreferences`, keyed by epoch day —
  same pattern as LightNotebook's calendar entries) and resets itself at local midnight,
  shown as a small overlay in the corner — the only thing drawn on top of the glass.

Rendering the pour is `androidx.media3` (`ExoPlayer` + `PlayerView`, `RESIZE_MODE_ZOOM` so
it crops to fill edge to edge on any aspect ratio, `SeekParameters.EXACT` so `seekTo()`
lands on the exact frame instead of the nearest keyframe). The bundled clip
(`app/src/main/res/raw/beer_pour.mp4`) is encoded all-intra — every frame is a keyframe —
specifically so a random seek is cheap and precise; a normal long-GOP encode would have to
decode forward from the last keyframe on every scrub and visibly lag.

**Not yet verified on a real LPIII**: the tilt axis (`atan2(ax, ay)`) is a best guess at
which physical tilt should pour the glass. If it pours the wrong way, flip the sign in
`TiltAndShake` in `ui/BeerScreen.kt` — cosmetics only, nothing else depends on it.

### Regenerating the video

There's no network access in the environment this was built in to license real filmed
footage, so the clip is procedurally rendered — gradients, cellular foam noise, rising
bubbles on fixed nucleation columns, static condensation and glass-highlight overlays,
vignette, grain — composited frame by frame with numpy/PIL, then encoded all-intra with
ffmpeg:

```
python3 scripts/gen_beer_video.py --frames /tmp/beer_frames
ffmpeg -y -framerate 30 -i /tmp/beer_frames/frame_%04d.png \
  -pix_fmt yuv420p -g 1 -keyint_min 1 -sc_threshold 0 \
  -c:v libx264 -profile:v high -crf 21 -preset medium -movflags +faststart \
  app/src/main/res/raw/beer_pour.mp4
```

The frame-to-fill mapping in the generator is linear on purpose
(`fill = 1.0 - t`) — the app seeks `positionMs = (1 - fill) * durationMs` directly, so if
that curve in the generator ever grows an easing function, the seek math has to invert it
or the two disagree about what "half full" looks like.

## Building

```
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

CI (`.github/workflows/build.yml`) publishes a signed GitHub Release on every push to
`main`: `versionCode` = workflow run number, `versionName` = `2.0.<run>`, tag
`v2.0.<run>`, exactly one release APK named `LightBeer-v<version>.apk`. The keystore is
committed at `keystore/lightbeer.jks` so `adb install -r` upgrades in place, and CI pins
its cert SHA-256 in `signing-fingerprint.txt`.

Grab the latest build from [Releases](../../releases/latest) or track
`https://github.com/gi-os/LightBeer` in Obtainium.

### Icon

Generated, not hand-drawn — the one full-colour icon among the greyscale line-art icons of
the sibling tools:

```
python3 scripts/generate_icon.py   # needs Pillow; rewrites app/src/main/res/{drawable*,mipmap-*}
```

### Why this isn't a Light SDK tool

The [Light SDK](https://github.com/lightphone/light-sdk) sandbox is built around the
greyscale design system and its own constrained UI toolkit — this app needs full RGB video
playback and raw accelerometer access outside any of that, so it ships as a plain
sideloaded APK like LightTip and LightPass.

## Version history

| Version | Change |
| --- | --- |
| v2.0.0 | Full-screen video-scrubbed pour (media3/ExoPlayer) replaces the hand-drawn Canvas glass; animated refill instead of an instant snap; app renamed off its working title |
| v1.0.0 | Initial commit — tilt-to-drink joke app for the Light Phone III, in full colour |

## Licence

MIT.
