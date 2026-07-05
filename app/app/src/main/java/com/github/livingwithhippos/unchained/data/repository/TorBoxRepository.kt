package com.github.livingwithhippos.unchained.data.repository

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.remote.TorBoxApi
import com.github.livingwithhippos.unchained.data.remote.isTorBoxApiKey
import com.github.livingwithhippos.unchained.data.remote.torBoxApiKey
import javax.inject.Inject
import timber.log.Timber

/**
 * Small repository for the calls that must reach torbox no matter which service backs the main
 * login. The api key is resolved like the *ApiHelperImpl classes do it: the dedicated preference
 * when available, otherwise the stored login token itself when torbox is the main login.
 */
class TorBoxRepository
@Inject
constructor(
    private val protoStore: ProtoStore,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
) {

    private suspend fun torBoxKey(): String? {
        preferences.torBoxApiKey()?.let {
            return it
        }
        val token: String? = protoStore.getCredentials().accessToken
        if (token.isNullOrBlank()) return null
        return if (isTorBoxApiKey(token)) token.removePrefix("Bearer").trim() else null
    }

    /** true when a torbox api key is available, from the preference or the main login */
    suspend fun isTorBoxActive(): Boolean = torBoxKey() != null

    /** true when the stored login token belongs to real debrid */
    suspend fun isRealDebridActive(): Boolean {
        val token: String? = protoStore.getCredentials().accessToken
        return !token.isNullOrBlank() && !isTorBoxApiKey(token)
    }

    /** the torbox account behind the api key, null when torbox is not active or the call fails */
    suspend fun getTorBoxUser(): TorBoxUser? {
        val key = torBoxKey() ?: return null
        return try {
            val response = torBoxApi.getUserInfo("Bearer $key")
            if (response.isSuccessful) response.body()?.data
            else {
                Timber.w("TorBox user call returned ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching the torbox user")
            null
        }
    }
}
