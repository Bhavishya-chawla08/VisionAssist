package com.example.visionassist

import android.content.Context

object NavigatorManager {
    private var navigator: NavigationAlgorithm? = null

    fun start(context: Context) {
        if (navigator == null) {
            navigator = NavigationAlgorithm(context.applicationContext)
            navigator?.startNavigation()
        }
    }

    fun stop() {
        navigator?.stopNavigation()
        navigator?.shutdown() // ✅ Updated from release() → shutdown()
        navigator = null
    }

    fun isRunning(): Boolean = navigator != null

    fun updateCameraObjects(objects: List<BoundingBox>) {
        navigator?.updateCameraObjects(objects)
    }

    fun updateSensorData(direction: String, distance: Int) {
        navigator?.updateSensorData(direction, distance)
    }
}
