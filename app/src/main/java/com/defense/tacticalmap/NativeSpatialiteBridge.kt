package com.defense.tacticalmap

import android.util.Log

class NativeSpatialiteBridge {
    private val tag = "NativeSpatialiteBridge"

    val isBridgeReady: Boolean by lazy {
        try {
            System.loadLibrary("tacticalmap_native")
            nativeIsBridgeReady()
        } catch (exception: UnsatisfiedLinkError) {
            Log.e(tag, "Unable to load tacticalmap_native library", exception)
            false
        }
    }

    val hasSpatialiteSupport: Boolean
        get() = isBridgeReady && nativeHasSpatialiteSupport()

    fun getDriverStatus(): String {
        return if (!isBridgeReady) {
            "Native bridge failed to load."
        } else {
            nativeGetDriverStatus()
        }
    }

    fun computeBufferSummary(entity: String, distanceMeters: Int): String {
        return if (!isBridgeReady) {
            "Native bridge unavailable."
        } else {
            nativeComputeBufferSummary(entity, distanceMeters)
        }
    }

    private external fun nativeIsBridgeReady(): Boolean
    private external fun nativeHasSpatialiteSupport(): Boolean
    private external fun nativeGetDriverStatus(): String
    private external fun nativeComputeBufferSummary(entity: String, distanceMeters: Int): String
}
