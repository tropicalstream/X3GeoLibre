package com.x3geolibre.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voice → text via the Gemini API ("Gemini voice STT" for the AI Assistant).
 *
 * First trigger records the glasses mic (AAC/m4a 16 kHz mono, same recorder as
 * [GroqDictation]); the second trigger (or the 30 s guard) stops it and POSTs
 * the audio inline to `generativelanguage.googleapis.com` `generateContent`
 * with a transcribe-only instruction. The transcript fires [onResult] on the
 * main thread so the host can type it into the assistant's prompt box.
 */
class GeminiDictation(
    private val context: Context,
    private val keyProvider: () -> String,
    private val onState: (recording: Boolean) -> Unit,
    private val onResult: (text: String) -> Unit,
    private val onError: (message: String) -> Unit
) {

    companion object {
        private const val TAG = "GeminiDictation"
        // gemini-3.5-flash kept getting rejected under high demand (overload /
        // quota). 2.5-flash is GA with far more headroom; flash-lite is the most
        // demand-tolerant tier, so it's the fallback when even 2.5-flash is busy.
        private const val MODEL = "gemini-2.5-flash"
        private const val FALLBACK_MODEL = "gemini-2.5-flash-lite"
        private const val MAX_RECORD_MS = 30_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    @Volatile private var recording = false
    @Volatile private var transcribing = false
    private val autoStop = Runnable { if (recording) toggle() }

    fun isRecording(): Boolean = recording

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
        val f = File(context.cacheDir, "gemini_dictation.m4a")
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
            val result = runCatching { transcribe(f, MODEL) }
                .recoverCatching { transcribe(f, FALLBACK_MODEL) }
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
        }, "x3geolibre-gemini-stt").start()
        Log.i(TAG, "recording stopped (${f.length()} bytes) — transcribing")
    }

    /** Blocking JSON POST (inline base64 audio); runs on the worker thread. */
    private fun transcribe(file: File, model: String): String {
        val key = keyProvider().trim()
        if (key.isBlank()) throw IllegalStateException("No Gemini API key.")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray()
                        .put(
                            JSONObject().put(
                                "inline_data", JSONObject()
                                    .put("mime_type", "audio/aac")
                                    .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                            )
                        )
                        .put(JSONObject().put("text", "Transcribe this audio exactly. Reply with ONLY the transcribed text, no quotes, no commentary."))
                )
            )
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "Gemini HTTP $code: ${resp.take(300)}")
                val apiMsg = runCatching {
                    JSONObject(resp).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(apiMsg ?: "Gemini error $code")
            }
            val parts = JSONObject(resp)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}
