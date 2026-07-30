package com.gios.lightbeer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.gios.lightbeer.R

/**
 * All three sound effects are synthesized (scripts/gen_beer_sounds.py), not licensed audio —
 * same reasoning as the video: nothing in this environment can fetch or clear real recordings
 * for redistribution. `glug`/`clink` are one-shots through [SoundPool] for low-latency
 * retriggering; the fizz bed is a looping [MediaPlayer] since [SoundPool] loops don't expose
 * per-frame volume control as cheaply.
 */
class BeerAudio(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private var glugId = 0
    private var clinkId = 0
    private var glugLoaded = false
    private var clinkLoaded = false

    private val fizzPlayer = MediaPlayer().apply {
        val afd = context.resources.openRawResourceFd(R.raw.fizz_loop)
        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        isLooping = true
        setVolume(0f, 0f)
        prepare()
        start()
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                if (sampleId == glugId) glugLoaded = true
                if (sampleId == clinkId) clinkLoaded = true
            }
        }
        glugId = soundPool.load(context, R.raw.glug, 1)
        clinkId = soundPool.load(context, R.raw.clink, 1)
    }

    /** `rate` shifts both speed and pitch — used to make a refill sound like it's rising in
     * pitch as the glass fills, and chugging sound faster/more frantic than a normal sip. */
    fun playGlug(rate: Float = 1f) {
        if (!glugLoaded) return
        soundPool.play(glugId, 0.9f, 0.9f, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun playClink() {
        if (!clinkLoaded) return
        soundPool.play(clinkId, 0.8f, 0.8f, 1, 0, 1f)
    }

    /** Fades the carbonation bed with how much beer is left — silent once the glass is empty
     * without needing a separate mute/unmute state machine. */
    fun setFizzLevel(fill: Float) {
        val v = (fill.coerceIn(0f, 1f) * 0.35f)
        fizzPlayer.setVolume(v, v)
    }

    fun release() {
        soundPool.release()
        fizzPlayer.release()
    }
}
