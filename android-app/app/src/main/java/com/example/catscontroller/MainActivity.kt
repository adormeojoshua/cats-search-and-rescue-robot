package com.example.catscontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.catscontroller.ui.theme.CATSControllerTheme
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

val BgTop = Color(0xFF12182B)
val BgBottom = Color(0xFF060810)
val AccentBlue = Color(0xFF3BA7FF)
val AccentBlueDim = Color(0xFF1E5A94)
val StatusGreen = Color(0xFF4CD964)
val StatusRed = Color(0xFFFF4C4C)
val AmberOn = Color(0xFFFFC107)

// Shared min/max button size range, used both to clamp sizes and to map
// the 0-100% slider in the size-adjust panel.
const val MinButtonSize = 30f
const val MaxButtonSize = 160f

enum class Screen { CONNECT, CONNECTING, CONTROL, SETTINGS, EDIT_UI }

data class FracPos(val x: Float, val y: Float)

fun saveUiSettings(prefs: SharedPreferences, s: UiSettings) {
    prefs.edit().apply {
        putFloat("cameraZoom", s.cameraZoom)
        putFloat("cameraBoxPosX", s.cameraBoxPos.x); putFloat("cameraBoxPosY", s.cameraBoxPos.y)
        putFloat("cameraBoxWidth", s.cameraBoxWidth); putFloat("cameraBoxHeight", s.cameraBoxHeight)
        putFloat("forwardPosX", s.forwardPos.x); putFloat("forwardPosY", s.forwardPos.y); putFloat("forwardSize", s.forwardSize)
        putFloat("backwardPosX", s.backwardPos.x); putFloat("backwardPosY", s.backwardPos.y); putFloat("backwardSize", s.backwardSize)
        putFloat("leftPosX", s.leftPos.x); putFloat("leftPosY", s.leftPos.y); putFloat("leftSize", s.leftSize)
        putFloat("rightPosX", s.rightPos.x); putFloat("rightPosY", s.rightPos.y); putFloat("rightSize", s.rightSize)
        putFloat("lightPosX", s.lightPos.x); putFloat("lightPosY", s.lightPos.y); putFloat("lightSize", s.lightSize)
        putFloat("micPosX", s.micPos.x); putFloat("micPosY", s.micPos.y); putFloat("micSize", s.micSize)
        putFloat("capturePosX", s.capturePos.x); putFloat("capturePosY", s.capturePos.y); putFloat("captureSize", s.captureSize)
        putFloat("buzzerPosX", s.buzzerPos.x); putFloat("buzzerPosY", s.buzzerPos.y); putFloat("buzzerSize", s.buzzerSize)
        putFloat("listenPosX", s.listenPos.x); putFloat("listenPosY", s.listenPos.y); putFloat("listenSize", s.listenSize)
        apply()
    }
}

fun loadUiSettings(prefs: SharedPreferences): UiSettings {
    val d = UiSettings.defaults()
    return UiSettings(
        cameraZoom = prefs.getFloat("cameraZoom", d.cameraZoom),
        cameraBoxPos = FracPos(prefs.getFloat("cameraBoxPosX", d.cameraBoxPos.x), prefs.getFloat("cameraBoxPosY", d.cameraBoxPos.y)),
        cameraBoxWidth = prefs.getFloat("cameraBoxWidth", d.cameraBoxWidth),
        cameraBoxHeight = prefs.getFloat("cameraBoxHeight", d.cameraBoxHeight),
        forwardPos = FracPos(prefs.getFloat("forwardPosX", d.forwardPos.x), prefs.getFloat("forwardPosY", d.forwardPos.y)),
        forwardSize = prefs.getFloat("forwardSize", d.forwardSize),
        backwardPos = FracPos(prefs.getFloat("backwardPosX", d.backwardPos.x), prefs.getFloat("backwardPosY", d.backwardPos.y)),
        backwardSize = prefs.getFloat("backwardSize", d.backwardSize),
        leftPos = FracPos(prefs.getFloat("leftPosX", d.leftPos.x), prefs.getFloat("leftPosY", d.leftPos.y)),
        leftSize = prefs.getFloat("leftSize", d.leftSize),
        rightPos = FracPos(prefs.getFloat("rightPosX", d.rightPos.x), prefs.getFloat("rightPosY", d.rightPos.y)),
        rightSize = prefs.getFloat("rightSize", d.rightSize),
        lightPos = FracPos(prefs.getFloat("lightPosX", d.lightPos.x), prefs.getFloat("lightPosY", d.lightPos.y)),
        lightSize = prefs.getFloat("lightSize", d.lightSize),
        micPos = FracPos(prefs.getFloat("micPosX", d.micPos.x), prefs.getFloat("micPosY", d.micPos.y)),
        micSize = prefs.getFloat("micSize", d.micSize),
        capturePos = FracPos(prefs.getFloat("capturePosX", d.capturePos.x), prefs.getFloat("capturePosY", d.capturePos.y)),
        captureSize = prefs.getFloat("captureSize", d.captureSize),
        buzzerPos = FracPos(prefs.getFloat("buzzerPosX", d.buzzerPos.x), prefs.getFloat("buzzerPosY", d.buzzerPos.y)),
        buzzerSize = prefs.getFloat("buzzerSize", d.buzzerSize),
        listenPos = FracPos(prefs.getFloat("listenPosX", d.listenPos.x), prefs.getFloat("listenPosY", d.listenPos.y)),
        listenSize = prefs.getFloat("listenSize", d.listenSize)
    )
}

