package com.example.visionassist

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.visionassist.databinding.ActivitySensorBinding
import java.io.InputStream
import java.util.*
import kotlin.concurrent.thread

class SensorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySensorBinding
    private lateinit var tts: TextToSpeech
    private var bluetoothSocket: BluetoothSocket? = null
    private var adapter: BluetoothAdapter? = null
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var vibrator: Vibrator? = null

    private enum class DistanceState { CLEAR_PATH, CAUTION, WARNING, DANGER }
    private var currentState = DistanceState.CLEAR_PATH
    private var lastSpeakTime = 0L

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var isTryingToConnect = false

    private val VOICE_REQUEST_CODE = 301
    private lateinit var voiceTriggerHelper: VoiceTriggerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySensorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceTriggerHelper = VoiceTriggerHelper(this) {
            binding.micButton.performClick()
        }

        binding.bottomNavigation.selectedItemId = R.id.nav_sensor

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) tts.language = Locale.UK
        }

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null) {
            binding.txtAlert.text = "❌ Bluetooth not supported on this device."
            return
        }

        requestAllPermissions()
        ensureBluetoothEnabled()

        setupBottomNavigation()
        setupMicButton()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, HomeActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_camera -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_sensor -> true
                else -> false
            }
        }
    }

    private fun setupMicButton() {
        binding.micButton.setOnClickListener { startVoiceRecognition() }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        startActivityForResult(intent, VOICE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = results?.get(0)?.lowercase(Locale.getDefault()) ?: return
            handleVoiceCommand(command)
        }
    }

    private fun handleVoiceCommand(command: String) {
        when {
            "open home" in command -> startActivity(Intent(this, HomeActivity::class.java))
            "open camera" in command -> startActivity(Intent(this, MainActivity::class.java))
            "start navigation" in command -> {
                if (!NavigatorManager.isRunning()) {
                    NavigatorManager.start(applicationContext)
                    tts.speak("Starting navigation", TextToSpeech.QUEUE_FLUSH, null, null)
                } else tts.speak("Navigation already running", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            "stop navigation" in command -> {
                if (NavigatorManager.isRunning()) {
                    NavigatorManager.stop()
                    getSharedPreferences("navigation_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("is_navigation_running", false).apply()
                    tts.speak("Navigation stopped. Returning to home screen.", TextToSpeech.QUEUE_FLUSH, null, null)
                    startActivity(Intent(this, HomeActivity::class.java))
                } else tts.speak("Navigation is not running.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            "app tutorial" in command -> speakTutorial()
            else -> tts.speak("Sorry, I didn’t understand that command.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakTutorial() {
        val tutorialText = """
            You can control this application using your voice.
            Say 'Open home' to go back to home screen.
            Say 'Open camera' to start object detection.
            Say 'Start navigation' to begin.
            Say 'Stop navigation' to stop navigation.
            Say 'App tutorial' to hear this again.
        """.trimIndent()
        tts.speak(tutorialText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun requestAllPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(this, permissions, 200)
    }

    private fun ensureBluetoothEnabled() {
        if (adapter?.isEnabled == false) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    201
                )
                return
            }
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 101)
        } else startAutoReconnectLoop()
    }

    private fun startAutoReconnectLoop() {
        if (isTryingToConnect) return
        isTryingToConnect = true
        reconnectHandler.post(object : Runnable {
            override fun run() {
                if (bluetoothSocket == null || bluetoothSocket?.isConnected == false) {
                    autoConnectToHC05()
                    reconnectHandler.postDelayed(this, 5000)
                }
            }
        })
    }

    private fun autoConnectToHC05() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 200)
            return
        }

        val device = adapter?.bondedDevices?.find { it.name == "HC-05" }
        if (device != null) connectToDevice(device)
        else runOnUiThread { binding.txtAlert.text = "HC-05 not paired. Please pair it manually." }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        thread {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) return@thread

                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                ) adapter?.cancelDiscovery()

                bluetoothSocket?.connect()

                runOnUiThread {
                    binding.txtDevices.text = "• HC-05 – ✅ Connected\n"
                    binding.txtAlert.text = "Connected to HC-05. Starting distance scanning..."
                    binding.txtAlert.setBackgroundColor(Color.parseColor("#004D40"))
                }

                bluetoothSocket?.inputStream?.let { listenForData(it) }
            } catch (e: Exception) {
                runOnUiThread { binding.txtAlert.text = "HC-05 not reachable. Retrying..." }
                bluetoothSocket = null
            }
        }
    }

    private fun listenForData(inputStream: InputStream) {
        val buffer = ByteArray(256)
        while (true) {
            try {
                val bytes = inputStream.read(buffer)
                val msg = String(buffer, 0, bytes)

                if (msg.contains("OBSTACLE")) {
                    val rawDirection = when {
                        msg.contains("Left") -> "Left"
                        msg.contains("Right") -> "Right"
                        else -> "Front"
                    }

                    val correctedDirection = when (rawDirection) {
                        "Left" -> "Right"
                        "Right" -> "Left"
                        else -> "Front"
                    }

                    val distance = msg.substringAfterLast("_")
                        .replace("cm", "")
                        .trim()
                        .toIntOrNull() ?: 0

                    if (distance == 0) continue

                    runOnUiThread { updateUI(distance, correctedDirection) }
                }
            } catch (e: Exception) {
                runOnUiThread { binding.txtAlert.text = "🔄 Lost connection. Retrying..." }
                bluetoothSocket = null
                break
            }
        }
    }

    private fun updateUI(distance: Int, direction: String) {
        binding.txtDistance.text = "$distance"
        binding.txtDirection.text = "Direction: $direction"
        binding.progressBar.progress = distance.coerceIn(0, 150)

        val newState = when {
            distance > 150 -> DistanceState.CLEAR_PATH
            distance > 100 -> DistanceState.CAUTION
            distance > 50 -> DistanceState.WARNING
            else -> DistanceState.DANGER
        }

        if (newState != currentState || System.currentTimeMillis() - lastSpeakTime > 5000) {
            speakAndVibrate(newState, distance, direction)
            currentState = newState
            lastSpeakTime = System.currentTimeMillis()
        }

        val colorMap = mapOf(
            DistanceState.CLEAR_PATH to Pair("#004D40", Color.GREEN),
            DistanceState.CAUTION to Pair("#8B5A00", Color.YELLOW),
            DistanceState.WARNING to Pair("#FF8C00", Color.rgb(255, 140, 0)),
            DistanceState.DANGER to Pair("#8B0000", Color.RED)
        )

        val (bgColor, progressColor) = colorMap[newState]!!
        binding.txtAlert.setBackgroundColor(Color.parseColor(bgColor))
        binding.progressBar.progressDrawable.setColorFilter(progressColor, PorterDuff.Mode.SRC_IN)
        binding.txtAlert.text = when (newState) {
            DistanceState.CLEAR_PATH -> "✅ Clear Path"
            DistanceState.CAUTION -> "⚠️ Obstacle on your $direction"
            DistanceState.WARNING -> "⚠️ Warning! Object $distance cm $direction"
            DistanceState.DANGER -> "🚨 Danger! Too close on your $direction"
        }

        if (NavigatorManager.isRunning()) {
            NavigatorManager.updateSensorData(direction.lowercase(Locale.ROOT), distance)
        }
    }

    private fun speakAndVibrate(state: DistanceState, distance: Int, direction: String) {
        val message = when (state) {
            DistanceState.CLEAR_PATH -> "No obstacle nearby."
            DistanceState.CAUTION -> "Obstacle on your $direction around $distance centimeters."
            DistanceState.WARNING -> "Warning! Object $distance centimeters on your $direction."
            DistanceState.DANGER -> "Danger! Very close obstacle on your $direction!"
        }
        if (!tts.isSpeaking) tts.speak(message, TextToSpeech.QUEUE_ADD, null, null) // 🕐 Queued for better latency

        val duration = when (state) {
            DistanceState.CAUTION -> 200L
            DistanceState.WARNING -> 400L
            DistanceState.DANGER -> 800L
            else -> 0L
        }
        if (duration > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            else vibrator?.vibrate(duration)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        voiceTriggerHelper.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        voiceTriggerHelper.handleKeyEvent(event.keyCode, event)
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        bluetoothSocket?.close()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
