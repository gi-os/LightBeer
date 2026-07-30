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

- **Tilt to drink.** The accelerometer drives a smoothed tilt angle; past a small dead
  zone (just enough to ignore hand jitter) the glass pours. The rate is squared against how
  far past vertical you are, not linear — a light tilt barely drains it, and only a tilt
  near horizontal empties it fast. A straight-line map made small tilts feel like they
  poured too readily.
- **Shake to refill**, but only once the glass is nearly empty — it animates back up to
  full over about a second rather than snapping, so a refill actually looks like one.
- **Double-tap to chug** — drains in about a second regardless of how the phone is held,
  and still counts as a finished beer.
- **Single tap** is a haptic clink, with a matching clink sound.
- **Beers today** persists across relaunches (`SharedPreferences`, keyed by epoch day —
  same pattern as LightNotebook's calendar entries) and resets itself at local midnight,
  shown as a small overlay in the corner — the only thing drawn on top of the glass.

Rendering the pour is `androidx.media3` (`ExoPlayer` + `PlayerView`, `RESIZE_MODE_ZOOM` so
it crops to fill edge to edge on any aspect ratio, `SeekParameters.EXACT` so `seekTo()`
lands on the exact frame instead of the nearest keyframe). The bundled clip
(`app/src/main/res/raw/beer_pour.mp4`) is encoded all-intra — every frame is a keyframe —
specifically so a random seek is cheap and precise; a normal long-GOP encode would have to
decode forward from the last keyframe on every scrub and visibly lag.

### A rotating glass was tried and cut

For a few versions the whole video frame counter-rotated against device tilt, so the
liquid's horizon stayed level with gravity instead of level with the phone. It caused more
trouble than it was worth and was removed:

1. Rendered through `PlayerView`'s default `SurfaceView` inside a Compose
   `graphicsLayer { rotationZ = ... }`, it **stretched** instead of turning — a `SurfaceView`
   is composited through a separate hardware overlay that ignores parent View transforms.
2. Switching to a raw `TextureView` (`Player.setVideoTextureView`) fixed the stretch — it's a
   normal View and rotates correctly — but cropping to fill had to be computed and applied by
   hand (`TextureView` doesn't do it on its own the way `PlayerView` does), and the rotation
   direction turned out to be backwards on a real device.
3. Even after both of those were fixed, it was **still laggy**: `TextureView` redoes real GPU
   compositing work on every `setTransform()` call, unlike `SurfaceView`'s much cheaper
   hardware-overlay compositing, and that cost showed up precisely while actively tilting —
   exactly the moment it's most noticeable, and not something throttling the call rate could
   fully hide.

`ui/BeerScreen.kt` is back to plain `PlayerView` + `SurfaceView` + `RESIZE_MODE_ZOOM`. The
frame no longer rotates; only which frame is showing (i.e. how full the glass looks)
responds to tilt.

### Sound

Three synthesized effects (`scripts/gen_beer_sounds.py`, numpy DSP, no licensed audio),
played through `audio/BeerAudio.kt`:

- **`fizz_loop.wav`** — carbonation hiss + crackle, built directly in the frequency domain
  so the loop point is mathematically seamless rather than crossfaded. Loops continuously
  through a `MediaPlayer` from app launch; its volume tracks fill level, so it fades out as
  the glass empties instead of needing a separate mute state.
- **`glug.wav`** — a single descending-pitch swallow, retriggered through a `SoundPool` at
  an interval and playback `rate` that depend on what's happening: steady while tilt-pouring,
  faster while chugging, and rising in pitch while refilling (mimicking the way a bottle
  filling under a tap climbs in pitch as the air column above the liquid shrinks).
- **`clink.wav`** — a short inharmonic "glass cheers" tap, on single tap.

**Not verified by ear** — nothing in this environment can play audio back, so these were
built from first-principles DSP (envelopes, decay rates, frequency bands) and checked only
for sane peak/RMS levels, not by listening. If any of the three sound wrong, they're cheap
to regenerate and are worth an actual listen on a device before assuming they're right.

The glug sound is gated on there actually being beer left: tilting or chugging an empty
glass stays silent (checked each physics tick, both in the tilt-pour branch and the chug
branch in `ui/BeerScreen.kt`) — only the refill "filling" glug is allowed to play from
near-empty, since that one represents the glass filling up, not someone drinking from it.

**Still not verified**: the tilt axis (`atan2(ax, ay)`) driving pour is a best guess —
nobody's reported the pour direction being wrong, but it hasn't been explicitly confirmed
either. One-line sign flip in `TiltAndShake` in `ui/BeerScreen.kt` if it ever turns out
backwards — cosmetics only, nothing else depends on it.

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

Regenerate the three sound effects the same way (needs only numpy — no ffmpeg, WAV is
written directly):

```
python3 scripts/gen_beer_sounds.py app/src/main/res/raw
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
`main`: `versionCode` = workflow run number, `versionName` = `<versionName>.<run>`, tag
`v<versionName>.<run>`, exactly one release APK named `LightBeer-v<version>.apk`. The keystore is
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
| v2.4.0 | Cut the rotating-glass feature entirely (see above) — back to plain PlayerView/SurfaceView, no more lag; drain rate is now squared against tilt instead of linear (slow at a light tilt, fast at a full tilt) |
| v2.3.0 | Fixed rotation direction (was backwards) and a real performance regression from the TextureView switch — setTransform()/setVolume() were firing every frame unconditionally |
| v2.2.0 | Fixed the tilt-rotation stretching instead of rotating (PlayerView/SurfaceView → raw TextureView with a hand-rolled crop+rotate matrix); glug sound no longer plays while tilting or chugging an already-empty glass |
| v2.1.0 | The glass visually counter-rotates against device tilt so the liquid horizon reads as level with gravity; three synthesized sound effects (fizz bed, glug, clink) |
| v2.0.0 | Full-screen video-scrubbed pour (media3/ExoPlayer) replaces the hand-drawn Canvas glass; animated refill instead of an instant snap; app renamed off its working title |
| v1.0.0 | Initial commit — tilt-to-drink joke app for the Light Phone III, in full colour |

## Licence

MIT.
