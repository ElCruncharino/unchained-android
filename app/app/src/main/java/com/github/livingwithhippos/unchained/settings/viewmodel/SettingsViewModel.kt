package com.github.livingwithhippos.unchained.settings.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.livingwithhippos.unchained.R
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.repository.HostsRepository
import com.github.livingwithhippos.unchained.data.repository.KodiRepository
import com.github.livingwithhippos.unchained.data.repository.PluginRepository
import com.github.livingwithhippos.unchained.data.repository.TorBoxRepository
import com.github.livingwithhippos.unchained.settings.view.SettingsFragment.Companion.KEY_THEME_NEW
import com.github.livingwithhippos.unchained.settings.view.ThemeItem
import com.github.livingwithhippos.unchained.start.viewmodel.MainActivityViewModel.Companion.KEY_DOWNLOAD_FOLDER
import com.github.livingwithhippos.unchained.utilities.Event
import com.github.livingwithhippos.unchained.utilities.KEY_TORBOX_API_KEY
import com.github.livingwithhippos.unchained.utilities.PRIVATE_TOKEN
import com.github.livingwithhippos.unchained.utilities.TORBOX_API_KEY_PATTERN
import com.github.livingwithhippos.unchained.utilities.postEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val hostsRepository: HostsRepository,
    private val pluginRepository: PluginRepository,
    private val kodiRepository: KodiRepository,
    private val protoStore: ProtoStore,
    private val preferences: SharedPreferences,
    private val torBoxRepository: TorBoxRepository,
) : ViewModel() {

    val kodiLiveData = MutableLiveData<Event<Boolean>>()

    val eventLiveData = MutableLiveData<Event<SettingEvent>>()

    val themeLiveData = MutableLiveData<Event<ThemeItem>>()

    fun updateRegexps() {
        viewModelScope.launch {
            hostsRepository.updateHostsRegex()
            hostsRepository.updateFoldersRegex()
        }
    }

    fun removeAllPlugins(context: Context): Int = pluginRepository.removeInstalledPlugins(context)

    fun testKodi(ip: String, port: Int, username: String?, password: String?) {
        viewModelScope.launch {
            val response = kodiRepository.getVolume(ip, port, username, password)
            kodiLiveData.postEvent(response != null)
        }
    }

    fun selectTheme(theme: ThemeItem) {
        themeLiveData.postEvent(theme)
    }

    fun setDownloadFolder(uri: Uri) {
        uri.describeContents()
        preferences.edit { putString(KEY_DOWNLOAD_FOLDER, uri.toString()) }
    }

    fun userLogout() {
        viewModelScope.launch {
            val credentials = protoStore.getCredentials()
            val hasTorBoxKey = !preferences.getString(KEY_TORBOX_API_KEY, null).isNullOrBlank()
            if (
                credentials.accessToken.isBlank() && credentials.clientId.isBlank() && !hasTorBoxKey
            ) {
                eventLiveData.postEvent(SettingEvent.LogoutNoCredentials)
            } else {
                // remove the credentials of both services
                preferences.edit { remove(KEY_TORBOX_API_KEY) }
                protoStore.deleteCredentials()
                eventLiveData.postEvent(SettingEvent.Logout)
            }
        }
    }

    /**
     * called when the torbox api key preference changes: when torbox is also the main app login
     * (no real debrid credentials) the single credentials storage is kept in sync so the
     * authentication flow keeps working with the new key
     */
    fun updateTorBoxApiKey(key: String) {
        viewModelScope.launch {
            if (key.isBlank()) return@launch
            val accessToken: String? = protoStore.getCredentials().accessToken
            if (
                accessToken.isNullOrBlank() ||
                    accessToken.matches(TORBOX_API_KEY_PATTERN.toRegex())
            ) {
                protoStore.setCredentials(
                    deviceCode = PRIVATE_TOKEN,
                    clientId = PRIVATE_TOKEN,
                    clientSecret = PRIVATE_TOKEN,
                    accessToken = key,
                    refreshToken = PRIVATE_TOKEN,
                )
            }
        }
    }

    fun applyTheme() {
        val selectedTheme: ThemeItem? = themeLiveData.value?.peekContent()
        selectedTheme?.let { preferences.edit { putInt(KEY_THEME_NEW, it.themeID) } }
    }

    fun getCurrentTheme(): Int {
        return preferences.getInt(KEY_THEME_NEW, R.style.Theme_Unchained_Material3_Green_One)
    }

    /** true when both real debrid and torbox are logged in, mirrors the new download screen check */
    suspend fun areBothServicesActive(): Boolean =
        torBoxRepository.isRealDebridActive() && torBoxRepository.isTorBoxActive()
}

sealed class SettingEvent {
    data object Logout : SettingEvent()

    data object LogoutNoCredentials : SettingEvent()
}
