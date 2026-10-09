package com.example.andriodfypprototype

import android.app.Application
import com.example.andriodfypprototype.data.Store

class WeedReaverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
