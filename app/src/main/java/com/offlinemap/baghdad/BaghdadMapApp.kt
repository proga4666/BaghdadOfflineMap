package com.offlinemap.baghdad

import android.app.Application
import org.maplibre.android.MapLibre
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class BaghdadMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MapLibre Native C++ rendering engine
        MapLibre.getInstance(this)
        // Mapsforge Android graphic factory initialization
        AndroidGraphicFactory.createInstance(this)
    }
}
