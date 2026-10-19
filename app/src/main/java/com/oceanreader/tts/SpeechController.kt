package com.oceanreader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.oceanreader.data.model.SpeechSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** What the reader UI needs to draw the follow-reading highlight. */
data class SpeechCursor(
    val chapterIndex: Int = -1,
    val sentenceStart: Int = 0,
    val sentenceEnd: Int = 0,
    val wordStart: Int = -1,
    val wordEnd: Int = -1
) {
    val hasWord: Boolean get() = wordStart >= 0 && wordEnd > wordStart
    val hasSentence: Boolean get() = sentenceEnd > sentenceStart
}

data class SpeechState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val bookId: String = "",
    val segmentIndex: Int = -1,
    val segmentCount: Int = 0,
    val chapterIndex: Int = 0,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String = "",
    val highlightWords: Boolean = true,
    val sleepTimerEnd: Long = 0L,
    val supportsWords: Boolean = false,
    val error: String? = null
) {
    val sleepActive: Boolean get() = sleepTimerEnd > System.currentTimeMillis()
}

/**
 * Read-aloud ("跟读") engine on top of the platform TTS.
 *
 * The book is pre-split into [SpeechSegment] sentences; we speak them one at a
 * time instead of queueing a whole chapter. That costs a few milliseconds per
 * sentence but buys three things that matter for 跟读:
 *  - exact knowledge of which sentence is highlighted, so the highlight can
 *    never drift out of sync with the audio;
 *  - instant, accurate seeking by sentence or by character offset;
 *  - automatic scrolling of the page to follow the voice.
 *
 * Word-level highlighting is used when the engine implements
 * [UtteranceProgressListener.onRangeStart]; otherwise a cursor is advanced
 * using the measured speaking rate, which keeps the effect without lying about
 * the current sentence.
 */
