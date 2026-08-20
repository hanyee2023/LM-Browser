package com.proxybrowser.app.core

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

object AdBlocker {
    private val filters = mutableListOf<String>()

    fun loadEnabled(ctx: Context) {
        if (!Settings.isAdBlockEnabled(ctx)) return
        if (filters.isNotEmpty()) return
        // Simple default rules
        filters.addAll(listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "google-analytics.com",
            "analytics."
        ))
    }

    fun shouldBlock(url: String): Boolean {
        return filters.any { url.contains(it, ignoreCase = true) }
    }

    fun emptyResponse(): WebResourceResponse {
        val bytes = ByteArray(0)
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(bytes))
    }
}
