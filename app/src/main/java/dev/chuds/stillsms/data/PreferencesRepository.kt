package dev.chuds.stillsms.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class SmsSettings(
    val fontPreset: FontPreset = FontPreset.System,
    val twentyFourHour: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val groupMmsEnabled: Boolean = false,
    val mmsAutoDownloadOnMobile: Boolean = true,
    val defaultSimId: String = "",
)

private val FONT_PRESET_KEY = stringPreferencesKey("pref_font")
private val TWENTY_FOUR_HOUR_KEY = booleanPreferencesKey("pref_24h")
private val HAPTICS_KEY = booleanPreferencesKey("pref_haptics")
private val GROUP_MMS_KEY = booleanPreferencesKey("pref_group_mms")
private val MMS_AUTO_DOWNLOAD_KEY = booleanPreferencesKey("pref_mms_auto_download")
private val DEFAULT_SIM_KEY = stringPreferencesKey("pref_default_sim")

class PreferencesRepository(private val context: Context) {

    val settings: Flow<SmsSettings> = context.stillSmsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            SmsSettings(
                fontPreset = prefs[FONT_PRESET_KEY]
                    ?.let { runCatching { FontPreset.valueOf(it) }.getOrNull() }
                    ?: FontPreset.System,
                twentyFourHour = prefs[TWENTY_FOUR_HOUR_KEY] ?: true,
                hapticsEnabled = prefs[HAPTICS_KEY] ?: true,
                groupMmsEnabled = prefs[GROUP_MMS_KEY] ?: false,
                mmsAutoDownloadOnMobile = prefs[MMS_AUTO_DOWNLOAD_KEY] ?: true,
                defaultSimId = prefs[DEFAULT_SIM_KEY] ?: "",
            )
        }

    suspend fun setFontPreset(value: FontPreset) =
        context.stillSmsDataStore.edit { it[FONT_PRESET_KEY] = value.name }

    suspend fun setTwentyFourHour(value: Boolean) =
        context.stillSmsDataStore.edit { it[TWENTY_FOUR_HOUR_KEY] = value }

    suspend fun setHapticsEnabled(value: Boolean) =
        context.stillSmsDataStore.edit { it[HAPTICS_KEY] = value }

    suspend fun setGroupMmsEnabled(value: Boolean) =
        context.stillSmsDataStore.edit { it[GROUP_MMS_KEY] = value }

    suspend fun setMmsAutoDownloadOnMobile(value: Boolean) =
        context.stillSmsDataStore.edit { it[MMS_AUTO_DOWNLOAD_KEY] = value }

    suspend fun setDefaultSimId(value: String) =
        context.stillSmsDataStore.edit { it[DEFAULT_SIM_KEY] = value }
}
