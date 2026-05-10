package dev.chuds.stillsms.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore for the eight settings rows. The block list lives outside DataStore
 * (filesDir/blocked.json) so it remains plaintext-`cat`-able.
 */
internal val Context.stillSmsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stillsms",
)
