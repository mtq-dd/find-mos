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
    private lateinit var flutterBinding: FlutterPlugin.FlutterPluginBinding

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

    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var imageReader: android.media.ImageReader? = null
    private var cameraId: String? = null
    private var camNativeWidth = CAM_WIDTH
    private var camNativeHeight = CAM_HEIGHT

    // SurfaceTexture 用于 Flutter Texture 组件直接显示相机预览
    private var textureEntry: io.flutter.view.TextureRegistry.SurfaceTextureEntry? = null
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var previewSurface: android.view.Surface? = null

    private var torchCameraId: String? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        flutterBinding = flutterPluginBinding
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

    private var sensorOrientation = 0

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
                val textureId = startCameraStream()
                result.success(textureId)
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
            "logEvent" -> {
                val tag = call.argument<String>("tag") ?: "dart"
                val message = call.argument<String>("message") ?: ""
                CrashHandler.appendRuntime(tag, message)
                result.success(true)
            }
            "getLatestRuntimeLog" -> {
                result.success(CrashHandler.getLatestRuntimeLog())
            }
            "listRuntimeLogs" -> {
                result.success(CrashHandler.listRuntimeLogs())
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
            // camera capture session 激活时，CameraManager.setTorchMode 会报 CAMERA_IN_USE
            // 必须通过 CaptureRequest.FLASH_MODE 在 session 内部控制闪光灯
            val session = captureSession
            val camera = cameraDevice
            if (session != null && camera != null) {
                val requestBuilder = camera.createCaptureRequest(
                    android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW,
                )
                requestBuilder.addTarget(previewSurface ?: return)
                requestBuilder.addTarget(imageReader?.surface ?: return)
                // 设置自动曝光模式
                try {
                    requestBuilder.set(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON,
                    )
                } catch (_: Throwable) {}
                // 设置闪光灯模式：ON 强制开启，OFF 强制关闭
                try {
                    requestBuilder.set(
                        android.hardware.camera2.CaptureRequest.FLASH_MODE,
                        if (on) android.hardware.camera2.CaptureRequest.FLASH_MODE_TORCH
                        else android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF,
                    )
                } catch (_: Throwable) {}
                session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                CrashHandler.appendRuntime("Torch", "session setFlashMode $on ok")
                return
            }
            // fallback：session 未激活时用 CameraManager
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val id = cameraId ?: run {
                for (cid in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(cid)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return@run cid
                    }
                }
                cm.cameraIdList.firstOrNull() ?: return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(id, on)
                CrashHandler.appendRuntime("Torch", "fallback setTorchMode($id, $on) ok")
            }
        } catch (t: CameraAccessException) {
            Log.w(TAG, "torch access exception", t)
            CrashHandler.appendRuntime("Torch", "CAMERA_IN_USE error: $t")
        } catch (t: Throwable) {
            Log.w(TAG, "torch failed", t)
            CrashHandler.appendRuntime("Torch", "error: $t")
        }
    }

    private var startleTrack: AudioTrack? = null

    private fun playSineTone(freqHz: Float, durationMs: Int) {
        // 释放上一次的 track
        val old = startleTrack
        startleTrack = null
        try {
            old?.stop()
            old?.flush()
            old?.release()
        } catch (_: Throwable) {}

        val sr = SAMPLE_RATE
        val n = (sr * durationMs / 1000).toInt().coerceIn(1024, 4 * sr)
        val samples = ShortArray(n)
        val fade = (sr * 0.02).toInt().coerceAtLeast(128)
        // 800Hz 主音 + 1760Hz 泛音，手机扬声器更易播放
        val f1 = freqHz.coerceAtLeast(200f).coerceAtMost(2000f)
        val f2 = (freqHz * 2.2f).coerceAtLeast(400f).coerceAtMost(4000f)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val v = (sin(2.0 * PI * f1 * t) + 0.5 * sin(2.0 * PI * f2 * t)) * env * 0.55
            samples[i] = (v * 28000.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        val track: AudioTrack = try {
            val bufSize = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(n * 2)
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
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sr,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack create failed", t)
            return
        }
        track.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume())
        startleTrack = track

        // 独立线程分块写入，避免被 GC 提前回收 & 不阻塞主线程
        Thread {
            try {
                track.play()
                val chunk = 4096
                var offset = 0
                while (offset < samples.size) {
                    val count = kotlin.math.min(chunk, samples.size - offset)
                    track.write(samples, offset, count)
                    offset += count
                }
                Thread.sleep(100)
                try { track.stop(); track.flush(); track.release() } catch (_: Throwable) {}
                if (startleTrack === track) startleTrack = null
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack play thread failed", t)
                try { track.release() } catch (_: Throwable) {}
                if (startleTrack === track) startleTrack = null
            }
        }.apply { priority = Thread.MAX_PRIORITY }.start()
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

    private fun startCameraStream(): Long {
        // 返回 texture ID（-1 表示失败）
        if (cameraRunning && textureEntry != null) return textureEntry!!.id()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            CrashHandler.appendRuntime("Camera", "startCameraStream: no CAMERA permission")
            Log.w(TAG, "No camera permission")
            return -1L
        }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        if (cm == null) {
            CrashHandler.appendRuntime("Camera", "startCameraStream: CameraManager null")
            Log.e(TAG, "CameraManager unavailable")
            return -1L
        }
        cameraManager = cm

        // 选择后置摄像头
        var chosenId: String? = null
        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)
            val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                chosenId = id
                break
            }
        }
        if (chosenId == null && cm.cameraIdList.isNotEmpty()) {
            chosenId = cm.cameraIdList[0]
        }
        if (chosenId == null) {
            CrashHandler.appendRuntime("Camera", "startCameraStream: no camera available (no id)")
            Log.e(TAG, "No camera available")
            return -1L
        }
        cameraId = chosenId

        // 获取该摄像头支持的输出尺寸和传感器方向
        val chars = cm.getCameraCharacteristics(chosenId)
        sensorOrientation = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        CrashHandler.appendRuntime("Camera", "sensorOrientation=$sensorOrientation")
        val configMap = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (configMap != null) {
            val sizes = configMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            if (sizes != null && sizes.isNotEmpty()) {
                val target = 320 * 180
                var bestSize = sizes[0]
                var bestDiff = Long.MAX_VALUE
                for (s in sizes) {
                    val product = s.width.toLong() * s.height.toLong()
                    val diff = kotlin.math.abs(product - target)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestSize = s
                    }
                }
                camNativeWidth = bestSize.width
                camNativeHeight = bestSize.height
            }
        }
        CrashHandler.appendRuntime("Camera", "cameraId=$chosenId, nativeSize=${camNativeWidth}x$camNativeHeight")

        // 创建 SurfaceTextureEntry（供 Flutter Texture 组件直接显示）
        try {
            val entry = flutterBinding.textureRegistry.createSurfaceTexture()
            val st = entry.surfaceTexture()
            st.setDefaultBufferSize(camNativeWidth, camNativeHeight)
            textureEntry = entry
            surfaceTexture = st
            previewSurface = android.view.Surface(st)
            CrashHandler.appendRuntime("Camera", "SurfaceTextureEntry created, textureId=${entry.id()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create SurfaceTexture", t)
            CrashHandler.appendRuntime("Camera", "SurfaceTexture create failed: ${t.message}")
            return -1L
        }

        // 启动后台线程
        val thread = HandlerThread("findmos-camera").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        // 创建 ImageReader（仅用于运动检测）
        val reader = android.media.ImageReader.newInstance(
            camNativeWidth,
            camNativeHeight,
            android.graphics.ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader
        reader.setOnImageAvailableListener(CameraFrameListener(), cameraHandler)

        // 打开相机
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.openCamera(chosenId, { executor -> cameraHandler?.post(executor) }, CameraStateCallback())
            } else {
                cm.openCamera(chosenId, CameraStateCallback(), cameraHandler)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed", t)
            cameraRunning = false
            return -1L
        }
        cameraRunning = true
        return textureEntry?.id() ?: -1L
    }

    private fun stopCameraStream() {
        CrashHandler.appendRuntime("Camera", "stopCameraStream called")
        cameraRunning = false
        cameraHandler?.removeCallbacksAndMessages(null)
        try { captureSession?.abortCaptures() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
        try { surfaceTexture?.release() } catch (_: Throwable) {}
        surfaceTexture = null
        try { textureEntry?.release() } catch (_: Throwable) {}
        textureEntry = null
        try { cameraThread?.quitSafely() } catch (_: Throwable) {}
        cameraThread = null
        cameraHandler = null
    }

    private inner class CameraStateCallback : android.hardware.camera2.CameraDevice.StateCallback() {
        override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
            CrashHandler.appendRuntime("Camera", "CameraDevice.onOpened")
            cameraDevice = camera
            val reader = imageReader
            val ps = previewSurface
            if (reader == null || ps == null) return
            try {
                // 同时输出到 SurfaceTexture（显示） 和 ImageReader（运动检测）
                val surfaces = mutableListOf<android.view.Surface>()
                surfaces.add(ps)
                surfaces.add(reader.surface)
                camera.createCaptureSession(
                    surfaces,
                    object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            captureSession = session
                            try {
                                val requestBuilder = camera.createCaptureRequest(
                                    android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW,
                                )
                                requestBuilder.addTarget(ps)
                                requestBuilder.addTarget(reader.surface)
                                try {
                                    requestBuilder.set(
                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON,
                                    )
                                } catch (_: Throwable) {}
                                try {
                                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                                    CrashHandler.appendRuntime("Camera", "setRepeatingRequest OK, camera stream running (2 surfaces)")
                                } catch (t: Throwable) {
                                    CrashHandler.appendRuntime("Camera", "setRepeatingRequest failed: ${t.message}")
                                    Log.e(TAG, "setRepeatingRequest failed", t)
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "createCaptureRequest failed", t)
                                CrashHandler.appendRuntime("Camera", "createCaptureRequest failed: ${t.message}")
                            }
                        }

                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                            CrashHandler.appendRuntime("Camera", "CaptureSession onConfigureFailed")
                            Log.e(TAG, "Camera capture session configure failed")
                        }
                    },
                    cameraHandler,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "createCaptureSession failed", t)
                CrashHandler.appendRuntime("Camera", "createCaptureSession failed: ${t.message}")
            }
        }

        override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
            CrashHandler.appendRuntime("Camera", "CameraDevice onDisconnected")
            try { camera.close() } catch (_: Throwable) {}
            if (cameraDevice === camera) cameraDevice = null
        }

        override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
            CrashHandler.appendRuntime("Camera", "CameraDevice onError code=$error")
            Log.e(TAG, "Camera error: $error")
            try { camera.close() } catch (_: Throwable) {}
            if (cameraDevice === camera) cameraDevice = null
        }
    }

    private inner class CameraFrameListener : android.media.ImageReader.OnImageAvailableListener {
        private var frameCount = 0
        private var prevLuma: ByteArray? = null

        override fun onImageAvailable(reader: android.media.ImageReader) {
            if (!cameraRunning) return
            val image = try { reader.acquireLatestImage() } catch (t: Throwable) {
                Log.w(TAG, "acquireLatestImage failed", t)
                return
            }
            if (image == null) {
                // image==null 说明 ImageReader 队列积压未及时消费（不一定是错误）
                return
            }

            try {
                frameCount++
                if (frameCount == 1) CrashHandler.appendRuntime("Camera", "First frame received! img=${image.width}x${image.height}")
                if (frameCount == 30) CrashHandler.appendRuntime("Camera", "30 frames received, stream is healthy")
                if (frameCount % 300 == 0) CrashHandler.appendRuntime("Camera", "${frameCount} frames, still running")

                // Y 平面
                val yPlane = image.planes[0]
                val buffer = yPlane.buffer
                val yRowStride = yPlane.rowStride
                val yPixelStride = yPlane.pixelStride
                val srcW = image.width
                val srcH = image.height

                // 下采样到 40x22 用于轻量级运动检测
                val subW = 40
                val subH = 22
                val curr = ByteArray(subW * subH)
                for (dy in 0 until subH) {
                    val sy = (dy * srcH / subH).coerceIn(0, srcH - 1)
                    for (dx in 0 until subW) {
                        val sx = (dx * srcW / subW).coerceIn(0, srcW - 1)
                        val byteIdx = sy * yRowStride + sx * yPixelStride
                        curr[dy * subW + dx] = buffer.get(byteIdx)
                    }
                }

                // 简单运动检测（与前帧差的绝对值和）
                val prev = prevLuma
                var motionPixelCount = 0
                var minX = subW
                var minY = subH
                var maxX = -1
                var maxY = -1
                if (prev != null && prev.size == curr.size) {
                    val threshold = 10  // 降低阈值，更灵敏（原来是25）
                    for (i in curr.indices) {
                        val diff = kotlin.math.abs((curr[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                        if (diff > threshold) {
                            motionPixelCount++
                            val x = i % subW
                            val y = i / subW
                            if (x < minX) minX = x
                            if (y < minY) minY = y
                            if (x > maxX) maxX = x
                            if (y > maxY) maxY = y
                        }
                    }
                }
                prevLuma = curr

                // 每帧必发运动事件，不做节流限制
                val sink = cameraSink
                if (sink != null) {
                    try {
                        val map = mutableMapOf<String, Any?>(
                            "frameCount" to frameCount,
                            "sensorOrientation" to sensorOrientation,
                            "subW" to subW,
                            "subH" to subH,
                            "motionPixels" to motionPixelCount,
                        )
                        if (motionPixelCount > 5 && maxX >= 0) {
                            map["rects"] = listOf(
                                mapOf(
                                    "left" to minX.toDouble() / subW,
                                    "top" to minY.toDouble() / subH,
                                    "right" to (maxX + 1).toDouble() / subW,
                                    "bottom" to (maxY + 1).toDouble() / subH,
                                )
                            )
                        } else {
                            map["rects"] = emptyList<Map<String, Double>>()
                        }
                        sink.success(map)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cameraSink send failed", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "process camera frame failed", t)
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
        }
    }
}
