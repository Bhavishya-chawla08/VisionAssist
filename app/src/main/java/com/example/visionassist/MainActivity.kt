package com.example.visionassist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.visionassist.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector1: Detector? = null
    private var detector2: Detector? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var tts: TextToSpeech
    private val VOICE_REQUEST_CODE = 201

    private var boxesFromModel1: List<BoundingBox> = emptyList()
    private var boxesFromModel2: List<BoundingBox> = emptyList()

    private lateinit var voiceTriggerHelper: VoiceTriggerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceTriggerHelper = VoiceTriggerHelper(this) {
            binding.micButton.performClick()
        }

        binding.bottomNavigation.selectedItemId = R.id.nav_camera

        cameraExecutor = Executors.newSingleThreadExecutor()

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) tts.language = Locale.UK
        }

        cameraExecutor.execute {
            detector1 = Detector(baseContext, Constants.MODEL_PATH_1, Constants.LABELS_PATH_1, 1, this)
            detector2 = Detector(baseContext, Constants.MODEL_PATH_2, Constants.LABELS_PATH_2, 2, this)
        }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        binding.bottomNavigation.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> startActivity(Intent(this, HomeActivity::class.java))
                R.id.nav_sensor -> startActivity(Intent(this, SensorActivity::class.java))
            }
            true
        }

        binding.micButton.setOnClickListener { startVoiceRecognition() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(rotationDegrees)
                if (isFrontCamera) postScale(-1f, 1f)
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            detector1?.detect(rotatedBitmap)
            detector2?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.CAMERA] == true) startCamera()
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
            "open sensor" in command -> startActivity(Intent(this, SensorActivity::class.java))
            "start navigation" in command -> {
                if (!NavigatorManager.isRunning()) {
                    NavigatorManager.start(applicationContext)
                    tts.speak("Starting navigation", TextToSpeech.QUEUE_FLUSH, null, null)
                } else tts.speak("Navigation is already running.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            "stop navigation" in command -> {
                if (NavigatorManager.isRunning()) {
                    NavigatorManager.stop()
                    // keep the UI consistent
                    getSharedPreferences("navigation_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("is_navigation_running", false).apply()
                    tts.speak("Navigation stopped. Returning to home screen.", TextToSpeech.QUEUE_FLUSH, null, null)
                    startActivity(Intent(this, HomeActivity::class.java))
                } else {
                    tts.speak("Navigation is not running.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            "app tutorial" in command -> speakTutorial()
            else -> tts.speak("Sorry, I didn’t understand that command.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakTutorial() {
        val tutorialText = """
            You can control this app with your voice.
            Say 'Open home' to return home.
            Say 'Open sensor' to switch to sensor mode.
            Say 'Start navigation' to start navigation.
            Say 'Stop navigation' to end navigation.
            Say 'App tutorial' to hear this again.
        """.trimIndent()
        tts.speak(tutorialText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onEmptyDetect(detectorId: Int) {
        runOnUiThread {
            if (detectorId == 1) boxesFromModel1 = emptyList()
            else boxesFromModel2 = emptyList()
            binding.overlay.setResults(boxesFromModel1 + boxesFromModel2)

            if (NavigatorManager.isRunning()) NavigatorManager.updateCameraObjects(emptyList())
        }
    }

    override fun onDetect(detectorId: Int, boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            if (detectorId == 1) boxesFromModel1 = boundingBoxes
            else boxesFromModel2 = boundingBoxes
            val allBoxes = boxesFromModel1 + boxesFromModel2
            binding.overlay.setResults(allBoxes)

            if (NavigatorManager.isRunning()) {
                NavigatorManager.updateCameraObjects(allBoxes)
            }
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
        super.onDestroy()
        detector1?.close()
        detector2?.close()
        cameraExecutor.shutdown()
        tts.stop()
        tts.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
