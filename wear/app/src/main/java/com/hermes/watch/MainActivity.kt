package com.hermes.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.CircularProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MAX_COMMAND_LENGTH = 400
private const val PREFS = "hermes_watch_prefs"
private const val KEY_PROFILE = "profile"
private const val KEY_URL = "backend_url"
private const val KEY_TOKEN = "backend_token"

// Safe public default — users replace this with their own Hermes backend.
// Never ship a real URL/token in the source.
private const val DEFAULT_BACKEND_URL = "http://192.168.1.100:8650"

// --- Design tokens ---
private val Accent = Color(0xFF00E5A0)        // mint green — the app's one accent
private val AccentDeep = Color(0xFF00B37A)
private val MicIdle = Color(0xFF1E1E24)
private val MicIdleEdge = Color(0xFF2E2E36)
private val BgDark = Color(0xFF0B0B0F)
private val TextMuted = Color(0xFF9A9AA5)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Comms
    private val scope = CoroutineScope(Dispatchers.IO)

    // A fresh session id is generated once per app process (app launch) so the
    // Hermes session persists while the app is open and resets when it closes.
    private val sessionId = "watch-" + java.util.UUID.randomUUID().toString().take(8)

    // UI state
    private var statusText = mutableStateOf("Idle")
    private var replyText = mutableStateOf("")
    private var sending = mutableStateOf(false)
    private var listening = mutableStateOf(false)
    private var selectedProfile = mutableStateOf("watch")
    private var fetchedProfiles = mutableStateOf<List<String>>(emptyList())
    private var backendUrl = mutableStateOf(DEFAULT_BACKEND_URL)
    private var backendToken = mutableStateOf("")
    private var setupNeeded = mutableStateOf(false)

    // NOTE: do NOT touch Context/SharedPreferences here in an init block —
    // init runs before the framework attaches the Activity's Context, so
    // getSharedPreferences() would crash on launch. Load prefs in onCreate().

    // Background STT (SpeechRecognizer API — no Google recognition screen)
    private var recognizer: SpeechRecognizer? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            listening.value = false
            statusText.value = "Mic permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the persisted profile selection (context is safe here).
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        selectedProfile.value = prefs.getString(KEY_PROFILE, "watch") ?: "watch"

        // Backend config priority: persisted (setup page) > build-time (APK) > blank.
        val persistedUrl = prefs.getString(KEY_URL, null)
        val persistedToken = prefs.getString(KEY_TOKEN, null)
        backendUrl.value = persistedUrl ?: BuildConfig.BACKEND_URL.ifBlank { DEFAULT_BACKEND_URL }
        backendToken.value = persistedToken ?: BuildConfig.BACKEND_TOKEN
        setupNeeded.value = backendToken.value.isBlank()

        // TTS init
        tts = TextToSpeech(applicationContext, this)

        // Register the reply callback (runs on main thread)
        DataLayerListenerService.resultHandler = { reply ->
            onReply(reply)
        }

        setContent {
            MaterialTheme {
                Scaffold(
                    timeText = { TimeText() },
                    modifier = Modifier.background(BgDark)
                ) {
                    // Three swipeable pages: 0 = mic, 1 = settings, 2 = setup.
                    // If the user hasn't set a backend token yet, land on setup.
                    val startPage = if (setupNeeded.value) 2 else 0
                    val pagerState = rememberPagerState(initialPage = startPage) { 3 }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> WatchScreen(
                                status = statusText.value,
                                reply = replyText.value,
                                listening = listening.value,
                                sending = sending.value,
                                profile = selectedProfile.value,
                                onRecord = {
                                    if (ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        buzz(35)
                                        listening.value = true
                                        statusText.value = "Listening…"
                                        startListening()
                                    }
                                }
                            )
                            1 -> {
                                LaunchedEffect(Unit) { fetchProfiles() }
                                SettingsScreen(
                                    currentProfile = selectedProfile.value,
                                    profiles = if (fetchedProfiles.value.isEmpty()) listOf("watch") else fetchedProfiles.value,
                                    onSelect = { setProfile(it) }
                                )
                            }
                            else -> SetupScreen(
                                url = backendUrl.value,
                                token = backendToken.value,
                                onSave = { url, token -> saveSetup(url, token) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setProfile(profile: String) {
        selectedProfile.value = profile
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, profile)
            .apply()
    }

    private fun saveSetup(url: String, token: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanToken = token.trim()
        if (cleanUrl.isBlank() || cleanToken.isBlank()) {
            statusText.value = "URL & token required"
            return
        }
        backendUrl.value = cleanUrl
        backendToken.value = cleanToken
        setupNeeded.value = false
        statusText.value = "Saved ✓"
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, cleanUrl)
            .putString(KEY_TOKEN, cleanToken)
            .apply()
    }

    private fun fetchProfiles() {
        val url = backendUrl.value
        val token = backendToken.value
        if (url.isBlank() || token.isBlank()) return
        scope.launch {
            try {
                val conn = java.net.URL("$url/api/v1/profiles").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONObject(resp).optJSONArray("profiles")
                        val list = mutableListOf<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
                            }
                        }
                        if (list.isNotEmpty()) {
                            mainHandler.post { fetchedProfiles.value = list }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                android.util.Log.e("HermesWatch", "fetch profiles failed", e)
            }
        }
    }

    private fun startListening() {
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: android.os.Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            mainHandler.post {
                                listening.value = false
                                statusText.value = when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                    else -> "Recognition error"
                                }
                            }
                        }
                        override fun onResults(results: android.os.Bundle?) {
                            mainHandler.post {
                                listening.value = false
                                val spoken = results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                if (spoken.isNullOrBlank()) {
                                    statusText.value = "No speech detected"
                                } else {
                                    statusText.value = "Sending…"
                                    sendCommand(spoken.take(MAX_COMMAND_LENGTH))
                                }
                            }
                        }
                        override fun onPartialResults(partialResults: android.os.Bundle?) {}
                        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    })
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening.value = false
            statusText.value = "Speech not supported"
            android.util.Log.e("HermesWatch", "startListening failed", e)
        }
    }

    private fun sendCommand(command: String) {
        val url = backendUrl.value
        val token = backendToken.value
        if (url.isBlank() || token.isBlank()) {
            statusText.value = "Setup required"
            return
        }
        sending.value = true
        scope.launch {
            try {
                // Direct HTTP POST to the Hermes backend. The Galaxy Watch has
                // real internet (via the phone's BT/Wi-Fi bridge), so we don't
                // need the phone relay app / GMS listener at all.
                val conn = java.net.URL("$url/api/v1/command").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    val body = JSONObject()
                        .put("text", command)
                        .put("session", sessionId)
                        .put("profile", selectedProfile.value)
                        .toString()
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code == 200) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(resp)
                        val reply = json.optString("result")
                        withContext(Dispatchers.Main) {
                            sending.value = false
                            onReply(if (reply.isBlank()) "(no reply)" else reply)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            sending.value = false
                            statusText.value = "HTTP $code"
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sending.value = false
                    statusText.value = "Send failed"
                }
                android.util.Log.e("HermesWatch", "direct POST failed", e)
            }
        }
    }

    private fun onReply(reply: String) {
        replyText.value = reply
        statusText.value = "Reply"
        buzz(90)
        speak(reply)
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-reply")
        }
    }

    // Short haptic pulses — cheap, native watch feedback for state changes.
    private fun buzz(durationMs: Long, amp: Int = 60) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amp))
            }
        } catch (e: Exception) {
            // Don't swallow — surface it so a haptic regression isn't silent.
            android.util.Log.e("HermesWatch", "buzz failed (VIBRATE permission?)", e)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onDestroy() {
        DataLayerListenerService.resultHandler = null
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun WatchScreen(
    status: String,
    reply: String,
    listening: Boolean,
    sending: Boolean,
    profile: String,
    onRecord: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        if (reply.isNotEmpty()) {
            // Full-screen reply view. The whole round screen is one scrollable
            // region so long replies are readable in full — no overlap with a
            // centered mic. A small mic button sits at the bottom so you can
            // talk again without leaving.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Reply",
                    style = MaterialTheme.typography.caption1,
                    color = Accent,
                    letterSpacing = 1.sp
                )
                Text(
                    text = reply,
                    style = MaterialTheme.typography.body1,
                    color = Color(0xFFE6E6EA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }

            // Small mic button pinned at the bottom to talk again.
            SmallMicButton(
                listening = listening,
                sending = sending,
                onClick = onRecord,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            // Idle / listening / sending: hero mic dead-center.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = profile.uppercase(),
                    style = MaterialTheme.typography.caption3,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )

                HeroMicButton(
                    listening = listening,
                    sending = sending,
                    onClick = onRecord
                )

                Text(
                    text = status,
                    style = MaterialTheme.typography.caption2,
                    color = if (listening) Accent else TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SmallMicButton(
    listening: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = listening || sending

    Box(
        modifier = modifier
            .padding(bottom = 8.dp)
            .size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulse ring while listening so you know it's capturing.
        if (listening) {
            val t = rememberInfiniteTransition(label = "smallMicPulse")
            val pulse by t.animateFloat(
                initialValue = 1f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )
            Canvas(modifier = Modifier.size(64.dp)) {
                drawCircle(
                    color = Accent.copy(alpha = 0.3f),
                    radius = size.minDimension / 2 * pulse,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // Spinner while sending.
        if (sending) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                strokeWidth = 3.dp
            )
        }

        Button(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier
                .size(if (sending) 46.dp else 52.dp)
                .background(
                    brush = Brush.linearGradient(
                        if (active) listOf(Accent, AccentDeep)
                        else listOf(MicIdleEdge, MicIdle)
                    ),
                    shape = CircleShape
                ),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.12f
                val c = if (active) Color.White else Color(0xFFD0D0D8)
                drawRoundRect(
                    color = c,
                    topLeft = Offset(w * 0.32f, h * 0.10f),
                    size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f),
                    style = Stroke(width = stroke)
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.50f, h * 0.58f),
                    end = Offset(w * 0.50f, h * 0.78f),
                    strokeWidth = stroke
                )
                drawArc(
                    color = c,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.66f),
                    size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.24f),
                    style = Stroke(width = stroke)
                )
            }
        }
    }
}

@Composable
private fun HeroMicButton(
    listening: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
) {
    val active = listening || sending

    // Pulsing halo while listening.
    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.size(132.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulsing halo (listening only).
        if (listening) {
            Canvas(modifier = Modifier.size(132.dp)) {
                val r = size.minDimension / 2 * pulse
                drawCircle(
                    color = Accent.copy(alpha = 0.25f),
                    radius = r,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // Sending spinner ring.
        if (sending) {
            CircularProgressIndicator(
                modifier = Modifier.size(124.dp),
                strokeWidth = 3.dp
            )
        }

        // The button itself.
        Button(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier
                .size(if (sending) 108.dp else 120.dp)
                .background(
                    brush = if (active) {
                        Brush.linearGradient(listOf(Accent, AccentDeep))
                    } else {
                        Brush.linearGradient(listOf(MicIdleEdge, MicIdle))
                    },
                    shape = CircleShape
                ),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            // Mic glyph drawn with Canvas — crisp at any size, no emoji rendering.
            Canvas(modifier = Modifier.size(40.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.10f
                val c = if (active) Color.White else Color(0xFFD0D0D8)
                // Mic capsule body (rounded rect).
                drawRoundRect(
                    color = c,
                    topLeft = Offset(w * 0.32f, h * 0.10f),
                    size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f),
                    style = Stroke(width = stroke)
                )
                // Mic stand / stem.
                drawLine(
                    color = c,
                    start = Offset(w * 0.50f, h * 0.58f),
                    end = Offset(w * 0.50f, h * 0.78f),
                    strokeWidth = stroke
                )
                // Base arc.
                drawArc(
                    color = c,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.66f),
                    size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.24f),
                    style = Stroke(width = stroke)
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    currentProfile: String,
    profiles: List<String>,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PROFILE",
                style = MaterialTheme.typography.caption1,
                color = Accent,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                profiles.forEach { p ->
                    val active = p == currentProfile
                    Button(
                        onClick = { onSelect(p) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (active) Accent.copy(alpha = 0.22f)
                            else MicIdle,
                            contentColor = if (active) Accent else TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = if (active) "✓  $p" else p,
                            style = MaterialTheme.typography.caption1,
                            color = if (active) Accent else Color(0xFFD0D0D8)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    url: String,
    token: String,
    onSave: (String, String) -> Unit,
) {
    var urlText by remember(url) { mutableStateOf(url) }
    var tokenText by remember(token) { mutableStateOf(token) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CONNECT TO HERMES",
                style = MaterialTheme.typography.caption1,
                color = Accent,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            SetupField(
                value = urlText,
                onValueChange = { urlText = it },
                label = "Backend URL",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            SetupField(
                value = tokenText,
                onValueChange = { tokenText = it },
                label = "Bearer token",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Button(
                onClick = { onSave(urlText, tokenText) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Accent,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.caption1)
            }

            Text(
                text = "Point at your own Hermes backend and enter its auth token. Tap a field to type.",
                style = MaterialTheme.typography.caption3,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption3,
            color = TextMuted
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.caption1.copy(color = Color(0xFFE6E6EA)),
            modifier = Modifier
                .fillMaxWidth()
                .background(MicIdle, CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
