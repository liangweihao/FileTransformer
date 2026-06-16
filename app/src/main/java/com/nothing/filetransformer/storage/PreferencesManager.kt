package com.nothing.filetransformer.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "file_transformer_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_SAVE_LOCATION_TYPE = stringPreferencesKey("save_location_type")
        private val KEY_CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")

        const val LOCATION_TYPE_DOWNLOADS = "downloads"
        const val LOCATION_TYPE_CUSTOM = "custom"
    }

    /** Flow that emits the current save location type ("downloads" or "custom"). */
    val saveLocationType: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_SAVE_LOCATION_TYPE] ?: LOCATION_TYPE_DOWNLOADS
    }

    /** Flow that emits the custom SAF tree URI string (empty string if not set). */
    val customTreeUri: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_CUSTOM_TREE_URI] ?: ""
    }

    /** Set save location to Downloads. */
    suspend fun setSaveLocationToDownloads() {
        context.dataStore.edit { preferences ->
            preferences[KEY_SAVE_LOCATION_TYPE] = LOCATION_TYPE_DOWNLOADS
        }
    }

    /** Set save location to a custom SAF tree URI. */
    suspend fun setCustomTreeUri(uri: Uri) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SAVE_LOCATION_TYPE] = LOCATION_TYPE_CUSTOM
            preferences[KEY_CUSTOM_TREE_URI] = uri.toString()
        }
    }
}
