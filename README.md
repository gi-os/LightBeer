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
- **The glass visually rotates against the tilt**, so the liquid's horizon line stays level
  with gravity instead of level with the phone — the same thing a real glass does in your
  hand. This is a counter-rotation of the whole video frame (see "Why a TextureView" below),
  overscaled ~1.8x on top of its normal crop-to-fill so a rotated frame never shows a corner
  gap. It's not a physically simulated liquid surface — the video has no separate "liquid"
  and "glass" layers to rotate independently, so the trick only works because the shot never
  shows a fixed glass rim to reveal it.
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

### Why a TextureView, not media3's PlayerView

The first pass at rotation used `PlayerView` (`RESIZE_MODE_ZOOM`) inside a Compose
`graphicsLayer { rotationZ = ... }`. It visibly **stretched** instead of turning: `PlayerView`
renders onto a `SurfaceView` by default, and a `SurfaceView`'s content is composited through
a separate hardware overlay that doesn't participate in the normal View transform pipeline —
rotating (or scaling) one from a parent produces exactly that kind of distortion instead of a
clean rotation. The fix was to drop `media3-ui` entirely and render onto a plain
`TextureView` (`Player.setVideoTextureView`), which is a regular View and rotates correctly.
The trade-off is that `TextureView` doesn't crop-to-fill on its own the way `PlayerView` did,
so the aspect-correct cover scale is computed by hand in `ui/BeerScreen.kt` and combined with
the tilt rotation into one `Matrix` passed to `setTransform()`.

`TextureView` also redoes real GPU compositing work on every `setTransform()` call, unlike
`SurfaceView`'s hardware overlay — calling that unconditionally once a frame (~60x/sec) was
visibly laggy. The rotation/seek loop now skips the call entirely unless the rotation angle
moved by more than `ROTATION_EPSILON_DEG` or the view was resized, and the ambient fizz
volume update (a `MediaPlayer.setVolume()` call) is gated the same way on fill level actually
changing. The video's own `seekTo()` granularity was also loosened from 20ms to 33ms to match
the bundled clip's actual per-frame spacing (finer than that can't land on a different frame
anyway, so it was pure wasted work).

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

**Confirmed on a real device**: the rotation direction was backwards in the first cut
(counter-rotating against the tilt, on the theory that the video needed to cancel the
phone's own rotation) — turns out rotating *with* it is what reads as level with gravity,
now fixed in `ui/BeerScreen.kt`.

**Still not verified**: the tilt axis (`atan2(ax, ay)`) driving *pour* is a separate best
guess — nobody's reported the pour direction being wrong, but it hasn't been explicitly
confirmed either. One-line sign flip in `TiltAndShake` in `ui/BeerScreen.kt` if it ever
turns out backwards — cosmetics only, nothing else depends on it.

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
| v2.3.0 | Fixed rotation direction (was backwards) and a real performance regression from the TextureView switch — setTransform()/setVolume() were firing every frame unconditionally |
| v2.2.0 | Fixed the tilt-rotation stretching instead of rotating (PlayerView/SurfaceView → raw TextureView with a hand-rolled crop+rotate matrix); glug sound no longer plays while tilting or chugging an already-empty glass |
| v2.1.0 | The glass visually counter-rotates against device tilt so the liquid horizon reads as level with gravity; three synthesized sound effects (fizz bed, glug, clink) |
| v2.0.0 | Full-screen video-scrubbed pour (media3/ExoPlayer) replaces the hand-drawn Canvas glass; animated refill instead of an instant snap; app renamed off its working title |
| v1.0.0 | Initial commit — tilt-to-drink joke app for the Light Phone III, in full colour |

## Licence

MIT.