data class UiSettings(
    val cameraZoom: Float = 1f,
    // Camera feed's own position + size — adjustable in Edit UI just
    // like the buttons. Defaults to filling the whole screen (0,0 at
    // 100% width/height), matching the original fullscreen behavior.
    val cameraBoxPos: FracPos = FracPos(0f, 0f),
    val cameraBoxWidth: Float = 1f,
    val cameraBoxHeight: Float = 1f,
    val forwardPos: FracPos = FracPos(0.12f, 0.58f),
    val backwardPos: FracPos = FracPos(0.12f, 0.82f),
    val leftPos: FracPos = FracPos(0.02f, 0.70f),
    val rightPos: FracPos = FracPos(0.22f, 0.70f),
    val forwardSize: Float = 76f,
    val backwardSize: Float = 76f,
    val leftSize: Float = 76f,
    val rightSize: Float = 76f,
    val lightPos: FracPos = FracPos(0.76f, 0.58f),
    val micPos: FracPos = FracPos(0.90f, 0.58f),
    val capturePos: FracPos = FracPos(0.76f, 0.70f),
    val buzzerPos: FracPos = FracPos(0.90f, 0.70f),
    val listenPos: FracPos = FracPos(0.76f, 0.82f),
    val lightSize: Float = 56f,
    val micSize: Float = 56f,
    val captureSize: Float = 56f,
    val buzzerSize: Float = 56f,
    val listenSize: Float = 56f
) {
    companion object {
        fun defaults() = UiSettings()
    }
}

// ------------------------------------------------------------------
// DriveUdp: sends the full held-button state to the ESP32 as a single
// byte over UDP, every 100ms (and immediately on any change).
// Bitmask: 1=forward, 2=backward, 4=left, 8=right.
// No connections, so nothing can pile up on the ESP32's web server.
// A lost packet is corrected by the next one; if the packets stop
// entirely, the ESP32's deadman stops the motors.
// ------------------------------------------------------------------
class DriveUdp(private val host: String, private val port: Int) {
    @Volatile private var mask = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setDir(dir: String, on: Boolean) {
        val bit = when (dir) {
            "forward" -> 1
            "backward" -> 2
            "left" -> 4
            "right" -> 8
            else -> 0
        }
        mask = if (on) mask or bit else mask and bit.inv()
    }

    fun clear() {
        mask = 0
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                val addr = InetAddress.getByName(host)
                var lastMask = -1
                var lastSend = 0L
                while (running) {
                    val m = mask
                    val now = System.currentTimeMillis()
                    if (m != lastMask || now - lastSend >= 100) {
                        val data = byteArrayOf(m.toByte())
                        try {
                            sock.send(DatagramPacket(data, data.size, addr, port))
                        } catch (_: Exception) {
                        }
                        lastMask = m
                        lastSend = now
                    }
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                sock?.close()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
    }
}

// ------------------------------------------------------------------
// MicStreamer: opens a raw TCP socket to the Pi and streams live
// 16kHz/mono/16-bit PCM audio from the phone mic until stop() is
// called. Independent of the ESP32 HTTP calls below.
// ------------------------------------------------------------------
class MicStreamer(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var streaming = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (streaming) return
        streaming = true
        thread = Thread {
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                socket = Socket(host, port)
                val out = socket!!.getOutputStream()
                recorder!!.startRecording()
                val buffer = ByteArray(minBuf)
                while (streaming) {
                    val read = recorder!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cleanup()
            }
        }
        thread?.start()
    }

