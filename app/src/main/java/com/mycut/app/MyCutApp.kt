package com.mycut.app

import android.app.Application

class MyCutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
