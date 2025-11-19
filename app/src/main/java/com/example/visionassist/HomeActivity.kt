package com.example.visionassist

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.visionassist.databinding.ActivityHomeBinding
import java.io.InputStream
import java.util.*
import kotlin.concurrent.thread

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var tts: TextToSpeech
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var voiceTriggerHelper: VoiceTriggerHelper

    private var bluetoothSocket: BluetoothSocket? = null
    private var adapter: BluetoothAdapter? = null
    private var isConnecting = false

    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val VOICE_REQUEST_CODE = 200
    private val REQUEST_BT_PERMISSIONS = 201
    private val REQUEST_ENABLE_BT = 101

    companion object {
        private var hasWelcomedUser = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceTriggerHelper = VoiceTriggerHelper(this) {
            binding.micButton.performClick()
        }

        prefs = getSharedPreferences("navigation_prefs", Context.MODE_PRIVATE)
        adapter = BluetoothAdapter.getDefaultAdapter()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                if (!hasWelcomedUser) {
                    hasWelcomedUser = true
                    tts.speak(
                        "Welcome to your navigation assistant. How can we help you?",
                        TextToSpeech.QUEUE_FLUSH, null, null
                    )
                }
            }
        }

        setupListeners()
        updateButtonText()
    }

    private fun setupListeners() {
        binding.btnStartNav.setOnClickListener {
            if (!NavigatorManager.isRunning()) startNavigationSequence()
            else stopNavigationSequence()
            updateButtonText()
        }

        binding.btnTutorial.setOnClickListener { speakTutorial() }
        binding.micButton.setOnClickListener { startVoiceRecognition() }
    }

    private fun startNavigationSequence() {
        if (adapter == null) {
            tts.speak("Bluetooth not supported on this device.", TextToSpeech.QUEUE_FLUSH, null, null)
            return
        }

        if (!adapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        connectToHC05()

        NavigatorManager.start(applicationContext)
        prefs.edit().putBoolean("is_navigation_running", true).apply()
        tts.speak("Starting navigation and connecting to sensors.", TextToSpeech.QUEUE_FLUSH, null, null)

        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun stopNavigationSequence() {
        NavigatorManager.stop()
        prefs.edit().putBoolean("is_navigation_running", false).apply()

        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}

        tts.speak("Navigation stopped. Thank you.", TextToSpeech.QUEUE_FLUSH, null, null)
    }


    private fun hasBluetoothPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(this, permissions, REQUEST_BT_PERMISSIONS)
    }

    private fun connectToHC05() {
        if (isConnecting) return
        isConnecting = true

        thread {
            try {
                val device = safeGetBondedDevice("HC-05") ?: run {
                    runOnUiThread {
                        Toast.makeText(this, "HC-05 not paired.", Toast.LENGTH_SHORT).show()
                        tts.speak("HC-05 not paired. Please pair it manually.", TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                    isConnecting = false
                    return@thread
                }

                bluetoothSocket = safeCreateSocket(device)
                if (bluetoothSocket == null) {
                    runOnUiThread { tts.speak("Unable to open Bluetooth socket.", TextToSpeech.QUEUE_FLUSH, null, null) }
                    isConnecting = false
                    return@thread
                }

                safeCancelDiscovery()
                safeConnect(bluetoothSocket!!)

                runOnUiThread {
                    Toast.makeText(this, "✅ Connected to HC-05", Toast.LENGTH_SHORT).show()
                    tts.speak("Connected to HC-05. Sensor data active.", TextToSpeech.QUEUE_ADD, null, null)
                }

                bluetoothSocket?.inputStream?.let { listenForSensorData(it) }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    tts.speak("Unable to connect to HC-05.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
                bluetoothSocket = null
            } finally {
                isConnecting = false
            }
        }
    }

    private fun safeGetBondedDevice(name: String): BluetoothDevice? {
        return try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return null
            adapter?.bondedDevices?.find { it.name == name }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun safeCreateSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun safeCancelDiscovery() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            ) adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
    }

    private fun safeConnect(socket: BluetoothSocket) {
        try {
            socket.connect()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    private fun listenForSensorData(inputStream: InputStream) {
        val buffer = ByteArray(256)
        while (NavigatorManager.isRunning()) {
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

                    val distance = msg.substringAfterLast("_").replace("cm", "").trim().toIntOrNull() ?: 0
                    if (distance == 0) continue

                    NavigatorManager.updateSensorData(correctedDirection.lowercase(Locale.ROOT), distance)
                }

            } catch (_: Exception) {
                bluetoothSocket = null
                break
            }
        }
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
        } else if (requestCode == REQUEST_ENABLE_BT) {
            if (adapter?.isEnabled == true) startNavigationSequence()
        }
    }

    private fun handleVoiceCommand(command: String) {
        when {
            "start navigation" in command -> {
                if (!NavigatorManager.isRunning()) startNavigationSequence()
                else tts.speak("Navigation is already running.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            "stop navigation" in command -> stopNavigationSequence()
            "open camera" in command -> startActivity(Intent(this, MainActivity::class.java))
            "open sensor" in command -> startActivity(Intent(this, SensorActivity::class.java))
            "open home" in command -> {}
            "app tutorial" in command -> speakTutorial()
            else -> tts.speak("Sorry, I didn’t understand that command.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
        updateButtonText()
    }

    private fun speakTutorial() {
        val tutorialText = """
            You can control the app with your voice.
            Say 'Start navigation' to begin.
            Say 'Stop navigation' to stop.
            Say 'Open sensor' to switch to sensor mode.
            Say 'Open camera' to go to camera detection.
            Say 'Open home' to come back here.
            Say 'App tutorial' to hear this guide again.
        """.trimIndent()
        tts.speak(tutorialText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun updateButtonText() {
        binding.btnStartNav.text =
            if (NavigatorManager.isRunning()) "🛑 Stop Navigation" else "▶️ Start Navigation"
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        voiceTriggerHelper.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        voiceTriggerHelper.handleKeyEvent(event.keyCode, event)
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        updateButtonText()
    }

    override fun onDestroy() {
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