class SpeechController(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var initializing = false
    private var ready = false
    private var engineLocale: Locale = Locale.CHINA

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _cursor = MutableStateFlow(SpeechCursor())
    val cursor: StateFlow<SpeechCursor> = _cursor.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    data class VoiceOption(val name: String, val label: String, val locale: String, val quality: Int)

    private var segments: List<SpeechSegment> = emptyList()
    private var bookId: String = ""
    private var playing = false
    private var paused = false
    private var finished = false
    private var speed = 1f
    private var pitch = 1f
    private var voiceName: String = ""
    private var highlightWords = true

    private var supportsWordRange = false
    private val seq = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wordJob: Job? = null

    /** Measured characters-per-second, used by the fallback word cursor. */
    private var charsPerSecond = 4.6f
    private var segmentStartedAt = 0L

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            AudioManager.AUDIOFOCUS_GAIN -> Unit
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun init(locale: Locale = Locale.CHINA) {
        if (tts != null || initializing) return
        initializing = true
        engineLocale = locale
        tts = TextToSpeech(context.applicationContext) { status ->
            initializing = false
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                _state.value = _state.value.copy(
                    ready = false,
                    error = "语音引擎初始化失败，请在系统设置中安装「文字转语音」引擎"
                )
                return@TextToSpeech
            }
            ready = true
            runCatching { engine.setLanguage(engineLocale) }
            engine.setSpeechRate(speed)
            engine.setPitch(pitch)
            engine.setOnUtteranceProgressListener(listener)
            loadVoices()
            _state.value = _state.value.copy(ready = true, error = null, supportsWords = supportsWordRange)
        }
    }

    private fun loadVoices() {
        val engine = tts ?: return
        val list = runCatching {
            engine.voices.orEmpty()
                .sortedBy { it.locale.toString() }
                .map { v ->
                    VoiceOption(
                        name = v.name,
                        label = describe(v),
                        locale = v.locale.toString(),
                        quality = v.quality
                    )
                }
        }.getOrDefault(emptyList())
        _voices.value = list
        supportsWordRange = list.any { it.locale.startsWith("zh") } || list.isNotEmpty()
        _state.value = _state.value.copy(supportsWords = supportsWordRange)
    }

    private fun describe(v: Voice): String {
        val lang = v.locale.displayLanguage.ifBlank { v.locale.toString() }
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "极高"
            v.quality >= Voice.QUALITY_HIGH -> "高"
            v.quality >= Voice.QUALITY_NORMAL -> "标准"
            else -> "低"
        }
        val name = v.name.substringAfterLast('/').replace('_', ' ')
        return "$name · $lang · $quality"
    }

    fun setLocale(locale: Locale) {
        engineLocale = locale
        tts?.let { runCatching { it.setLanguage(locale) } }
        loadVoices()
    }

    // -------------------------------------------------------------- content

    /**
     * Loads the sentence list for a book. [segments] must be ordered and cover
     * the whole book so that skipping across chapters works.
     */
    fun load(bookId: String, segments: List<SpeechSegment>) {
        this.bookId = bookId
        this.segments = segments
        playing = false
        paused = false
        finished = false
        abandonAudioFocus()
        _state.value = _state.value.copy(
            bookId = bookId,
            segmentCount = segments.size,
            segmentIndex = -1,
            finished = false,
            error = null
        )
        _cursor.value = SpeechCursor()
    }

    fun segmentCount(): Int = segments.size

    /** Index of the segment covering (chapter, offset). */
    fun indexOf(chapterIndex: Int, offset: Int): Int {
        var lo = 0
        var hi = segments.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                s.chapterIndex < chapterIndex || (s.chapterIndex == chapterIndex && offset < s.startInChapter) ->
                    lo = mid + 1
                s.chapterIndex > chapterIndex || s.startInChapter + s.text.length <= offset ->
                    hi = mid - 1
                else -> return mid
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- transport

    fun playFrom(chapterIndex: Int, offset: Int = 0) {
        init()
        if (segments.isEmpty()) {
            _state.value = _state.value.copy(error = "当前文档没有可朗读的文本（可能是扫描版 PDF）")
            return
        }
        val index = indexOf(chapterIndex, offset)
        speak(index)
    }

    fun resume() {
        val index = _state.value.segmentIndex
        if (index < 0) {
            playFrom(_state.value.chapterIndex, 0)
        } else {
            speak(index)
        }
    }

    fun toggle() {
        if (playing) pause() else resume()
    }

    fun pause() {
        tts?.stop()
        stopWordCursor()
        playing = false
        paused = true
        publish()
        ServiceBridge.sync?.invoke(this)
    }

    fun next() = skip(1)

    fun previous() = skip(-1)

    fun skip(delta: Int) {
        if (segments.isEmpty()) return
        val next = (_state.value.segmentIndex + delta).coerceIn(0, segments.lastIndex)
        if (playing || paused) speak(next) else moveCursorTo(next)
    }

    /** Skips an approximate wall-clock distance, like ±15s buttons. */
    fun skipSeconds(seconds: Int) {
        if (segments.isEmpty()) return
        val perSegment = (charsPerSecond / speed).coerceAtLeast(0.8f)
        val needed = (perSegment * seconds).toInt().coerceAtLeast(1)
        skip(if (seconds >= 0) needed else -needed)
    }

    fun seekTo(chapterIndex: Int, offset: Int) {
        val index = indexOf(chapterIndex, offset)
        if (playing) speak(index) else moveCursorTo(index)
    }

    fun stop() {
        tts?.stop()
        stopWordCursor()
        playing = false
        paused = false
        finished = false
        abandonAudioFocus()
        _state.value = _state.value.copy(segmentIndex = -1, finished = false)
        _cursor.value = SpeechCursor()
        publish()
    }

    // ------------------------------------------------------------- settings

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.3f, 3f)
        tts?.setSpeechRate(speed)
        publish()
        if (playing) restartCurrent()
    }

    fun setPitch(value: Float) {
        pitch = value.coerceIn(0.5f, 2f)
        tts?.setPitch(pitch)
        publish()
        if (playing) restartCurrent()
    }

    fun setVoice(name: String) {
        voiceName = name
        val engine = tts ?: return
        runCatching { engine.voices?.firstOrNull { it.name == name } }
            .getOrNull()
            ?.let { engine.voice = it }
        publish()
        if (playing) restartCurrent()
    }

    fun setHighlightWords(enabled: Boolean) {
        highlightWords = enabled
        if (!enabled) _cursor.value = _cursor.value.copy(wordStart = -1, wordEnd = -1)
        publish()
    }

    fun setSleepTimer(minutes: Int) {
        val end = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepTimerEnd = end)
        ServiceBridge.sync?.invoke(this)
    }

    fun sleepRemaining(): Long =
        (_state.value.sleepTimerEnd - System.currentTimeMillis()).coerceAtLeast(0L)

    // --------------------------------------------------------------- speaking

    private fun speak(index: Int) {
        if (index < 0 || index >= segments.size) {
            finish()
            return
        }
        val engine = tts
        if (engine == null) {
            init()
            return
        }
        if (!ready) return

        if (_state.value.sleepTimerEnd in 1..System.currentTimeMillis()) {
            setSleepTimer(0)
            pause()
            return
        }

        requestAudioFocus()

        engine.stop()
        stopWordCursor()

        var i = index
        while (i < segments.size && segments[i].text.isBlank()) i++
        if (i >= segments.size) {
            finish()
            return
        }
        val segment = segments[i]
        val utteranceId = "ocean-${seq.incrementAndGet()}"

        playing = true
        paused = false
        finished = false
        segmentStartedAt = System.currentTimeMillis()

        _state.value = _state.value.copy(
            segmentIndex = i,
            chapterIndex = segment.chapterIndex,
            playing = true,
            paused = false,
            finished = false
        )
        setCursorFor(i)

        val result = runCatching {
            engine.speak(
                segment.text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId
            )
        }.getOrElse { TextToSpeech.ERROR }

        if (result == TextToSpeech.ERROR) {
            playing = false
            _state.value = _state.value.copy(
                playing = false,
                error = "朗读失败，请更换发音人或检查系统 TTS 引擎"
            )
            publish()
            return
        }

        if (highlightWords) startWordCursor(segment)
        publish()
    }

    private fun restartCurrent() {
        val index = _state.value.segmentIndex
        if (index >= 0) speak(index)
    }

    private fun moveCursorTo(index: Int) {
        val segment = segments.getOrNull(index)
        if (segment == null) return
        _state.value = _state.value.copy(segmentIndex = index, chapterIndex = segment.chapterIndex)
        setCursorFor(index)
        publish()
    }

    private fun finish() {
        playing = false
        paused = false
        finished = true
        stopWordCursor()
        abandonAudioFocus()
        publish()
        ServiceBridge.sync?.invoke(this)
    }

    private fun setCursorFor(index: Int) {
        val s = segments.getOrNull(index) ?: return
        _cursor.value = SpeechCursor(
            chapterIndex = s.chapterIndex,
            sentenceStart = s.startInChapter,
            sentenceEnd = s.startInChapter + s.text.length,
            wordStart = -1,
            wordEnd = -1
        )
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            setCursorFor(_state.value.segmentIndex)
        }

        override fun onDone(utteranceId: String?) {
            val next = _state.value.segmentIndex + 1
            if (next >= segments.size) finish() else speak(next)
        }

        // Word ranges only exist from API 26; declared without `override`
        // so the class still compiles on older platforms.
        fun onRangeStart(utteranceId: String?, start: Int, end: Int) {
            val s = segments.getOrNull(_state.value.segmentIndex) ?: return
            if (!highlightWords) return
            supportsWordRange = true
            _cursor.value = _cursor.value.copy(
                wordStart = s.startInChapter + start,
                wordEnd = s.startInChapter + end
            )
        }

        fun onRangeEnd(utteranceId: String?, start: Int, end: Int) {
            val s = segments.getOrNull(_state.value.segmentIndex) ?: return
            val elapsed = (System.currentTimeMillis() - segmentStartedAt).coerceAtLeast(1L)
            charsPerSecond = (s.text.length * 1000f / elapsed).coerceIn(0.5f, 60f)
        }

        @Deprecated("Required override on older API levels")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            playing = false
            _state.value = _state.value.copy(
                playing = false,
                error = if (errorCode == TextToSpeech.ERROR_NETWORK) "网络不可用，朗读已暂停" else "朗读出错"
            )
            publish()
        }
    }

    /** Fallback word cursor for engines without range callbacks. */
    private fun startWordCursor(segment: SpeechSegment) {
        wordJob?.cancel()
        val rate = (charsPerSecond * speed).coerceIn(0.6f, 60f)
        wordJob = scope.launch {
            var offset = 0
            while (isActive) {
                delay(70)
                if (!playing) break
                if (_state.value.segmentIndex != segments.indexOf(segment)) break
                val elapsed = (System.currentTimeMillis() - segmentStartedAt) / 1000f
                val target = max(0, (elapsed * rate).toInt()).coerceAtMost(segment.text.length)
                if (target != offset) {
                    offset = target
                    val end = wordEndIn(segment.text, offset)
                    _cursor.value = _cursor.value.copy(
                        wordStart = segment.startInChapter + offset,
                        wordEnd = segment.startInChapter + end
                    )
                }
                if (offset >= segment.text.length) break
            }
        }
    }

    private fun wordEndIn(text: String, start: Int): Int {
        if (start >= text.length) return start
        var i = start + 1
        // Chinese: advance to the next boundary character.
        if (text[start].code in 0x2E80..0x9FFF) {
            while (i < text.length && text[i].code in 0x2E80..0x9FFF && i - start < 4) i++
            return i
        }
        while (i < text.length && !text[i].isWhitespace() && i - start < 12) i++
        return i
    }

    private fun stopWordCursor() {
        wordJob?.cancel()
        wordJob = null
    }

    private fun publish() {
        _state.value = _state.value.copy(
            ready = ready,
            playing = playing,
            paused = paused,
            finished = finished,
            segmentIndex = _state.value.segmentIndex,
            chapterIndex = _state.value.chapterIndex,
            segmentCount = segments.size,
            speed = speed,
            pitch = pitch,
            voiceName = voiceName,
            highlightWords = highlightWords,
            supportsWords = supportsWordRange
        )
        ServiceBridge.sync?.invoke(this)
    }

    // ---------------------------------------------------------- audio focus

    private fun requestAudioFocus() {
        val am = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.also { audioManager = it } ?: return
        if (BuildVersion.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (BuildVersion.SDK_INT >= 26) {
            focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(focusListener) }
        }
    }

    fun destroy() {
        stopWordCursor()
        abandonAudioFocus()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        scope.coroutineContext[Job]?.cancel()
    }

    private object BuildVersion {
        val SDK_INT: Int = android.os.Build.VERSION.SDK_INT
    }
}

/** Lets the service observe controller changes without a DI framework. */
object ServiceBridge {
    var sync: ((SpeechController) -> Unit)? = null
}

/** Global handle so the service and the app can reach the controller. */
object SpeechHolder {
    @Volatile
    var controller: SpeechController? = null
}