package com.offlinemap.baghdad

import android.app.Application
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class BaghdadMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mapsforge Android graphic factory initialization is required before using MapView
        AndroidGraphicFactory.createInstance(this)
    }
}