    fun stop() {
        streaming = false
        thread?.join(500)
        cleanup()
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

// ------------------------------------------------------------------
// ListenStreamer: connects to the Pi's "listen" port and plays the
// incoming 16kHz/mono/16-bit PCM audio (captured from the Pi's USB
// mic) through the phone speaker in real time. Independent of
// MicStreamer, which sends audio the other direction.
// ------------------------------------------------------------------
class ListenStreamer(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var listening = false

    fun start() {
        if (listening) return
        listening = true
        thread = Thread {
            try {
                val sampleRate = 16000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    minBuf * 2,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                socket = Socket(host, port)
                val input = socket!!.getInputStream()
                track!!.play()
                val buffer = ByteArray(minBuf)
                while (listening) {
                    val read = input.read(buffer)
                    if (read > 0) {
                        track!!.write(buffer, 0, read)
                    } else if (read == -1) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cleanup()
            }
        }
        thread?.start()
    }

    fun stop() {
        listening = false
        thread?.join(500)
        cleanup()
    }

    private fun cleanup() {
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

class MainActivity : ComponentActivity() {
    val client = OkHttpClient()

    // ESP32 joins the Raspberry Pi's hotspot as a station with a static
    // IP (see the ESP32 sketch's WiFi.config(...) call).
    val esp32Ip = "http://10.42.0.5"
    val esp32Host = "10.42.0.5"
    val esp32UdpPort = 4210

    // Raspberry Pi — same one serving the camera feed, hosting the
    // hotspot, and running the audio server (see audio_server.py).
    val piIp = "10.42.1.1"
    val piHttpPort = 5001
    val piMicPort = 6000
    val piListenPort = 6001

    var onStatusChange: ((Boolean) -> Unit)? = null

    // Non-drive ESP32 commands (gear, light, capture, stop). These do NOT
    // touch the connection status: the ESP32 has no /light or /capture
    // route, and a 404 there used to flip the indicator to SIGNAL LOST.
    // Status now comes from pingEsp32 below.
    fun sendCommand(path: String) {
        val request = Request.Builder().url("$esp32Ip$path").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    // ------------------------------------------------------------------
    // DRIVE OVER UDP
    // Held buttons set bits in DriveUdp's mask; DriveUdp sends that mask
    // to the ESP32 every 100ms. Releasing clears the bit, and the next
    // packet carries the release. The ESP32's deadman stops the motors
    // if packets stop arriving.
    // ------------------------------------------------------------------
    private val driveUdp = DriveUdp(esp32Host, esp32UdpPort)

    fun startHold(dir: String) = driveUdp.setDir(dir, true)
    fun stopHold(dir: String) = driveUdp.setDir(dir, false)

    fun stopAllHolds() {
        driveUdp.clear()
        sendCommand("/stop")
    }

    override fun onPause() {
        super.onPause()
        stopAllHolds()
    }

    override fun onDestroy() {
        super.onDestroy()
        driveUdp.stop()
    }

    // Lightweight ESP32 reachability check, used for the status indicator.
    private val pingClient = OkHttpClient.Builder()
        .callTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    fun pingEsp32(callback: (Boolean) -> Unit) {
        val request = Request.Builder().url("$esp32Ip/ping").build()
        pingClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    fun sendPiAnnounce(mode: String) {
        val request = Request.Builder()
            .url("http://$piIp:$piHttpPort/announce?mode=$mode")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    // There's no physical buzzer on the robot, so this hits the Pi's
    // audio server instead of the ESP32 — it loops a synthesized alarm
    // tone through the Pi's USB speaker until told to stop (see
    // /buzzer in audio_server.py).
    fun sendPiBuzzer(on: Boolean, callback: (Boolean) -> Unit = {}) {
        val request = Request.Builder()
            .url("http://$piIp:$piHttpPort/buzzer?val=${if (on) 1 else 0}")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    // Uses the tiny /ping route instead of loading the whole control page.
    fun testConnection(callback: (Boolean) -> Unit) {
        val request = Request.Builder().url("$esp32Ip/ping").build()
        pingClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    // Tells the Pi to power itself off safely (see /shutdown route in
    // audio_server.py — requires passwordless sudo for shutdown, see setup notes).
    fun sendPiShutdown(callback: (Boolean) -> Unit) {
        val request = Request.Builder()
            .url("http://$piIp:$piHttpPort/shutdown")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    // Cheap reachability check for the audio server (announce/mic/listen/buzzer).
    fun checkPiAudioReady(callback: (Boolean) -> Unit) {
        val request = Request.Builder().url("http://$piIp:$piHttpPort/health").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    // Cheap reachability check for the camera stream. Hits a lightweight
    // /camera_health route (see live5.py) rather than /video_feed itself —
    // opening/closing a second connection to /video_feed every 2s was
    // interrupting the WebView's live MJPEG stream on Flask's default
    // single-threaded dev server, causing pink/glitched frames and an
    // eventual freeze after repeated collisions.
    fun checkCameraReady(callback: (Boolean) -> Unit) {
        val request = Request.Builder().url("http://$piIp:5000/camera_health").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    // True only once both the camera stream and the audio server
    // (announce/talk/listen/buzzer) are confirmed reachable.
    fun checkPiReady(callback: (Boolean) -> Unit) {
        checkCameraReady { camOk ->
            checkPiAudioReady { audioOk ->
                callback(camOk && audioOk)
            }
        }
    }

    // Reads the Pi speaker's current ALSA volume (0-100), used to
    // initialize the slider in Settings. Returns null on failure.
    fun getPiVolume(callback: (Int?) -> Unit) {
        val request = Request.Builder().url("http://$piIp:$piHttpPort/volume").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    val level = body?.let {
                        Regex("\"level\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    callback(level)
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback(null)
                } finally {
                    response.close()
                }
            }
        })
    }

    // Sets the Pi speaker's ALSA volume (0-100).
    fun setPiVolume(level: Int, callback: (Boolean) -> Unit = {}) {
        val clamped = level.coerceIn(0, 100)
        val request = Request.Builder()
            .url("http://$piIp:$piHttpPort/volume?level=$clamped")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
                response.close()
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the UDP drive sender (idles at mask 0 until a button is held).
        driveUdp.start()

        setContent {
            CATSControllerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(
                        modifier = Modifier.padding(innerPadding).fillMaxSize(),
                        color = BgBottom
                    ) {
                        var screen by remember { mutableStateOf(Screen.CONNECT) }
                        var connectFailed by remember { mutableStateOf(false) }
                        var isConnected by remember { mutableStateOf<Boolean?>(null) }
                        val prefs = remember { getSharedPreferences("cats_ui_settings", MODE_PRIVATE) }
                        var settings by remember { mutableStateOf(loadUiSettings(prefs)) }

                        // Currently selected gear (1-3): 1=Low, 2=Normal, 3=Sports.
                        var selectedGear by remember { mutableStateOf(2) }

                        // Mic streaming: phone mic -> Pi USB speaker over raw TCP.
                        val micStreamer = remember { MicStreamer(piIp, piMicPort) }

                        // Listen streaming: Pi USB mic -> phone speaker over raw TCP.
                        val listenStreamer = remember { ListenStreamer(piIp, piListenPort) }

                        val requestMicPermission = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { /* granted or not — mic button just won't stream if denied */ }

                        LaunchedEffect(Unit) {
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }

                        // Storage permission is only needed pre-Android 10 (API 29) to
                        // save photos into the gallery — Android 10+ uses scoped
                        // storage via MediaStore and doesn't require it.
                        val requestStoragePermission = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { /* granted or not — capture button just won't save if denied */ }

                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }

                        // Bumped every time the app comes back to the foreground.
                        // Passed down to the camera feed so it can force a
                        // fresh reconnect of the MJPEG stream, since Android
                        // suspends that connection while the app is backgrounded.
                        var reloadTrigger by remember { mutableStateOf(0) }

                        val lifecycleOwner = LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    // Re-check the ESP32 connection immediately instead
                                    // of waiting for the next poll — fixes the status
                                    // indicator getting stuck on "SIGNAL LOST"
                                    // after returning from the background.
                                    testConnection { success -> isConnected = success }
                                    reloadTrigger++
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }

                        // ESP32 status: UDP gives no replies, so poll the tiny
                        // /ping route every 2s while on the control screen.
                        // Two misses in a row are needed before SIGNAL LOST.
                        LaunchedEffect(screen) {
                            if (screen == Screen.CONTROL) {
                                var fails = 0
                                while (true) {
                                    pingEsp32 { ok ->
                                        if (ok) {
                                            fails = 0
                                            isConnected = true
                                        } else if (++fails >= 2) {
                                            isConnected = false
                                        }
                                    }
                                    delay(2000)
                                }
                            }
                        }

                        // True once the Pi's camera stream AND audio server are both
                        // confirmed reachable. The ESP32 (drive/lights) is a
                        // separate board and is usually ready first, so we don't
                        // block those controls on this — only the camera view and
                        // mic/listen/buzzer buttons wait on it.
                        var piReady by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            while (true) {
                                checkPiReady { ready -> piReady = ready }
                                delay(2000)
                            }
                        }

                        // Shutdown flow: confirm -> tell the Pi to power off -> show
                        // a countdown so the person waits a safe amount of time
                        // before cutting power, instead of yanking it mid-shutdown.
                        var showShutdownConfirm by remember { mutableStateOf(false) }
                        var shutdownInProgress by remember { mutableStateOf(false) }
                        var shutdownSecondsLeft by remember { mutableStateOf(15) }

                        LaunchedEffect(shutdownInProgress) {
                            if (shutdownInProgress) {
                                for (i in 15 downTo 0) {
                                    shutdownSecondsLeft = i
                                    delay(1000)
                                }
                                // Pi should be fully powered off by now — reset the
                                // app back to a fresh Connect screen.
                                shutdownInProgress = false
                                piReady = false
                                isConnected = null
                                screen = Screen.CONNECT
                            }
                        }

                        // Pi speaker volume (0-100), shown as a slider in Settings.
                        // Fetched fresh each time Settings is opened so it reflects
                        // the Pi's actual current level.
                        var volumeLevel by remember { mutableStateOf(70f) }

                        LaunchedEffect(screen) {
                            if (screen == Screen.SETTINGS) {
                                getPiVolume { level -> if (level != null) volumeLevel = level.toFloat() }
                            }
                        }

                        onStatusChange = { success -> isConnected = success }

                        val persistSettings: (UiSettings) -> Unit = { updated ->
                            settings = updated
                            saveUiSettings(prefs, updated)
                        }

                        when (screen) {
                            Screen.CONNECT -> ConnectScreen(
                                failed = connectFailed,
                                onConnectClick = {
                                    connectFailed = false
                                    screen = Screen.CONNECTING
                                }
                            )

                            Screen.CONNECTING -> ConnectingScreen(
                                onResult = { success ->
                                    if (success) {
                                        isConnected = true
                                        screen = Screen.CONTROL
                                    } else {
                                        connectFailed = true
                                        screen = Screen.CONNECT
                                    }
                                },
                                testConnection = { cb -> testConnection(cb) }
                            )

                            Screen.CONTROL -> ControlScreen(
                                isConnected = isConnected,
                                settings = settings,
                                editable = false,
                                reloadTrigger = reloadTrigger,
                                piReady = piReady,
                                piIp = piIp,
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onSettingsChange = persistSettings,
                                onSave = {},
                                // Held buttons set bits in the UDP mask (see DriveUdp),
                                // which is sent to the ESP32 every 100ms.
                                onPress = { dir -> startHold(dir) },
                                onRelease = { dir -> stopHold(dir) },
                                onLightToggle = { on -> sendCommand("/light?val=${if (on) 1 else 0}") },
                                onMicToggle = { on -> if (on) micStreamer.start() else micStreamer.stop() },
                                onListenToggle = { on -> if (on) listenStreamer.start() else listenStreamer.stop() },
                                onCapture = { sendCommand("/capture") },
                                // No physical buzzer exists — this now hits the Pi's
                                // audio server, which loops a synthesized alarm tone
                                // through the USB speaker until toggled off.
                                onBuzzerToggle = { on -> sendPiBuzzer(on) },
                                selectedGear = selectedGear,
                                onGearSelect = { level ->
                                    selectedGear = level
                                    sendCommand("/gear?level=$level")
                                    sendPiAnnounce(gearModeIds[level] ?: "normal")
                                }
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onSettingsChange = persistSettings,
                                onEditUi = { screen = Screen.EDIT_UI },
                                onShutdownClick = { showShutdownConfirm = true },
                                onBack = { screen = Screen.CONTROL },
                                volumeLevel = volumeLevel,
                                onVolumeChange = { volumeLevel = it },
                                onVolumeChangeFinished = { setPiVolume(it.toInt()) }
                            )

                            Screen.EDIT_UI -> ControlScreen(
                                isConnected = isConnected,
                                settings = settings,
                                editable = true,
                                reloadTrigger = reloadTrigger,
                                piReady = true,
                                piIp = piIp,
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onSettingsChange = persistSettings,
                                onSave = { screen = Screen.CONTROL },
                                onPress = {},
                                onRelease = {},
                                onLightToggle = {},
                                onMicToggle = {},
                                onListenToggle = {},
                                onCapture = {},
                                onBuzzerToggle = {},
                                selectedGear = selectedGear,
                                onGearSelect = {}
                            )
                        }

                        if (showShutdownConfirm) {
                            AlertDialog(
                                onDismissRequest = { showShutdownConfirm = false },
                                title = { Text("Shut down C.A.T.S.?", fontWeight = FontWeight.Bold) },
                                text = {
                                    Text(
                                        "This safely powers off the Raspberry Pi. " +
                                                "You'll need to physically restart it to reconnect."
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showShutdownConfirm = false
                                            sendPiShutdown {}
                                            shutdownInProgress = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                                    ) {
                                        Text("Shut Down", color = Color.White)
                                    }
                                },
                                dismissButton = {
                                    Button(onClick = { showShutdownConfirm = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        if (shutdownInProgress) {
                            AlertDialog(
                                onDismissRequest = { /* not dismissible mid-shutdown */ },
                                title = { Text("Shutting down safely…", fontWeight = FontWeight.Bold) },
                                text = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = AccentBlue)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            if (shutdownSecondsLeft > 0)
                                                "Please wait ${shutdownSecondsLeft}s before disconnecting power."
                                            else
                                                "Safe to disconnect power now."
                                        )
                                    }
                                },
                                confirmButton = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundGradient(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(BgTop, BgBottom)))
    ) {
        content()
    }
}

@Composable
fun ConnectScreen(failed: Boolean, onConnectClick: () -> Unit) {
    BackgroundGradient {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "C.A.T.S.",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.shadow(
                    elevation = 12.dp,
                    ambientColor = AccentBlue,
                    spotColor = AccentBlue
                )
            )

            Text(
                "SEARCH & RESCUE ROBOTIC UNIT",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onConnectClick,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                modifier = Modifier
                    .height(52.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(50),
                        ambientColor = AccentBlue,
                        spotColor = AccentBlue
                    )
            ) {
                Text(
                    "  CONNECT  ",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (failed) {
                Text(
                    "Couldn't reach C.A.T.S. — check you're on the CATS-Hotspot WiFi",
                    color = StatusRed,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }
    }
}

@Composable
fun ConnectingScreen(
    onResult: (Boolean) -> Unit,
    testConnection: ((Boolean) -> Unit) -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var result: Boolean? = null
        testConnection { success -> result = success }

        while (progress < 0.95f) {
            delay(40)
            progress += 0.02f
        }

        var waited = 0
        while (result == null && waited < 3000) {
            delay(100)
            waited += 100
        }

        progress = 1f
        delay(200)
        onResult(result ?: false)
    }

    BackgroundGradient {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "C.A.T.S.",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.shadow(
                    elevation = 12.dp,
                    ambientColor = AccentBlue,
                    spotColor = AccentBlue
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Connecting to C.A.T.S... ${(progress * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = AccentBlue,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }
    }
}

@Composable
fun StatusIndicator(isConnected: Boolean?) {
    val dotColor = when (isConnected) {
        true -> StatusGreen
        false -> StatusRed
        null -> Color.Gray
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = dotColor,
                    spotColor = dotColor
                )
                .background(color = dotColor, shape = CircleShape)
        )

        Spacer(modifier = Modifier.padding(4.dp))

        Text(
            text = when (isConnected) {
                true -> "CONNECTED"
                false -> "SIGNAL LOST"
                null -> "STANDBY"
            },
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ------------------------------------------------------------------
// Modes button: tap to open a popup with Low Gear / Normal Mode /
// Sports Mode. Selecting one calls onModeSelected(gear) which the
// caller wires to the existing ESP32 /gear route.
// ------------------------------------------------------------------
val gearModeNames = mapOf(1 to "LOW GEAR", 2 to "NORMAL MODE", 3 to "SPORTS MODE")
val gearModeIds = mapOf(1 to "low", 2 to "normal", 3 to "sports")

@Composable
fun ModesButton(selectedGear: Int, onModeSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Button(
        onClick = { expanded = true },
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        modifier = Modifier.height(38.dp)
    ) {
        Text(
            gearModeNames[selectedGear] ?: "MODES",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Select Mode", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    (1..3).forEach { gear ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .pointerInput(gear) {
                                    detectTapGestures(onTap = {
                                        onModeSelected(gear)
                                        expanded = false
                                    })
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Gear $gear — ${gearModeNames[gear]}",
                                fontWeight = if (gear == selectedGear) FontWeight.Bold else FontWeight.Normal,
                                color = if (gear == selectedGear) AccentBlue else Color.Unspecified
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { expanded = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun CircleHoldButton(
    symbol: String,
    dir: String,
    size: Float,
    editable: Boolean,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = tween(120),
        label = "buttonScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .shadow(
                elevation = if (isPressed) 2.dp else 12.dp,
                shape = CircleShape,
                ambientColor = AccentBlue,
                spotColor = AccentBlue
            )
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = if (isPressed)
                        listOf(AccentBlue, AccentBlueDim)
                    else
                        listOf(AccentBlueDim, Color(0xFF162238))
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        AccentBlue.copy(alpha = 0.9f),
                        Color.White.copy(alpha = 0.15f)
                    )
                ),
                shape = CircleShape
            )
            .then(
                if (!editable) {
                    Modifier.pointerInput(dir) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                onPress(dir)
                                tryAwaitRelease()
                                isPressed = false
                                onRelease(dir)
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Text(
            symbol,
            fontSize = (size * 0.33f).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun ToggleIconButton(
    symbol: String,
    isOn: Boolean,
    size: Float,
    editable: Boolean,
    activeColor: Color = AmberOn,
    onToggle: (Boolean) -> Unit
) {
    val latestIsOn = rememberUpdatedState(isOn)
    val latestOnToggle = rememberUpdatedState(onToggle)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .shadow(
                elevation = if (isOn) 10.dp else 2.dp,
                shape = CircleShape,
                ambientColor = activeColor,
                spotColor = activeColor
            )
            .clip(CircleShape)
            .background(if (isOn) activeColor else Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .then(
                if (!editable) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                latestOnToggle.value(!latestIsOn.value)
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Text(
            symbol,
            fontSize = (size * 0.38f).sp,
            color = if (isOn) Color.Black else Color.White
        )
    }
}

@Composable
fun ActionIconButton(
    symbol: String,
    size: Float,
    editable: Boolean,
    onTap: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(100),
        label = "iconScale"
    )

    val latestOnTap = rememberUpdatedState(onTap)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .then(
                if (!editable) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            },
                            onTap = {
                                latestOnTap.value()
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Text(
            symbol,
            fontSize = (size * 0.38f).sp,
            color = Color.White
        )
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Text("⚙", fontSize = 18.sp, color = Color.White)
    }
}

@Composable
fun BoxWithConstraintsScope.EditableButtonSlot(
    id: String,
    pos: FracPos,
    size: Float,
    editable: Boolean,
    isSelected: Boolean,
    onPosChange: (FracPos) -> Unit,
    onSelect: (String) -> Unit,
    content: @Composable (Float) -> Unit
) {
    val density = LocalDensity.current
    val maxW = maxWidth
    val maxH = maxHeight
    val latestPos = rememberUpdatedState(pos)
    val latestOnPosChange = rememberUpdatedState(onPosChange)

    Box(
        modifier = Modifier.offset(
            x = maxW * pos.x,
            y = maxH * pos.y
        )
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (editable) {
                        Modifier
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected)
                                    AccentBlue
                                else
                                    AccentBlue.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .pointerInput(id) {
                                detectTapGestures(
                                    onTap = {
                                        onSelect(id)
                                    }
                                )
                            }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress { change, dragAmount ->
                                    change.consume()

                                    val dxFrac =
                                        with(density) {
                                            dragAmount.x.toDp()
                                        } / maxW

                                    val dyFrac =
                                        with(density) {
                                            dragAmount.y.toDp()
                                        } / maxH

                                    val current = latestPos.value

                                    latestOnPosChange.value(
                                        FracPos(
                                            (current.x + dxFrac)
                                                .coerceIn(0f, 0.92f),
                                            (current.y + dyFrac)
                                                .coerceIn(0f, 0.92f)
                                        )
                                    )
                                }
                            }
                    } else Modifier
                )
        ) {
            content(size)
        }
    }
}

// ------------------------------------------------------------------
// CameraBoxFrame: purely positions/sizes the camera content — no
// gestures here. Stays composed early (as the background), same as
// before.
// ------------------------------------------------------------------
@Composable
fun BoxWithConstraintsScope.CameraBoxFrame(
    pos: FracPos,
    widthFrac: Float,
    heightFrac: Float,
    content: @Composable () -> Unit
) {
    val maxW = maxWidth
    val maxH = maxHeight
    Box(
        modifier = Modifier
            .offset(x = maxW * pos.x, y = maxH * pos.y)
            .size(width = maxW * widthFrac, height = maxH * heightFrac)
    ) {
        content()
    }
}

// ------------------------------------------------------------------
// CameraBoxHandles: the move/resize border + handles for the camera
// box, matching its position exactly. Deliberately composed AFTER the
// darkening gradient overlay (same as the button EditableButtonSlots)
// so it gets touch priority — the gradient overlay's full-screen
// tap-to-deselect gesture otherwise sits on top and swallows every
// touch, which is why dragging silently did nothing before.
// ------------------------------------------------------------------
@Composable
fun BoxWithConstraintsScope.CameraBoxHandles(
    pos: FracPos,
    widthFrac: Float,
    heightFrac: Float,
    onPosChange: (FracPos) -> Unit,
    onSizeChange: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val maxW = maxWidth
    val maxH = maxHeight
    val latestPos = rememberUpdatedState(pos)
    val latestWidth = rememberUpdatedState(widthFrac)
    val latestHeight = rememberUpdatedState(heightFrac)
    val latestOnPosChange = rememberUpdatedState(onPosChange)
    val latestOnSizeChange = rememberUpdatedState(onSizeChange)

    Box(
        modifier = Modifier
            .offset(x = maxW * pos.x, y = maxH * pos.y)
            .size(width = maxW * widthFrac, height = maxH * heightFrac)
            .border(2.dp, AccentBlue.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
    ) {
        // Move handle — top-left corner. Long-press + drag, same
        // gesture as repositioning a button.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(AccentBlue)
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, dragAmount ->
                        change.consume()
                        val dxFrac = with(density) { dragAmount.x.toDp() } / maxW
                        val dyFrac = with(density) { dragAmount.y.toDp() } / maxH
                        val current = latestPos.value
                        latestOnPosChange.value(
                            FracPos(
                                (current.x + dxFrac).coerceIn(0f, 1f - latestWidth.value),
                                (current.y + dyFrac).coerceIn(0f, 1f - latestHeight.value)
                            )
                        )
                    }
                }
        ) {
            Text("✥", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }

        // Resize handle — bottom-right corner. Plain drag (no long
        // press needed) since it's a distinct small hit target.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(StatusGreen)
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dwFrac = with(density) { dragAmount.x.toDp() } / maxW
                        val dhFrac = with(density) { dragAmount.y.toDp() } / maxH
                        val current = latestPos.value
                        val newWidth = (latestWidth.value + dwFrac).coerceIn(0.2f, 1f - current.x)
                        val newHeight = (latestHeight.value + dhFrac).coerceIn(0.2f, 1f - current.y)
                        latestOnSizeChange.value(newWidth, newHeight)
                    }
                }
        ) {
            Text("⤡", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun BoxWithConstraintsScope.SizeAdjustPanel(
    label: String,
    size: Float,
    minSize: Float,
    maxSize: Float,
    onSizeChange: (Float) -> Unit,
    onClose: () -> Unit
) {
    val percent =
        (((size - minSize) / (maxSize - minSize)) * 100f)
            .coerceIn(0f, 100f)

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = AccentBlue,
                spotColor = AccentBlue
            )
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF10192E))
            .border(
                1.dp,
                AccentBlue.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp)
            )
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$label size — ${percent.toInt()}%",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    onClose()
                                }
                            )
                        }
                ) {
                    Text(
                        "✕",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = percent,
                onValueChange = { newPercent ->
                    val newSize =
                        minSize +
                                (newPercent / 100f) *
                                (maxSize - minSize)

                    onSizeChange(newSize)
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentBlue,
                    activeTrackColor = AccentBlue,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                )
            )
        }
    }
}

@Composable
fun ControlScreen(
    isConnected: Boolean?,
    settings: UiSettings,
    editable: Boolean,
    reloadTrigger: Int,
    piReady: Boolean,
    piIp: String,
    onOpenSettings: () -> Unit,
    onSettingsChange: (UiSettings) -> Unit,
    onSave: () -> Unit,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    onLightToggle: (Boolean) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onListenToggle: (Boolean) -> Unit,
    onCapture: () -> Unit,
    onBuzzerToggle: (Boolean) -> Unit,
    selectedGear: Int,
    onGearSelect: (Int) -> Unit
) {
    var lightOn by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
    var listenOn by remember { mutableStateOf(false) }
    var buzzerOn by remember { mutableStateOf(false) }
    var selectedSlotId by remember { mutableStateOf<String?>(null) }

    // Holds a reference to the live camera WebView so the capture
    // button can screenshot whatever it's currently displaying.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    val slotSizeInfo: Map<String, Triple<String, Float, (Float) -> Unit>> =
        mapOf(
            "forward" to Triple(
                "Forward",
                settings.forwardSize
            ) { v: Float ->
                onSettingsChange(settings.copy(forwardSize = v))
            },
            "backward" to Triple(
                "Backward",
                settings.backwardSize
            ) { v: Float ->
                onSettingsChange(settings.copy(backwardSize = v))
            },
            "left" to Triple(
                "Left",
                settings.leftSize
            ) { v: Float ->
                onSettingsChange(settings.copy(leftSize = v))
            },
            "right" to Triple(
                "Right",
                settings.rightSize
            ) { v: Float ->
                onSettingsChange(settings.copy(rightSize = v))
            },
            "light" to Triple(
                "Light",
                settings.lightSize
            ) { v: Float ->
                onSettingsChange(settings.copy(lightSize = v))
            },
            "mic" to Triple(
                "Mic",
                settings.micSize
            ) { v: Float ->
                onSettingsChange(settings.copy(micSize = v))
            },
            "capture" to Triple(
                "Capture",
                settings.captureSize
            ) { v: Float ->
                onSettingsChange(settings.copy(captureSize = v))
            },
            "buzzer" to Triple(
                "Buzzer",
                settings.buzzerSize
            ) { v: Float ->
                onSettingsChange(settings.copy(buzzerSize = v))
            },
            "listen" to Triple(
                "Listen",
                settings.listenSize
            ) { v: Float ->
                onSettingsChange(settings.copy(listenSize = v))
            }
        )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        // Camera feed now lives in its own draggable/resizable box,
        // same idea as the buttons — editable only in Edit UI mode.
        // This is just the frame + content (no gestures); the
        // move/resize handles are composed separately below, after
        // the gradient overlay, so they actually receive touches.
        CameraBoxFrame(
            pos = settings.cameraBoxPos,
            widthFrac = settings.cameraBoxWidth,
            heightFrac = settings.cameraBoxHeight
        ) {
            // While the Pi is still booting (camera/audio server not yet
            // reachable), show a loading placeholder instead of a WebView
            // that would just fail to connect repeatedly.
            if (piReady) {
                MjpegCameraFeed(
                    url = "http://$piIp:5000/video_feed",
                    reloadTrigger = reloadTrigger,
                    modifier = Modifier.fillMaxSize(),
                    onWebViewReady = { webViewRef = it }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Booting up C.A.T.S. camera & audio systems…",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "This can take up to a minute after power-on.\nDriving controls work already.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
                .then(
                    if (editable) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    selectedSlotId = null
                                }
                            )
                        }
                    } else Modifier
                )
        )

        // Composed AFTER the gradient overlay above, so its handles get
        // touch priority over the gradient's full-screen tap-to-deselect
        // gesture — same reason the button handles below already work.
        if (editable) {
            CameraBoxHandles(
                pos = settings.cameraBoxPos,
                widthFrac = settings.cameraBoxWidth,
                heightFrac = settings.cameraBoxHeight,
                onPosChange = { onSettingsChange(settings.copy(cameraBoxPos = it)) },
                onSizeChange = { w, h -> onSettingsChange(settings.copy(cameraBoxWidth = w, cameraBoxHeight = h)) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = 24.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsButton(onClick = onOpenSettings)

                Spacer(modifier = Modifier.padding(6.dp))

                if (!editable) {
                    StatusIndicator(isConnected)
                } else {
                    Text(
                        "EDIT MODE — tap to resize, long-press + drag to move",
                        color = AccentBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (editable) {
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusGreen
                    ),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(
                        "SAVE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                ModesButton(
                    selectedGear = selectedGear,
                    onModeSelected = onGearSelect
                )
            }
        }

        EditableButtonSlot(
            id = "forward",
            pos = settings.forwardPos,
            size = settings.forwardSize,
            editable = editable,
            isSelected = selectedSlotId == "forward",
            onPosChange = {
                onSettingsChange(settings.copy(forwardPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            CircleHoldButton(
                "▲",
                "forward",
                sz,
                editable,
                onPress,
                onRelease
            )
        }

        EditableButtonSlot(
            id = "backward",
            pos = settings.backwardPos,
            size = settings.backwardSize,
            editable = editable,
            isSelected = selectedSlotId == "backward",
            onPosChange = {
                onSettingsChange(settings.copy(backwardPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            CircleHoldButton(
                "▼",
                "backward",
                sz,
                editable,
                onPress,
                onRelease
            )
        }

        EditableButtonSlot(
            id = "left",
            pos = settings.leftPos,
            size = settings.leftSize,
            editable = editable,
            isSelected = selectedSlotId == "left",
            onPosChange = {
                onSettingsChange(settings.copy(leftPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            CircleHoldButton(
                "◀",
                "left",
                sz,
                editable,
                onPress,
                onRelease
            )
        }

        EditableButtonSlot(
            id = "right",
            pos = settings.rightPos,
            size = settings.rightSize,
            editable = editable,
            isSelected = selectedSlotId == "right",
            onPosChange = {
                onSettingsChange(settings.copy(rightPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            CircleHoldButton(
                "▶",
                "right",
                sz,
                editable,
                onPress,
                onRelease
            )
        }

        EditableButtonSlot(
            id = "light",
            pos = settings.lightPos,
            size = settings.lightSize,
            editable = editable,
            isSelected = selectedSlotId == "light",
            onPosChange = {
                onSettingsChange(settings.copy(lightPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            ToggleIconButton(
                "💡",
                lightOn,
                sz,
                editable
            ) { on ->
                lightOn = on
                onLightToggle(on)
            }
        }

        EditableButtonSlot(
            id = "mic",
            pos = settings.micPos,
            size = settings.micSize,
            editable = editable,
            isSelected = selectedSlotId == "mic",
            onPosChange = {
                onSettingsChange(settings.copy(micPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            ToggleIconButton(
                "🎤",
                micOn,
                sz,
                editable
            ) { on ->
                micOn = on
                onMicToggle(on)
            }
        }

        EditableButtonSlot(
            id = "capture",
            pos = settings.capturePos,
            size = settings.captureSize,
            editable = editable,
            isSelected = selectedSlotId == "capture",
            onPosChange = {
                onSettingsChange(settings.copy(capturePos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            ActionIconButton(
                "📷",
                sz,
                editable
            ) {
                // Keep the existing ESP32 command (e.g. shutter LED/sound on
                // the robot itself), plus actually grab and save a photo.
                onCapture()

                val webView = webViewRef
                if (webView == null) {
                    Toast.makeText(context, "Camera not ready yet", Toast.LENGTH_SHORT).show()
                } else {
                    val bitmap = captureWebViewSnapshot(webView)
                    if (bitmap != null && saveBitmapToGallery(context, bitmap)) {
                        Toast.makeText(context, "Saved to CATS Robot album", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Couldn't save photo", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        EditableButtonSlot(
            id = "buzzer",
            pos = settings.buzzerPos,
            size = settings.buzzerSize,
            editable = editable,
            isSelected = selectedSlotId == "buzzer",
            onPosChange = {
                onSettingsChange(settings.copy(buzzerPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            ToggleIconButton(
                "🔔",
                buzzerOn,
                sz,
                editable,
                activeColor = StatusRed
            ) { on ->
                buzzerOn = on
                onBuzzerToggle(on)
            }
        }

        EditableButtonSlot(
            id = "listen",
            pos = settings.listenPos,
            size = settings.listenSize,
            editable = editable,
            isSelected = selectedSlotId == "listen",
            onPosChange = {
                onSettingsChange(settings.copy(listenPos = it))
            },
            onSelect = {
                selectedSlotId = it
            }
        ) { sz ->
            ToggleIconButton(
                "🔊",
                listenOn,
                sz,
                editable
            ) { on ->
                listenOn = on
                onListenToggle(on)
            }
        }

        if (editable && selectedSlotId != null) {
            val info = slotSizeInfo[selectedSlotId]

            if (info != null) {
                val (label, currentSize, onSizeChange) = info

                SizeAdjustPanel(
                    label = label,
                    size = currentSize,
                    minSize = MinButtonSize,
                    maxSize = MaxButtonSize,
                    onSizeChange = onSizeChange,
                    onClose = {
                        selectedSlotId = null
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            "$label: ${"%.1f".format(value)}",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AccentBlue,
                activeTrackColor = AccentBlue,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
fun SettingsScreen(
    settings: UiSettings,
    onSettingsChange: (UiSettings) -> Unit,
    onEditUi: () -> Unit,
    onShutdownClick: () -> Unit,
    onBack: () -> Unit,
    volumeLevel: Float,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: (Float) -> Unit
) {
    BackgroundGradient {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "SETTINGS",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "Done",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onEditUi,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    "✥  EDIT USER INTERFACE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSlider(
                label = "Camera feed zoom",
                value = settings.cameraZoom,
                range = 1f..2f,
                onValueChange = {
                    onSettingsChange(
                        settings.copy(cameraZoom = it)
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                Text(
                    "C.A.T.S. volume: ${volumeLevel.toInt()}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Slider(
                    value = volumeLevel,
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = { onVolumeChangeFinished(volumeLevel) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentBlue,
                        activeTrackColor = AccentBlue,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    )
                )

                Text(
                    "Controls the robot's speaker — announcements, talk, listen, and buzzer.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    onSettingsChange(UiSettings.defaults())
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRed.copy(alpha = 0.8f)
                )
            ) {
                Text(
                    "Reset everything to default",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onShutdownClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRed
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    "⏻  SHUT DOWN C.A.T.S.",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Safely powers off the Raspberry Pi. Wait for the on-screen countdown before disconnecting power.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "Layout and settings are saved automatically and will be here next time you open the app.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
    }
}

// ------------------------------------------------------------------
// MjpegCameraFeed: loads the raw stream URL in a WebView — the same
// Chromium engine Chrome itself uses, so this renders the MJPEG feed
// exactly like pasting the URL into a Chrome tab. Sized to fill
// whatever container it's placed in (see CameraBoxFrame), so it's
// no longer forced to fullscreen.
// ------------------------------------------------------------------
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MjpegCameraFeed(
    url: String,
    reloadTrigger: Int,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView) -> Unit = {}
) {
    // Tracks which reloadTrigger value was last loaded, so we only call
    // loadUrl again when the app actually comes back to the foreground
    // (not on every unrelated recomposition).
    var lastLoadedTrigger by remember { mutableStateOf(-1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                }

                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.BLACK)

                // Fit the image to the WebView's own bounds (which now
                // match the camera box's size, not necessarily the full
                // screen) rather than forcing 100vw/100vh viewport units.
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        val javascript = """
                            document.body.style.margin = '0';
                            document.body.style.padding = '0';
                            document.body.style.backgroundColor = 'black';
                            document.body.style.overflow = 'hidden';
                            var img = document.querySelector('img');
                            if (img) {
                                img.style.width = '100%';
                                img.style.height = '100%';
                                img.style.objectFit = 'contain';
                            }
                        """.trimIndent()
                        view.evaluateJavascript(javascript, null)
                    }
                }

                loadUrl(url)
                lastLoadedTrigger = reloadTrigger
                onWebViewReady(this)
            }
        },
        update = { view ->
            // Reload only when reloadTrigger has actually changed since the
            // last load — this fires on app resume, forcing the MJPEG
            // stream to reconnect instead of showing a frozen last frame.
            if (reloadTrigger != lastLoadedTrigger) {
                lastLoadedTrigger = reloadTrigger
                view.loadUrl(url)
            }
        }
    )
}

// ------------------------------------------------------------------
// Captures whatever the camera WebView is currently displaying into a
// Bitmap, for the capture/save-to-gallery button.
// ------------------------------------------------------------------
fun captureWebViewSnapshot(webView: WebView): Bitmap? {
    if (webView.width <= 0 || webView.height <= 0) return null
    val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    webView.draw(canvas)
    return bitmap
}

// ------------------------------------------------------------------
// Saves a Bitmap into the phone's Gallery under a "CATS Robot" album.
// Uses the modern scoped-storage MediaStore API on Android 10+ (no
// extra permission needed), and falls back to the legacy public
// Pictures directory + WRITE_EXTERNAL_STORAGE on older versions.
// ------------------------------------------------------------------
fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean {
    val filename = "CATS_${System.currentTimeMillis()}.jpg"
    val resolver = context.contentResolver

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CATS Robot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val albumDir = File(picturesDir, "CATS Robot")
            if (!albumDir.exists()) albumDir.mkdirs()
            put(MediaStore.Images.Media.DATA, File(albumDir, filename).absolutePath)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

    return try {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}