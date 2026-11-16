package com.notcherry.lfm3pocket

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.imagePreferences by preferencesDataStore(
    name = "image_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Application-scoped DataStore for vision image preparation preferences. */
class ImageSettingsStore(context: Context) {
    private val store = context.applicationContext.imagePreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val enabledKey = booleanPreferencesKey(ImageSettings.KEY_ENABLED)
    private val maxSideKey = intPreferencesKey(ImageSettings.KEY_MAX_SIDE)
    private val cropKey = booleanPreferencesKey(ImageSettings.KEY_CROP_SQUARE)
    private val qualityKey = intPreferencesKey(ImageSettings.KEY_JPEG_QUALITY)

    fun load(success: Consumer<ImageSettings>, failure: Consumer<String>) {
        scope.launch {
            try {
                val prefs = store.data.first()
                val map = HashMap<String, Any>()
                prefs[enabledKey]?.let { map[ImageSettings.KEY_ENABLED] = it }
                prefs[maxSideKey]?.let { map[ImageSettings.KEY_MAX_SIDE] = it }
                prefs[cropKey]?.let { map[ImageSettings.KEY_CROP_SQUARE] = it }
                prefs[qualityKey]?.let { map[ImageSettings.KEY_JPEG_QUALITY] = it }
                success.accept(ImageSettings.fromPersistenceMap(map))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure.accept(e.message ?: "Cannot read image settings")
            }
        }
    }

    fun save(settings: ImageSettings, success: Runnable, failure: Consumer<String>) {
        scope.launch {
            try {
                store.edit { prefs ->
                    prefs[enabledKey] = settings.enabled
                    prefs[maxSideKey] = settings.maxSide.pixels
                    prefs[cropKey] = settings.cropToSquare
                    prefs[qualityKey] = settings.jpegQuality
                }
                success.run()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure.accept(e.message ?: "Cannot save image settings")
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
