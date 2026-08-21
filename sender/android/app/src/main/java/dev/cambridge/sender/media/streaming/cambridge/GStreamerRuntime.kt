package dev.cambridge.sender.media.streaming.cambridge

import android.content.Context
import org.freedesktop.gstreamer.GStreamer

object GStreamerRuntime {
    private val lock = Any()
    private var initialized = false
    private var gstreamerLibraryLoaded = false
    private var libraryLoaded = false

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            try {
                if (!gstreamerLibraryLoaded) {
                    System.loadLibrary("gstreamer_android")
                    gstreamerLibraryLoaded = true
                }
                GStreamer.init(context.applicationContext)
                if (!libraryLoaded) {
                    System.loadLibrary("cambridge_gstreamer")
                    libraryLoaded = true
                }
                initialized = true
            } catch (cause: Throwable) {
                throw IllegalStateException("CamBridge GStreamer initialization failed", cause)
            }
        }
    }
}
