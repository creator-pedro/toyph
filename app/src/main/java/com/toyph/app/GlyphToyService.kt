package com.toyph.app

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.util.Calendar
import com.toyph.app.modes.ModeRenderer
import com.toyph.app.modes.ShakeDetector
import android.util.Log
import com.toyph.app.effects.CrtShimmerEffect
import com.toyph.app.effects.Effect
import com.toyph.app.effects.GlitchTearEffect
import com.toyph.app.effects.PhosphorTrailEffect
import com.toyph.app.effects.ScanlineEffect
import com.toyph.app.effects.TripleScanlineEffect
import com.toyph.app.filters.BlurFilter
import com.toyph.app.filters.PipBoyFilter
import com.toyph.app.filters.BrightnessFilter
import com.toyph.app.filters.DeadPixelFilter
import com.toyph.app.filters.Filter
import com.toyph.app.pipeline.EffectCycler
import com.toyph.app.pipeline.FilterChain
import com.toyph.app.pipeline.RenderingPipeline
import com.toyph.app.pipeline.SequencePlayer
import com.toyph.app.sequence.GlyphItem
import com.toyph.app.sequence.ScrollingTextItem
import com.toyph.app.sequence.SequenceItem
import com.toyph.app.util.TextBitmapRenderer
import com.toyph.app.util.GridConstants.MAX_BRIGHTNESS
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class GlyphToyService : Service() {
    private var manager: GlyphMatrixManager? = null
    private val pipelineScope = CoroutineScope(Dispatchers.IO)
    private var pipelineJob: Job? = null

    private enum class Mode { CLOCK, MUSIC, ANIMATION }

    private var supervisorJob: Job? = null
    private var modeJob: Job? = null
    private var currentMode: Mode? = null
    @Volatile private var animationUntil = 0L
    private lateinit var audioManager: AudioManager
    private var shakeDetector: ShakeDetector? = null

    private lateinit var config: ToyConfig

    private val configFile = "toy-config.json"

    private fun loadGlyph(path: String, brightness: Double): IntArray {
        val json = assets.open(path).bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("brightness")
        val pixels = IntArray(arr.length()) { arr.getInt(it) }
        scalePixels(pixels, brightness)
        return pixels
    }

    private fun scalePixels(pixels: IntArray, brightness: Double) {
        if (brightness < 1.0) {
            for (i in pixels.indices) {
                pixels[i] = (pixels[i] * brightness).toInt()
            }
        }
    }

    private fun buildSequenceItems(): List<SequenceItem> {
        val items = mutableListOf<SequenceItem>()
        for (itemConfig in config.sequence) {
            try {
                when (itemConfig.type) {
                    "glyph" -> {
                        val pixels = loadGlyph(itemConfig.path!!, itemConfig.brightness)
                        items.add(GlyphItem(pixels, itemConfig.displayDurationMs))
                    }
                    "scrolling_text" -> {
                        val renderer = TextBitmapRenderer(assets, itemConfig.font!!)
                        val (matrix, width) = renderer.renderText(itemConfig.text!!)
                        scalePixels(matrix, itemConfig.brightness)
                        items.add(ScrollingTextItem(matrix, width, itemConfig.frameIntervalMs))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create sequence item: ${itemConfig.type}", e)
            }
        }
        return items
    }

    private fun buildEffects(): List<Effect> {
        return config.effects.mapNotNull { effectConfig ->
            when (effectConfig.type) {
                "SCANLINE" -> ScanlineEffect(effectConfig.frameIntervalMs, effectConfig.trailingIntervalMs)
                "TRIPLE_SCANLINE" -> TripleScanlineEffect(effectConfig.frameIntervalMs, effectConfig.trailingIntervalMs)
                "CRT_SHIMMER" -> CrtShimmerEffect(effectConfig.frameIntervalMs, effectConfig.trailingIntervalMs)
                "PHOSPHOR_TRAIL" -> PhosphorTrailEffect(effectConfig.frameIntervalMs, effectConfig.trailingIntervalMs)
                "GLITCH_TEAR" -> GlitchTearEffect(effectConfig.frameIntervalMs, effectConfig.trailingIntervalMs)
                else -> {
                    Log.w(TAG, "Unknown effect type: ${effectConfig.type}")
                    null
                }
            }
        }
    }

    private fun buildFilters(): List<Filter> {
        return config.filters.mapNotNull { filterConfig ->
            when (filterConfig.type) {
                "brightness" -> BrightnessFilter(filterConfig.value)
                "dead_pixels" -> DeadPixelFilter(filterConfig.pixels)
                "blur" -> BlurFilter()
                "pip_boy" -> PipBoyFilter(filterConfig.value)
                else -> {
                    Log.w(TAG, "Unknown filter type: ${filterConfig.type}")
                    null
                }
            }
        }
    }

    private fun startPipeline() {
        stopPipeline()

        val sequenceItems = buildSequenceItems()
        val effects = buildEffects()
        val filters = buildFilters()

        val sequencePlayer = if (sequenceItems.isNotEmpty()) {
            val transitionConfigs = config.sequence.map { it.transition }
            SequencePlayer(sequenceItems, config.defaultTransition, transitionConfigs)
        } else null

        val effectCycler = if (effects.isNotEmpty()) EffectCycler(effects) else null
        val filterChain = if (filters.isNotEmpty()) FilterChain(filters) else null

        val pipeline = RenderingPipeline(
            sequencePlayer = sequencePlayer,
            effectCycler = effectCycler,
            filterChain = filterChain,
            display = { frame -> manager?.setMatrixFrame(frame) }
        )

        pipelineJob = pipeline.start(pipelineScope)
    }

    private fun stopPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
    }

    private fun showFrame(frame: IntArray) {
        for (i in frame.indices) frame[i] = frame[i].coerceIn(0, MAX_BRIGHTNESS)
        manager?.setMatrixFrame(frame)
    }

    private fun startSupervisor() {
        if (supervisorJob?.isActive == true) return
        shakeDetector = ShakeDetector(this) {
            val now = SystemClock.elapsedRealtime()
            if (!audioManager.isMusicActive && now >= animationUntil) {
                animationUntil = now + ANIMATION_MS
            }
        }.also { it.start() }

        supervisorJob = pipelineScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val wanted = when {
                    audioManager.isMusicActive -> Mode.MUSIC
                    now < animationUntil -> Mode.ANIMATION
                    else -> Mode.CLOCK
                }
                if (wanted != currentMode) switchTo(wanted)
                delay(250)
            }
        }
    }

    private fun stopSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = null
        shakeDetector?.stop()
        shakeDetector = null
        stopMode()
    }

    private fun stopMode() {
        stopPipeline()
        modeJob?.cancel()
        modeJob = null
        currentMode = null
    }

    private fun switchTo(mode: Mode) {
        stopMode()
        currentMode = mode
        when (mode) {
            Mode.ANIMATION -> startPipeline()
            Mode.MUSIC -> {
                animationUntil = 0L
                modeJob = pipelineScope.launch {
                    val eq = ModeRenderer.Equalizer()
                    while (isActive) {
                        showFrame(eq.nextFrame())
                        delay(60)
                    }
                }
            }
            Mode.CLOCK -> modeJob = pipelineScope.launch {
                while (isActive) {
                    val cal = Calendar.getInstance()
                    showFrame(ModeRenderer.clockFrame(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
                    delay(1000L - (System.currentTimeMillis() % 1000L))
                }
            }
        }
    }

    private val serviceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    when (event) {
                        GlyphToy.EVENT_AOD, GlyphToy.EVENT_CHANGE -> {
                            startSupervisor()
                        }
                    }
                }
            }
        }
    }

    private val serviceMessenger = Messenger(serviceHandler)

    override fun onBind(intent: Intent?): IBinder {
        val configJson = assets.open(configFile).bufferedReader().use { it.readText() }
        config = ToyConfig.parse(configJson)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        manager = GlyphMatrixManager.getInstance(this)
        manager?.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(componentName: ComponentName) {
                manager?.register(Glyph.DEVICE_25111p)
            }
            override fun onServiceDisconnected(componentName: ComponentName) {}
        })
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopSupervisor()
        pipelineScope.cancel()
        manager?.unInit()
        manager = null
        return false
    }

    companion object {
        private const val TAG = "GlyphToyService"
        private const val ANIMATION_MS = 15_000L
    }
}
