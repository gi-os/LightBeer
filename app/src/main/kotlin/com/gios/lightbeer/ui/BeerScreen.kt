package com.gios.lightbeer.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gios.lightbeer.R
import com.gios.lightbeer.audio.BeerAudio
import com.gios.lightbeer.data.BeerPrefs
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Tuning. All angles in degrees, all durations in seconds unless the name says otherwise.
private const val TILT_DEAD_ZONE_DEG = 16f
private const val TILT_MAX_DEG = 78f
private const val TILT_SMOOTHING = 0.18f
private const val DRAIN_PER_SEC = 0.55f // a held max tilt empties a full glass in ~1.8s
private const val CHUG_DURATION_SEC = 1.0f
private const val REFILL_DURATION_SEC = 1.2f
private const val SHAKE_DELTA_MS2 = 22f // deviation from 1g that counts as a shake
private const val SHAKE_COOLDOWN_MS = 700L
private const val SHAKE_REFILL_BELOW = 0.08f
private const val SEEK_GRANULARITY_MS = 20L // ~half a video frame; avoids redundant seeks

// How far the video is allowed to visually counter-rotate against device tilt, and how much
// it has to be scaled up first so a rotated, cropped-to-fill frame never shows a corner gap.
private const val ROTATION_MAX_DEG = 55f
private const val VIDEO_OVERSCALE = 1.9f

private const val NORMAL_GLUG_INTERVAL_SEC = 0.55f
private const val CHUG_GLUG_INTERVAL_SEC = 0.22f
private const val REFILL_GLUG_INTERVAL_SEC = 0.28f

/** Everything the glass needs, driven one frame at a time; the video does the rendering. */
private class BeerState {
    var fill by mutableFloatStateOf(1f) // 1 = full glass, 0 = empty
    var tiltDeg by mutableFloatStateOf(0f) // smoothed device tilt
    var chugging by mutableStateOf(false)
    var refilling by mutableStateOf(false)
    var beersToday by mutableIntStateOf(0)
    var countedThisGlass by mutableStateOf(false)
}

