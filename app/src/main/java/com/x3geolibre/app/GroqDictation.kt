package com.x3geolibre.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tap-to-dictate via Groq's Whisper endpoint.
 *
 * First trigger starts recording the glasses mic (MediaRecorder, AAC/m4a,
 * 16 kHz mono); the second trigger (or the 60 s guard) stops it and POSTs the
 * clip to `api.groq.com/openai/v1/audio/transcriptions`
 * (`whisper-large-v3-turbo`). The transcript comes back through [onResult]
 * so the host can type it into the focused Gemini prompt field.
 *
 * All callbacks fire on the main thread. The API key is read per-use from
 * [keyProvider] (settings-backed), so a key entered mid-session applies to
 * the very next dictation.
 */
class GroqDictation(
    private val context: Context,
    private val keyProvider: () -> String?,
    private val onState: (recording: Boolean) -> Unit,
    private val onResult: (text: String) -> Unit,
    private val onError: (message: String) -> Unit
) {

    companion object {
        private const val TAG = "GroqDictation"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL = "whisper-large-v3-turbo"
        private const val MAX_RECORD_MS = 60_000L
        private const val BOUNDARY = "----X3GeoLibreGroqBoundary7f3a"
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    @Volatile private var recording = false
    @Volatile private var transcribing = false
    private val autoStop = Runnable { if (recording) toggle() }

    fun isRecording(): Boolean = recording

    /** Start on first call, stop-and-transcribe on the second. */
    fun toggle() {
        if (transcribing) return
        if (recording) stopAndTranscribe() else start()
    }

    fun cancel() {
        main.removeCallbacks(autoStop)
        if (recording) {
            recording = false
            onState(false)
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            outFile?.delete()
        }
    }

    private fun start() {
        val key = keyProvider()?.trim().orEmpty()
        if (key.isBlank()) {
            onError("No Groq API key — set it in Settings (gear, top-right).")
            return
        }
        val f = File(context.cacheDir, "dictation.m4a")
        val rec = runCatching {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64_000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
        }.getOrElse { e ->
            Log.w(TAG, "record start failed: ${e.message}")
            onError("Mic couldn't start: ${e.message}")
            return
        }
        recorder = rec
        outFile = f
        recording = true
        onState(true)
        main.postDelayed(autoStop, MAX_RECORD_MS)
        Log.i(TAG, "recording started")
    }

    private fun stopAndTranscribe() {
        main.removeCallbacks(autoStop)
        recording = false
        onState(false)
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "stop failed: ${it.message}") }
        runCatching { recorder?.release() }
        recorder = null
        val f = outFile ?: return
        if (!f.exists() || f.length() < 800) {
            onError("Nothing recorded.")
            return
        }
        transcribing = true
        Thread({
            val result = runCatching { transcribe(f) }
            f.delete()
            main.post {
                transcribing = false
                result.fold(
                    onSuccess = { text ->
                        if (text.isBlank()) onError("Heard nothing.") else onResult(text)
                    },
                    onFailure = { e ->
                        Log.w(TAG, "transcription failed: ${e.message}")
                        onError(e.message ?: "Transcription failed.")
                    }
                )
            }
        }, "x3geolibre-groq").start()
        Log.i(TAG, "recording stopped (${f.length()} bytes) — transcribing")
    }

    /** Blocking multipart POST; runs on the worker thread. */
    private fun transcribe(file: File): String {
        val key = keyProvider()?.trim().orEmpty()
        if (key.isBlank()) throw IllegalStateException("No Groq API key.")
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        }
        try {
            DataOutputStream(conn.outputStream).use { o ->
                fun field(name: String, value: String) {
                    o.writeBytes("--$BOUNDARY\r\n")
                    o.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    o.writeBytes("$value\r\n")
                }
                field("model", MODEL)
                field("response_format", "json")
                o.writeBytes("--$BOUNDARY\r\n")
                o.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"dictation.m4a\"\r\n"
                )
                o.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                o.write(file.readBytes())
                o.writeBytes("\r\n--$BOUNDARY--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "Groq HTTP $code: ${body.take(300)}")
                val apiMsg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                throw IllegalStateException(apiMsg ?: "Groq error $code")
            }
            return JSONObject(body).optString("text").trim()
        } finally {
            conn.disconnect()
        }
    }
}
