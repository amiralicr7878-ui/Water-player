package com.amiralicr.waterplayer

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class WaterPlayerActivity : Activity() {
    private lateinit var webView: WebView
    private val permissionRequest = 7142
    private val fileChooserRequest = 7143
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val selectedAudioPrefs by lazy { getSharedPreferences("water_player_audio", MODE_PRIVATE) }
    private val selectedAudioKey = "persisted_audio_uris"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val chooser = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }

                return try {
                    startActivityForResult(chooser, fileChooserRequest)
                    true
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(NativeBridge(), "AndroidMusic")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileChooserRequest) {
            val callback = filePathCallback
            filePathCallback = null
            if (callback != null) {
                val selectedUris = mutableListOf<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val grantedFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val savedUris = selectedAudioPrefs.getStringSet(selectedAudioKey, emptySet())?.toMutableSet() ?: mutableSetOf()
                    val clip = data.clipData
                    if (clip != null) {
                        for (index in 0 until clip.itemCount) {
                            selectedUris.add(clip.getItemAt(index).uri)
                        }
                    } else {
                        data.data?.let { selectedUris.add(it) }
                    }
                    selectedUris.forEach { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(uri, grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            savedUris.add(uri.toString())
                        } catch (_: Exception) { }
                    }
                    selectedAudioPrefs.edit().putStringSet(selectedAudioKey, savedUris).apply()
                }
                callback.onReceiveValue(selectedUris.takeIf { it.isNotEmpty() }?.toTypedArray())
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (instance === this) instance = null
        webView.destroy()
        super.onDestroy()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestNativePermissions() {
        if (hasAudioPermission()) {
            notifyPermissionReady()
            return
        }

        val requested = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requested.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) notifyPermissionReady()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && hasAudioPermission()) notifyPermissionReady()
    }

    private fun notifyPermissionReady() {
        if (::webView.isInitialized) webView.post {
            webView.evaluateJavascript("window.onWaterAudioPermissionGranted && window.onWaterAudioPermissionGranted()", null)
        }
    }

    inner class NativeBridge {
        @JavascriptInterface fun requestPermissions() {
            runOnUiThread { requestNativePermissions() }
        }

        @JavascriptInterface fun getAudioLibrary(): String {
            if (!hasAudioPermission()) return "[]"
            val result = JSONArray()
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
            val sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(collection, projection, selection, null, sort)
                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val albumId = c.getLong(albumIdCol)
                        val itemUri = ContentUris.withAppendedId(collection, id)
                        val item = JSONObject()
                        item.put("id", "media-$id")
                        item.put("title", c.getString(titleCol) ?: "بدون عنوان")
                        item.put("artist", c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "هنرمند ناشناس")
                        item.put("album", c.getString(albumCol) ?: "")
                        item.put("duration", (c.getLong(durationCol) / 1000L).coerceAtLeast(1L))
                        item.put("fileUrl", itemUri.toString())
                        item.put("coverUrl", "content://media/external/audio/albumart/$albumId")
                        result.put(item)
                    }
                }
            } catch (_: Exception) { }

            // Re-add files explicitly selected by the user, even if they are outside MediaStore.
            val indexedUris = HashSet<String>()
            try {
                val savedUris = selectedAudioPrefs.getStringSet(selectedAudioKey, emptySet()) ?: emptySet()
                for (savedUri in savedUris) {
                    if (!indexedUris.add(savedUri)) continue
                    val uri = Uri.parse(savedUri)
                    try {
                        val nameAndDuration = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, MediaStore.Audio.Media.DURATION)
                        var displayName = "آهنگ انتخاب‌شده"
                        var durationMs = 0L
                        contentResolver.query(uri, nameAndDuration, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val durationIndex = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                                if (nameIndex >= 0) displayName = c.getString(nameIndex) ?: displayName
                                if (durationIndex >= 0 && !c.isNull(durationIndex)) durationMs = c.getLong(durationIndex)
                            }
                        }
                        val item = JSONObject()
                        item.put("id", "picked-${savedUri.hashCode()}")
                        item.put("title", displayName.substringBeforeLast('.', displayName))
                        item.put("artist", "آهنگ انتخاب‌شده")
                        item.put("album", "Local Audio")
                        item.put("duration", (durationMs / 1000L).coerceAtLeast(1L))
                        item.put("fileUrl", savedUri)
                        item.put("coverUrl", JSONObject.NULL)
                        result.put(item)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            return result.toString()
        }

        @JavascriptInterface fun updateMetadata(json: String) {
            val intent = Intent(this@WaterPlayerActivity, WaterPlaybackService::class.java).apply {
                action = WaterPlaybackService.ACTION_METADATA
                putExtra(WaterPlaybackService.EXTRA_METADATA, json)
            }
            startService(intent)
        }

        @JavascriptInterface fun setPlaybackState(playing: Boolean, positionMs: Long) {
            val action = if (playing) WaterPlaybackService.ACTION_PLAY else WaterPlaybackService.ACTION_PAUSE
            val intent = Intent(this@WaterPlayerActivity, WaterPlaybackService::class.java).apply {
                this.action = action
                putExtra(WaterPlaybackService.EXTRA_POSITION, positionMs)
            }
            if (playing && Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }

        @JavascriptInterface fun updatePosition(positionMs: Long, durationMs: Long) {
            val intent = Intent(this@WaterPlayerActivity, WaterPlaybackService::class.java).apply {
                action = WaterPlaybackService.ACTION_POSITION
                putExtra(WaterPlaybackService.EXTRA_POSITION, positionMs)
                putExtra(WaterPlaybackService.EXTRA_DURATION, durationMs)
            }
            startService(intent)
        }
    }

    companion object {
        @Volatile var instance: WaterPlayerActivity? = null
        fun dispatchMediaAction(action: String, positionMs: Long? = null) {
            val activity = instance ?: return
            activity.runOnUiThread {
                val arg = positionMs?.toString() ?: "null"
                activity.webView.evaluateJavascript(
                    "window.WaterPlayerNativeAction && window.WaterPlayerNativeAction('$action', $arg)", null
                )
            }
        }
    }
}
