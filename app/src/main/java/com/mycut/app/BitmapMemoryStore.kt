package com.mycut.app

import android.graphics.Bitmap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BitmapMemoryStore {
    private val map = ConcurrentHashMap<String, Bitmap>()

    fun put(bitmap: Bitmap): String {
        val key = UUID.randomUUID().toString()
        map[key] = bitmap
        return key
    }

    fun take(key: String?): Bitmap? {
        if (key.isNullOrBlank()) return null
        return map.remove(key)
    }
}
