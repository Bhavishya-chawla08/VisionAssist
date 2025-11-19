package com.example.visionassist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class NavigationAlgorithm(private val context: Context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private val ttsQueue = ConcurrentLinkedQueue<Pair<String, Boolean>>() // (message, isSensor)
    private var isSpeaking = AtomicBoolean(false)

    private var cameraObjects: List<BoundingBox> = emptyList()
    private var sensorData: SensorReading? = null

    private var lastInstruction: String? = null
    private var lastSpeakTime = 0L
    private val isNavigationActive = AtomicBoolean(false)

    private val sensorPriorityDistance = 150
    private val cameraConsiderDistance = 300

    init {
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking.set(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking.set(false)
                        handler.postDelayed({ processNextInQueue() }, 700)
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking.set(false)
                        handler.postDelayed({ processNextInQueue() }, 700)
                    }
                })
            }
        }
    }

    fun startNavigation() {
        isNavigationActive.set(true)
        handler.post(navigationLoop)
    }

    fun stopNavigation() {
        isNavigationActive.set(false)
        enqueueSpeech("Navigation stopped.", false)
    }

    fun updateCameraObjects(objects: List<BoundingBox>) {
        cameraObjects = objects
    }

    fun updateSensorData(direction: String, distance: Int) {
        sensorData = SensorReading(direction.lowercase(Locale.ROOT), distance)
    }

    private val navigationLoop = object : Runnable {
        override fun run() {
            if (!isNavigationActive.get()) return

            val instruction = generateInstruction()
            if (instruction != null && instruction != lastInstruction &&
                System.currentTimeMillis() - lastSpeakTime > 2500
            ) {
                val isSensor = instruction.startsWith("[SENSOR]")
                enqueueSpeech(instruction.replace("[SENSOR] ", ""), isSensor)
                lastInstruction = instruction
                lastSpeakTime = System.currentTimeMillis()
            }

            handler.postDelayed(this, 1200)
        }
    }

    private fun generateInstruction(): String? {
        val sensor = sensorData
        val objects = cameraObjects.filter { it.distance > 0f }
        val nearbyObjects = objects.filter { it.distance <= cameraConsiderDistance }.sortedBy { it.distance }

        fun boxDirection(cx: Float): String {
            return when {
                cx < 0.33f -> "left"
                cx <= 0.66f -> "front"
                else -> "right"
            }
        }

        fun cameraSummary(list: List<BoundingBox>): List<Pair<String, Int>> {
            val result = mutableListOf<Pair<String, Int>>()
            for (b in list.take(3)) {
                val name = b.clsName.ifBlank { "object" }
                val distCm = b.distance.roundToInt()
                val dir = boxDirection(b.cx)
                result.add(Pair("$name|$dir", distCm))
            }
            return result
        }

        if (sensor != null && sensor.distance <= sensorPriorityDistance) {
            val sensorMsg = "Obstacle detected ${sensor.distance} centimeters on your ${sensor.direction}."
            val camList = cameraSummary(nearbyObjects)
            val cameraMsg = if (camList.isNotEmpty()) {
                camList.joinToString(" and ") {
                    val (k, d) = it
                    val (name, dir) = k.split("|")
                    "a $name at $d cm at your $dir"
                }
            } else null

            val counts = nearbyObjects.groupingBy { boxDirection(it.cx) }.eachCount()
            val leftCount = counts["left"] ?: 0
            val frontCount = counts["front"] ?: 0
            val rightCount = counts["right"] ?: 0

            val move = when (sensor.direction) {
                "left" -> if (rightCount <= leftCount && rightCount <= frontCount) "Please move slightly to your right."
                else "Please move forward carefully."
                "right" -> if (leftCount <= rightCount && leftCount <= frontCount) "Please move slightly to your left."
                else "Please move forward carefully."
                "front" -> if (leftCount <= rightCount) "Please move slightly to your left."
                else "Please move slightly to your right."
                else -> "Please adjust your direction to avoid the obstacle."
            }

            val combined = StringBuilder("[SENSOR] ")
            combined.append(sensorMsg)
            if (cameraMsg != null) combined.append(" Also, ").append(cameraMsg).append(".")
            combined.append(" ").append(move)
            return combined.toString()
        }

        if (nearbyObjects.isNotEmpty()) {
            val phrases = cameraSummary(nearbyObjects)
            val description = phrases.joinToString(" and ") {
                val (k, d) = it
                val (name, dir) = k.split("|")
                val dirPhrase = when (dir) {
                    "front" -> "in front"
                    "left" -> "at your left"
                    "right" -> "at your right"
                    else -> "nearby"
                }
                "a $name is present at $d cm $dirPhrase"
            }

            val dirs = nearbyObjects.map { boxDirection(it.cx) }.toSet()
            val suggestion = when {
                dirs.containsAll(setOf("left", "front")) -> "Please move towards your right."
                dirs.containsAll(setOf("right", "front")) -> "Please move towards your left."
                dirs.containsAll(setOf("left", "right")) -> "Objects on both sides. Move forward carefully."
                dirs.containsAll(setOf("left", "front", "right")) -> "Obstacles all around. Please stop and turn around."
                dirs.contains("front") -> "Please move slightly to your left or right to avoid the object in front."
                dirs.contains("left") -> "Please move slightly to your right."
                dirs.contains("right") -> "Please move slightly to your left."
                else -> "Path is clear."
            }

            return "$description. $suggestion"
        }

        return "Path is clear."
    }

    private fun enqueueSpeech(message: String, isSensor: Boolean) {
        ttsQueue.offer(message to isSensor)
        if (!isSpeaking.get()) processNextInQueue()
    }

    private fun processNextInQueue() {
        if (isSpeaking.get()) return
        val next = ttsQueue.poll() ?: return
        val (msg, isSensor) = next
        speakAndVibrate(msg, isSensor)
    }

    private fun speakAndVibrate(message: String, isSensor: Boolean) {
        val ttsObj = tts ?: return
        val id = UUID.randomUUID().toString()
        ttsObj.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)

        val duration = if (isSensor) 700L else 300L
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(duration)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    data class SensorReading(val direction: String, val distance: Int)
}
