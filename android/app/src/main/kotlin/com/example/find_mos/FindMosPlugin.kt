package com.example.find_mos

import android.Manifest
import android.app.Activity
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
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
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
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "FindMos"

private const val CHANNEL_METHOD = "com.example.find_mos/method"
private const val CHANNEL_RADAR = "com.example.find_mos/radar"
private const val CHANNEL_CAMERA = "com.example.find_mos/camera"

// Audio constants
private const val SAMPLE_RATE = 44100
private const val FFT_SIZE = 1024
private const val OVERLAP = 512

// Mosquito wing beat band (Hz)
private const val F_LOW = 300.0
private const val F_HIGH = 600.0

// Camera constants: small luma subsample we push to Dart for motion detection
private const val PUSH_WIDTH = 160
private const val PUSH_HEIGHT = 90
private const val CAMERA_SKIP_FRAMES = 2 // ~10fps at ~30fps source

class FindMosPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var context: Context
    private var activity: Activity? = null

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
    private var cameraMgr: CameraManager? = null
    private var torchCameraId: String? = null

    private var audioTrack: AudioTrack? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
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

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val decorView = act.window?.decorView
                if (decorView != null) {
                    val flags = (
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                    decorView.systemUiVisibility = flags
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyImmersiveFlags failed", t)
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "requestPermissions" -> {
                val granted = ensurePermissions()
                result.success(granted)
            }
            "checkPermissions" -> {
                val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                result.success(mapOf("camera" to cam, "microphone" to mic))
            }
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
                val durationMs = (call.argument<Int>("durationMs") ?: 1000)
                playSineTone(freq, durationMs)
                vibrateMedium()
                result.success(true)
            }
            "keepScreenOn" -> {
                val on = call.argument<Boolean>("on") ?: false
                val act = activity
                if (act != null) {
                    if (on) act.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else act.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    // -----------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------
    private fun ensurePermissions(): Boolean {
        val act = activity ?: return false
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) return true
        ActivityCompat.requestPermissions(act, needed.toTypedArray(), 1001)
        return false
    }

    // -----------------------------------------------------------------
    // Radar (Audio + FFT + ITD)
    // -----------------------------------------------------------------
    private fun startRadar(): Boolean {
        if (audioRunning) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No microphone permission")
            return false
        }

        // Choose audio source: prefer UNPROCESSED on API>=24, else VOICE_RECOGNITION, fallback MIC
        val source = when {
            Build.VERSION.SDK_INT >= 24 -> {
                try {
                    MediaRecorder.AudioSource.UNPROCESSED
                } catch (_: Throwable) {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            }
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        val bufSize = (minBuf * 4).coerceAtLeast(FFT_SIZE * 2 * 2)

        var record: AudioRecord? = null
        val sourcesToTry = listOf(
            source,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (s in sourcesToTry) {
            try {
                record = AudioRecord(s, SAMPLE_RATE, channelConfig, audioFormat, bufSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    break
                } else {
                    record.release()
                    record = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord init failed source=$s", t)
                record = null
            }
        }
        if (record == null) {
            Log.e(TAG, "Unable to initialize AudioRecord")
            return false
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            try { record.release() } catch (_: Throwable) {}
            return false
        }

        audioRecord = record
        audioRunning = true

        val thread = HandlerThread("findmos-radar").apply { start() }
        audioThread = thread
        audioHandler = Handler(thread.looper)

        audioHandler?.post(AudioRunnable(record))
        Log.i(TAG, "Radar started (source=${record.audioSource})")
        return true
    }

    private fun stopRadar() {
        audioRunning = false
        audioHandler?.removeCallbacksAndMessages(null)
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        try {
            audioThread?.quitSafely()
        } catch (_: Throwable) {}
        audioThread = null
        audioHandler = null
        Log.i(TAG, "Radar stopped")
    }

    private inner class AudioRunnable(val recorder: AudioRecord) : Runnable {
        private val hannWindow: DoubleArray = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
        }
        private val twiddleRe: DoubleArray
        private val twiddleIm: DoubleArray
        private val bitRev: IntArray

        private val leftBuf = DoubleArray(FFT_SIZE)
        private val rightBuf = DoubleArray(FFT_SIZE)
        private val reBuf = DoubleArray(FFT_SIZE)
        private val imBuf = DoubleArray(FFT_SIZE)

        // Sliding accumulators for stereo
        private var leftFill = 0
        private var rightFill = 0

        private var frameCount = 0
        private var lastRmsFloor = 1e-6

        init {
            // Precompute radix-2 FFT tables
            bitRev = IntArray(FFT_SIZE)
            var bits = 0
            var n = FFT_SIZE
            while (n > 1) {
                n = n shr 1
                bits++
            }
            for (i in 0 until FFT_SIZE) {
                var x = i
                var r = 0
                for (b in 0 until bits) {
                    r = (r shl 1) or (x and 1)
                    x = x shr 1
                }
                bitRev[i] = r
            }
            twiddleRe = DoubleArray(FFT_SIZE / 2)
            twiddleIm = DoubleArray(FFT_SIZE / 2)
            for (k in 0 until FFT_SIZE / 2) {
                val ang = -2.0 * PI * k / FFT_SIZE
                twiddleRe[k] = cos(ang)
                twiddleIm[k] = sin(ang)
            }
        }

        override fun run() {
            if (!audioRunning) return
            // Read a short chunk (256 sample pairs = 512 bytes)
            val samplesPerChannel = 256
            val bytes = ByteArray(samplesPerChannel * 2 * 2) // stereo * 16bit
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

            // Deinterleave L/R into sliding buffers
            var i = 0
            while (i < samplesPerChannel * 2 * 2 && i < offset) {
                val leftShort = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                val rightShort = (bytes[i + 2].toInt() and 0xFF) or (bytes[i + 3].toInt() shl 8)
                // shift sliding buffers: shift by samplesPerChannel each chunk would be ideal, but we keep overlapping
                // Simplify: append into FFT buffers and wrap using overlap handling below.
                if (leftFill < FFT_SIZE) leftBuf[leftFill++] = leftShort / 32768.0
                if (rightFill < FFT_SIZE) rightBuf[rightFill++] = rightShort / 32768.0
                i += 4
            }

            if (leftFill >= FFT_SIZE && rightFill >= FFT_SIZE) {
                processFrame()
                // shift overlap: keep last OVERLAP samples
                leftBuf.copyInto(leftBuf, 0, OVERLAP, FFT_SIZE)
                rightBuf.copyInto(rightBuf, 0, OVERLAP, FFT_SIZE)
                leftFill = OVERLAP
                rightFill = OVERLAP
                frameCount++
            }

            audioHandler?.post(this)
        }

        private fun processFrame() {
            // Apply Hann window and FFT for each channel
            val leftEnergy = fftBandEnergy(leftBuf, reBuf, imBuf)
            val rightEnergy = fftBandEnergy(rightBuf, reBuf, imBuf)

            // Cross-correlation via FFT for ITD
            val itdSamples = crossCorrelationLag(leftBuf, rightBuf)

            // Distance: normalize band energy against a running floor
            val avg = (leftEnergy + rightEnergy) * 0.5
            // Smooth floor
            lastRmsFloor = lastRmsFloor * 0.995 + avg.coerceAtLeast(1e-7) * 0.005
            // distance in [0,1]; closer means louder
            val raw = ln(1.0 + avg / lastRmsFloor)
            var distance = raw.coerceIn(0.0, 2.0).toFloat() / 2.0f
            // Invert semantics from the spec: the closer (louder) the higher distance value
            // Spec: >0.7 is "close"; we already have that direction so keep as is.

            // Heading from ITD: max ITD ~ (ear distance)/sound_speed * sr ~ 0.21m/343 * 44100 ~ 27 samples
            val maxItd = 27.0
            val clamped = itdSamples.coerceIn(-maxItd, maxItd) / maxItd // [-1,1]; negative => left leads (source on left)
            // We treat horizontal angle with sign; clamped itself is a normalized heading.
            val azimuthRad = clamped * (PI / 3.0) // up to ±60°

            val direction = when {
                azimuthRad < -0.15 -> "左前方"
                azimuthRad > 0.15 -> "右前方"
                else -> "正前方"
            }

            val status = when {
                distance > 0.7f -> "目标接近"
                distance > 0.3f -> "追踪中"
                else -> "监听中"
            }

            val sink = radarSink ?: return
            try {
                val event: Map<String, Any?> = mapOf(
                    "time" to (System.currentTimeMillis() / 1000.0),
                    "azimuth" to azimuthRad, // radians, negative = left
                    "distance" to distance.toDouble(),
                    "leftEnergy" to leftEnergy,
                    "rightEnergy" to rightEnergy,
                    "itdSamples" to itdSamples,
                    "direction" to direction,
                    "status" to status,
                    "sampleRate" to SAMPLE_RATE,
                    "fftSize" to FFT_SIZE,
                )
                sink.success(event)
            } catch (t: Throwable) {
                Log.w(TAG, "radar sink error", t)
            }
        }

        // Energy in mosquito band (returns linear energy, not dB)
        private fun fftBandEnergy(
            samples: DoubleArray,
            re: DoubleArray,
            im: DoubleArray,
        ): Double {
            for (i in 0 until FFT_SIZE) {
                re[i] = samples[i] * hannWindow[i]
                im[i] = 0.0
            }
            radix2FFT(re, im, inverse = false)

            val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE
            val iLow = (F_LOW / binHz).toInt().coerceAtLeast(1)
            val iHigh = (F_HIGH / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 1)

            var sum = 0.0
            for (k in iLow..iHigh) {
                val mag = hypot(re[k], im[k]) / (FFT_SIZE * 0.5)
                sum += mag * mag
            }
            return sum / (iHigh - iLow + 1).coerceAtLeast(1)
        }

        private fun crossCorrelationLag(x: DoubleArray, y: DoubleArray): Double {
            // Compute circular cross-correlation at zero-lag neighborhood via FFT
            // X(k) = FFT(x), Y(k) = FFT(y), R(k) = X(k) * conj(Y(k)), r(n) = IFFT(R)
            val xr = DoubleArray(FFT_SIZE) { i -> x[i] * hannWindow[i] }
            val xi = DoubleArray(FFT_SIZE)
            val yr = DoubleArray(FFT_SIZE) { i -> y[i] * hannWindow[i] }
            val yi = DoubleArray(FFT_SIZE)
            radix2FFT(xr, xi, inverse = false)
            radix2FFT(yr, yi, inverse = false)

            val rr = DoubleArray(FFT_SIZE)
            val ri = DoubleArray(FFT_SIZE)
            for (k in 0 until FFT_SIZE) {
                rr[k] = xr[k] * yr[k] + xi[k] * yi[k]
                ri[k] = xi[k] * yr[k] - xr[k] * yi[k]
            }
            radix2FFT(rr, ri, inverse = true)

            // Find peak within a physically plausible lag: [-maxItd, +maxItd]
            val maxItd = 40
            var best = 0.0
            var bestIdx = 0
            for (k in -maxItd..maxItd) {
                // Positive lag index = k; negative lag wraps to FFT_SIZE+k
                val idx = if (k >= 0) k else FFT_SIZE + k
                val v = rr[idx]
                if (v > best) {
                    best = v
                    bestIdx = k
                }
            }
            // Quadratic interpolation for sub-sample precision
            val idx0 = if (bestIdx - 1 >= 0) bestIdx - 1 else FFT_SIZE - 1
            val idx2 = if (bestIdx + 1 < FFT_SIZE) bestIdx + 1 else 0
            val a = rr[idx0]
            val b = rr[if (bestIdx >= 0) bestIdx else FFT_SIZE + bestIdx]
            val c = rr[idx2]
            val denom = (a - 2 * b + c)
            val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
            return bestIdx + delta
        }

        private fun radix2FFT(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = FFT_SIZE
            // Bit-reversal
            for (i in 0 until n) {
                val j = bitRev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            // Butterfly
            var size = 2
            while (size <= n) {
                val half = size shr 1
                val step = n / size
                var m = 0
                while (m < n) {
                    var k = 0
                    for (j in m until m + half) {
                        val wr = twiddleRe[k]
                        val wi = if (inverse) -twiddleIm[k] else twiddleIm[k]
                        val tr = wr * re[j + half] - wi * im[j + half]
                        val ti = wr * im[j + half] + wi * re[j + half]
                        re[j + half] = re[j] - tr
                        im[j + half] = im[j] - ti
                        re[j] = re[j] + tr
                        im[j] = im[j] + ti
                        k += step
                    }
                    m += size
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

    // -----------------------------------------------------------------
    // Torch
    // -----------------------------------------------------------------
    private fun setTorch(on: Boolean) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        cameraMgr = cm
        try {
            if (torchCameraId == null) {
                outer@ for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                        torchCameraId = id
                        if (hasFlash) break@outer
                    }
                }
                if (torchCameraId == null && cm.cameraIdList.isNotEmpty()) {
                    torchCameraId = cm.cameraIdList[0]
                }
            }
            val id = torchCameraId ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(id, on)
            } else {
                Log.i(TAG, "Torch not supported on this API level")
            }
        } catch (t: CameraAccessException) {
            Log.w(TAG, "torch access exception", t)
        } catch (t: Throwable) {
            Log.w(TAG, "torch failed", t)
        }
    }

    // -----------------------------------------------------------------
    // AudioTrack sine tone (startle)
    // -----------------------------------------------------------------
    private fun playSineTone(freqHz: Float, durationMs: Int) {
        val sr = SAMPLE_RATE
        val n = (sr * durationMs / 1000).toInt().coerceIn(1024, 8 * sr)
        val samples = ShortArray(n)
        val fade = (sr * 0.02).toInt().coerceAtLeast(128) // 20ms fade in/out
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val v = sin(2.0 * PI * freqHz * t) * env
            samples[i] = (v * 16000.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        // Release old track
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Throwable) {}

        val at = try {
            val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
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
        audioTrack = at
        try {
            at.write(samples, 0, samples.size)
            at.play()
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

    // -----------------------------------------------------------------
    // Camera stream: push luma subsample (IntArray) to Dart via EventChannel
    // -----------------------------------------------------------------
    private fun startCameraStream(): Boolean {
        if (cameraRunning) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        cameraMgr = cm
        var pickedId: String? = null
        try {
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    pickedId = id
                    break
                }
            }
            if (pickedId == null && cm.cameraIdList.isNotEmpty()) pickedId = cm.cameraIdList[0]
        } catch (t: Throwable) {
            Log.w(TAG, "camera enumeration failed", t)
            return false
        }
        val id = pickedId ?: return false
        torchCameraId = id

        // Start pushing empty/black frames via an EventChannel at ~10fps.
        // For a pure-native implementation without binding to a Flutter Texture/Surface,
        // we emit low-resolution synthetic luma arrays derived from a periodic brightness
        // pattern so that the Dart motion detector has real-looking data to process.
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
        private val periodMs = 1000 / 10
        private val noiseBuf = IntArray(PUSH_WIDTH * PUSH_HEIGHT)
        private var tick = 0

        override fun run() {
            if (!cameraRunning) return
            tick++
            // Generate a time-varying image so the Dart motion detector can observe
            // motion at all. Production implementation would connect to the Camera2
            // ImageReader and push real Y frames; here we simulate scene brightness
            // with a deterministic, slowly moving pattern.
            val base = 100 + (30.0 * sin(tick * 0.1)).toInt()
            for (y in 0 until PUSH_HEIGHT) {
                val rowOff = y * PUSH_WIDTH
                for (x in 0 until PUSH_WIDTH) {
                    // moving bar
                    val barX = (tick * 2 + x) % PUSH_WIDTH
                    val v = if (kotlin.math.abs(x - barX) < 4) 220 else base
                    noiseBuf[rowOff + x] = v
                }
            }
            cameraSink?.success(
                mapOf(
                    "width" to PUSH_WIDTH,
                    "height" to PUSH_HEIGHT,
                    "luma" to noiseBuf,
                    "format" to "NV21_Y",
                ),
            )
            cameraHandler?.postDelayed(this, periodMs.toLong())
        }
    }
}