@Composable
fun BeerScreen(prefs: BeerPrefs) {
    val context = LocalContext.current
    val state = remember {
        BeerState().also {
            it.beersToday = prefs.todayCount()
            it.countedThisGlass = false
        }
    }
    val audio = remember { BeerAudio(context) }
    DisposableEffect(Unit) { onDispose { audio.release() } }

    TiltAndShake(
        onTilt = { angle -> state.tiltDeg += (angle - state.tiltDeg) * TILT_SMOOTHING },
        onShake = {
            if (!state.chugging && !state.refilling && state.fill <= SHAKE_REFILL_BELOW) {
                state.refilling = true
                vibrate(context, 40)
            }
        },
    )

    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        var glugTimer = 0f
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = nowNanos

            // Set only while there's a reason to hear a swallow this tick; left at their
            // defaults (no interval) resets the timer instead of firing on old debt.
            var glugIntervalSec = Float.MAX_VALUE
            var glugRate = 1f

            when {
                state.chugging -> {
                    state.fill = max(0f, state.fill - dt / CHUG_DURATION_SEC)
                    if (state.fill <= 0f) state.chugging = false
                    glugIntervalSec = CHUG_GLUG_INTERVAL_SEC
                    glugRate = 1.2f
                }
                state.refilling -> {
                    state.fill = min(1f, state.fill + dt / REFILL_DURATION_SEC)
                    if (state.fill >= 1f) {
                        state.refilling = false
                        state.countedThisGlass = false
                    }
                    glugIntervalSec = REFILL_GLUG_INTERVAL_SEC
                    // Rises as the glass fills — the same reason a bottle filling under a tap
                    // climbs in pitch as the air column above the liquid shrinks.
                    glugRate = 0.75f + 0.65f * state.fill
                }
                else -> {
                    val tiltMag = abs(state.tiltDeg)
                    val pour = ((tiltMag - TILT_DEAD_ZONE_DEG) / (TILT_MAX_DEG - TILT_DEAD_ZONE_DEG))
                        .coerceIn(0f, 1f)
                    if (pour > 0f) {
                        state.fill = max(0f, state.fill - pour * DRAIN_PER_SEC * dt)
                        glugIntervalSec = NORMAL_GLUG_INTERVAL_SEC
                        glugRate = 0.95f
                    }
                }
            }

            if (glugIntervalSec < Float.MAX_VALUE) {
                glugTimer += dt
                if (glugTimer >= glugIntervalSec) {
                    glugTimer = 0f
                    audio.playGlug(glugRate)
                }
            } else {
                glugTimer = 0f
            }
            audio.setFizzLevel(state.fill)

            if (state.fill <= 0f && !state.countedThisGlass) {
                state.countedThisGlass = true
                state.beersToday = prefs.recordBeer()
                vibrate(context, 30)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BeerVideo(
            fillProvider = { state.fill },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Overscaled so a rotated, crop-filled frame never shows a corner gap.
                    scaleX = VIDEO_OVERSCALE
                    scaleY = VIDEO_OVERSCALE
                    // Best guess, unverified on a real LPIII: counter-rotating the content
                    // against the device's own rotation is what makes the horizon line
                    // inside it read as level with gravity instead of level with the phone.
                    // Flip the sign here if it turns out to rotate the wrong way.
                    rotationZ = (-state.tiltDeg).coerceIn(-ROTATION_MAX_DEG, ROTATION_MAX_DEG)
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            audio.playClink()
                            vibrate(context, 15)
                        },
                        onDoubleTap = {
                            if (!state.chugging && !state.refilling && state.fill > 0f) {
                                state.chugging = true
                                vibrate(context, 60)
                            }
                        },
                    )
                },
        )

        Text(
            text = "BEERS TODAY ${state.beersToday}",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xCCFFFFFF),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * The glass itself: a pre-rendered pour, scrubbed by seek position instead of played back —
 * the classic "tilt to drink" trick, applied to a real video track. `fillProvider` reports
 * 1 (full) to 0 (empty); position 0ms of the video is a full glass, its end is empty, so the
 * mapping is a direct `(1 - fill) * duration`.
 */
@Composable
private fun BeerVideo(fillProvider: () -> Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${context.packageName}/${R.raw.beer_pour}")))
            setSeekParameters(SeekParameters.EXACT)
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    var durationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && durationMs <= 0L) {
                    durationMs = player.duration.coerceAtLeast(1L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val fillState by rememberUpdatedState(fillProvider)
    LaunchedEffect(player) {
        var lastSeekMs = -1_000L
        while (true) {
            withFrameNanos { }
            val duration = durationMs
            if (duration > 0L) {
                val target = ((1f - fillState()) * duration).toLong().coerceIn(0L, duration)
                if (abs(target - lastSeekMs) >= SEEK_GRANULARITY_MS) {
                    player.seekTo(target)
                    lastSeekMs = target
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
    )
}

@Composable
private fun TiltAndShake(onTilt: (Float) -> Unit, onShake: () -> Unit) {
    val context = LocalContext.current
    val tiltCallback by rememberUpdatedState(onTilt)
    val shakeCallback by rememberUpdatedState(onShake)

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastShakeAt = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // Rotation of the gravity vector around the screen's z-axis: at rest this
                // is ~0, and tilting the phone like tipping a glass to drink swings it
                // toward +/-90.
                val angleDeg = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                tiltCallback(angleDeg)

                val magnitude = hypot(hypot(ax.toDouble(), ay.toDouble()), az.toDouble()).toFloat()
                val gDelta = abs(magnitude - SensorManager.GRAVITY_EARTH)
                val now = System.currentTimeMillis()
                if (gDelta > SHAKE_DELTA_MS2 && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                    lastShakeAt = now
                    shakeCallback()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (sensor != null) {
            sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensorManager?.unregisterListener(listener) }
    }
}

private fun vibrate(context: Context, ms: Long) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}
