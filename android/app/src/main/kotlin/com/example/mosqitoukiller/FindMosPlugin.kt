package com.example.mosqitoukiller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

private const val TAG = "FindMosKt"

private const val CHANNEL_METHOD = "com.example.mosqitoukiller/method"
private const val CHANNEL_RADAR = "com.example.mosqitoukiller/radar"
private const val CHANNEL_CAMERA = "com.example.mosqitoukiller/camera"

private const val SAMPLE_RATE = 44100
private const val FFT_SIZE = 1024
private const val F_LOW = 300.0
private const val F_HIGH = 600.0
private const val CAM_WIDTH = 160
private const val CAM_HEIGHT = 90
private const val CAM_PERIOD_MS = 100L

class FindMosPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var context: Context
    private var activity: android.app.Activity? = null

    private lateinit var methodChannel: MethodChannel
    private lateinit var radarEventChannel: EventChannel
    private lateinit var cameraEventChannel: EventChannel

    private var radarSink: EventChannel.EventSink? = null
    private var cameraSink: EventChannel.EventSink? = null

    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var audioRecord: AudioRecord? = null
    private var audioRunning = false

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraRunning = false

    private var torchCameraId: String? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        CrashHandler.init(context)
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_METHOD)
        methodChannel.setMethodCallHandler(this)

        radarEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, CHANNEL_RADAR)
        radarEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                radarSink = events
            }

            override fun onCancel(arguments: Any?) {
                radarSink = null
            }
        })

        cameraEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, CHANNEL_CAMERA)
        cameraEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                cameraSink = events
            }

            override fun onCancel(arguments: Any?) {
                cameraSink = null
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stopRadar()
        stopCameraStream()
        setTorch(false)
        methodChannel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        applyImmersiveFlags()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        applyImmersiveFlags()
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    private fun applyImmersiveFlags() {
        val act = activity ?: return
        try {
            act.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val decorView = act.window?.decorView ?: return
            val flags = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            decorView.systemUiVisibility = flags
        } catch (t: Throwable) {
            Log.w(TAG, "applyImmersiveFlags failed", t)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "startRadar" -> {
                val ok = startRadar()
                result.success(ok)
            }
            "stopRadar" -> {
                stopRadar()
                result.success(true)
            }
            "startCameraStream" -> {
                val ok = startCameraStream()
                result.success(ok)
            }
            "stopCameraStream" -> {
                stopCameraStream()
                result.success(true)
            }
            "setTorch" -> {
                val on = call.argument<Boolean>("on") ?: false
                setTorch(on)
                result.success(true)
            }
            "playStartleTone" -> {
                val freq = (call.argument<Double>("frequency") ?: 350.0).toFloat()
                val durationMs = call.argument<Int>("durationMs") ?: 1000
                playSineTone(freq, durationMs)
                vibrateMedium()
                result.success(true)
            }
            "getLatestCrashLog" -> {
                result.success(CrashHandler.getLatestCrashLog())
            }
            "clearCrashLogs" -> {
                CrashHandler.clearCrashLogs()
                result.success(true)
            }
            "recordException" -> {
                val message = call.argument<String>("message") ?: ""
                val source = call.argument<String>("source") ?: "dart"
                CrashHandler.recordHandledException(Exception(message), source)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun startRadar(): Boolean {
        if (audioRunning) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No microphone permission")
            return false
        }

        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        val bufSize = (minBuf * 4).coerceAtLeast(FFT_SIZE * 2 * 4)

        val audioSources = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioSources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        audioSources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        audioSources.add(MediaRecorder.AudioSource.MIC)

        var recorder: AudioRecord? = null
        for (src in audioSources) {
            try {
                val candidate = AudioRecord(
                    src,
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat,
                    bufSize,
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    Log.i(TAG, "AudioRecord initialized with source=$src")
                    break
                } else {
                    try { candidate.release() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord init failed source=$src", t)
            }
        }
        if (recorder == null) {
            Log.e(TAG, "Unable to initialize AudioRecord")
            return false
        }
        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            try { recorder.release() } catch (_: Throwable) {}
            return false
        }

        audioRecord = recorder
        audioRunning = true

        val thread = HandlerThread("findmos-radar").apply { start() }
        audioThread = thread
        audioHandler = Handler(thread.looper)
        audioHandler?.post(AudioRunnable(recorder))
        return true
    }

    private fun stopRadar() {
        audioRunning = false
        audioHandler?.removeCallbacksAndMessages(null)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { audioThread?.quitSafely() } catch (_: Throwable) {}
        audioThread = null
        audioHandler = null
    }

    private inner class AudioRunnable(val recorder: AudioRecord) : Runnable {
        private val hann = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
        }
        private val fftReBuf = DoubleArray(FFT_SIZE)
        private val fftImBuf = DoubleArray(FFT_SIZE)
        private val leftSamples = ShortArray(FFT_SIZE)
        private val rightSamples = ShortArray(FFT_SIZE)
        private var fillIdx = 0

        private val ccXr = DoubleArray(FFT_SIZE)
        private val ccXi = DoubleArray(FFT_SIZE)
        private val ccYr = DoubleArray(FFT_SIZE)
        private val ccYi = DoubleArray(FFT_SIZE)
        private val ccRr = DoubleArray(FFT_SIZE)
        private val ccRi = DoubleArray(FFT_SIZE)

        private var smoothedEnergy = 0.001

        override fun run() {
            if (!audioRunning) return
            val samplesPerChannel = 256
            val bytes = ByteArray(samplesPerChannel * 2 * 2)
            var offset = 0
            while (offset < bytes.size && audioRunning) {
                val read = try {
                    recorder.read(bytes, offset, bytes.size - offset)
                } catch (t: Throwable) {
                    Log.e(TAG, "read failed", t)
                    -1
                }
                if (read <= 0) break
                offset += read
            }

            var si = 0
            while (si < offset && fillIdx < FFT_SIZE) {
                val l = (bytes[si].toInt() and 0xFF) or (bytes[si + 1].toInt() shl 8)
                val r = (bytes[si + 2].toInt() and 0xFF) or (bytes[si + 3].toInt() shl 8)
                leftSamples[fillIdx] = l.toShort()
                rightSamples[fillIdx] = r.toShort()
                fillIdx++
                si += 4
            }

            if (fillIdx >= FFT_SIZE) {
                processFrame()
                leftSamples.copyInto(leftSamples, 0, FFT_SIZE / 2, FFT_SIZE)
                rightSamples.copyInto(rightSamples, 0, FFT_SIZE / 2, FFT_SIZE)
                fillIdx = FFT_SIZE / 2
            }

            audioHandler?.post(this)
        }

        private fun processFrame() {
            val leftEnergy = bandEnergy(leftSamples)
            val rightEnergy = bandEnergy(rightSamples)
            val itdSamples = crossCorrelationLag()

            smoothedEnergy = 0.95 * smoothedEnergy + 0.05 * (leftEnergy + rightEnergy) * 0.5
            val raw = ln(1.0 + (leftEnergy + rightEnergy) * 0.5 / max(smoothedEnergy, 1e-9))
            val distance = raw.coerceIn(0.0, 2.0) / 2.0

            val azimuthRad = (itdSamples / 30.0).coerceIn(-1.0, 1.0) * (PI / 3.0)
            val direction = when {
                azimuthRad < -0.15 -> "左前方"
                azimuthRad > 0.15 -> "右前方"
                else -> "正前方"
            }
            val status = when {
                distance > 0.7 -> "目标接近"
                distance > 0.3 -> "追踪中"
                else -> "监听中"
            }

            val sink = radarSink
            if (sink != null) {
                try {
                    sink.success(
                        mapOf(
                            "azimuth" to azimuthRad,
                            "distance" to distance,
                            "leftEnergy" to leftEnergy,
                            "rightEnergy" to rightEnergy,
                            "itdSamples" to itdSamples,
                            "direction" to direction,
                            "status" to status,
                        ),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "radarSink send failed", t)
                }
            }
        }

        private fun bandEnergy(samples: ShortArray): Double {
            for (i in 0 until FFT_SIZE) {
                fftReBuf[i] = (samples[i].toDouble() / 32768.0) * hann[i]
                fftImBuf[i] = 0.0
            }
            radix2FFT(fftReBuf, fftImBuf, inverse = false)
            val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE
            val iLow = (F_LOW / binHz).toInt().coerceAtLeast(1)
            val iHigh = (F_HIGH / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)
            var sum = 0.0
            for (k in iLow..iHigh) {
                val re = fftReBuf[k]
                val im = fftImBuf[k]
                val mag = kotlin.math.hypot(re, im) / (FFT_SIZE * 0.5)
                sum += mag * mag
            }
            return sum / (iHigh - iLow + 1).coerceAtLeast(1)
        }

        private fun crossCorrelationLag(): Double {
            for (i in 0 until FFT_SIZE) {
                ccXr[i] = (leftSamples[i].toDouble() / 32768.0) * hann[i]
                ccXi[i] = 0.0
                ccYr[i] = (rightSamples[i].toDouble() / 32768.0) * hann[i]
                ccYi[i] = 0.0
            }
            radix2FFT(ccXr, ccXi, inverse = false)
            radix2FFT(ccYr, ccYi, inverse = false)
            for (k in 0 until FFT_SIZE) {
                ccRr[k] = ccXr[k] * ccYr[k] + ccXi[k] * ccYi[k]
                ccRi[k] = ccXi[k] * ccYr[k] - ccXr[k] * ccYi[k]
            }
            radix2FFT(ccRr, ccRi, inverse = true)

            fun wrap(idx: Int): Int = ((idx % FFT_SIZE) + FFT_SIZE) % FFT_SIZE

            val maxItd = 40
            var best = Double.NEGATIVE_INFINITY
            var bestIdx = 0
            for (k in -maxItd..maxItd) {
                val idx = wrap(k)
                val v = ccRr[idx]
                if (v > best) {
                    best = v
                    bestIdx = k
                }
            }

            val iPrev = wrap(bestIdx - 1)
            val iCurr = wrap(bestIdx)
            val iNext = wrap(bestIdx + 1)
            val a = ccRr[iPrev]
            val b = ccRr[iCurr]
            val c = ccRr[iNext]
            val denom = a - 2 * b + c
            val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
            return bestIdx + delta
        }

        private fun radix2FFT(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = FFT_SIZE
            var target = 0
            for (pos in 1 until n) {
                var bitMask = n shr 1
                while (bitMask <= target) {
                    target = target xor bitMask
                    bitMask = bitMask shr 1
                }
                target = target xor bitMask
                if (pos < target) {
                    val tr = re[pos]; re[pos] = re[target]; re[target] = tr
                    val ti = im[pos]; im[pos] = im[target]; im[target] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val half = size shr 1
                val angleStep = if (inverse) 2.0 * PI / size else -2.0 * PI / size
                for (m in 0 until n step size) {
                    var wr = 1.0
                    var wi = 0.0
                    for (j in 0 until half) {
                        val rr = wr * re[m + j + half] - wi * im[m + j + half]
                        val ri = wr * im[m + j + half] + wi * re[m + j + half]
                        re[m + j + half] = re[m + j] - rr
                        im[m + j + half] = im[m + j] - ri
                        re[m + j] = re[m + j] + rr
                        im[m + j] = im[m + j] + ri
                        val nwr = wr * cos(angleStep) - wi * sin(angleStep)
                        val nwi = wr * sin(angleStep) + wi * cos(angleStep)
                        wr = nwr
                        wi = nwi
                    }
                }
                size = size shl 1
            }
            if (inverse) {
                val inv = 1.0 / n
                for (i in 0 until n) {
                    re[i] *= inv
                    im[i] *= inv
                }
            }
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            if (torchCameraId == null) {
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id
                        break
                    }
                }
                if (torchCameraId == null && cm.cameraIdList.isNotEmpty()) {
                    torchCameraId = cm.cameraIdList[0]
                }
            }
            val id = torchCameraId ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(id, on)
            }
        } catch (t: CameraAccessException) {
            Log.w(TAG, "torch access exception", t)
        } catch (t: Throwable) {
            Log.w(TAG, "torch failed", t)
        }
    }

    private fun playSineTone(freqHz: Float, durationMs: Int) {
        val sr = SAMPLE_RATE
        val n = (sr * durationMs / 1000).toInt().coerceIn(1024, 4 * sr)
        val samples = ShortArray(n)
        val fade = (sr * 0.02).toInt().coerceAtLeast(128)
        for (i in 0 until n) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val v = sin(2.0 * PI * freqHz * i / sr) * env
            samples[i] = (v * 16000.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        val track: AudioTrack = try {
            val bufSize = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(n * 2) * 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sr,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STATIC,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack create failed", t)
            return
        }
        try {
            track.write(samples, 0, samples.size)
            track.play()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack play failed", t)
        }
    }

    private fun vibrateMedium() {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(150)
            }
        } catch (_: Throwable) {}
    }

    private fun startCameraStream(): Boolean {
        if (cameraRunning) return true
        cameraRunning = true
        val thread = HandlerThread("findmos-camera").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
        cameraHandler?.post(CameraRunnable())
        return true
    }

    private fun stopCameraStream() {
        cameraRunning = false
        cameraHandler?.removeCallbacksAndMessages(null)
        try { cameraThread?.quitSafely() } catch (_: Throwable) {}
        cameraThread = null
        cameraHandler = null
    }

    private inner class CameraRunnable : Runnable {
        private var tick = 0

        override fun run() {
            if (!cameraRunning) return
            tick++
            val luma = IntArray(CAM_WIDTH * CAM_HEIGHT) { idx ->
                val bar = (tick * 2 + idx % CAM_WIDTH) % CAM_WIDTH
                val dx = kotlin.math.abs((idx % CAM_WIDTH) - bar)
                if (dx < 4) 220 else 100
            }
            val sink = cameraSink
            if (sink != null) {
                try {
                    sink.success(
                        mapOf(
                            "width" to CAM_WIDTH,
                            "height" to CAM_HEIGHT,
                            "luma" to luma,
                            "format" to "NV21_Y",
                        ),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "cameraSink send failed", t)
                }
            }
            cameraHandler?.postDelayed(this, CAM_PERIOD_MS)
        }
    }
}
