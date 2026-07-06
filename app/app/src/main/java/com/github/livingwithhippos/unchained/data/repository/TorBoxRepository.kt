package com.github.livingwithhippos.unchained.data.repository

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.remote.TorBoxApi
import com.github.livingwithhippos.unchained.data.remote.isTorBoxApiKey
import com.github.livingwithhippos.unchained.data.remote.torBoxApiKey
import java.io.IOException
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

    /**
     * fetches the torbox account behind the api key, distinguishing a transient network problem
     * (no connection, timeout...) from an actual bad/revoked key so the caller can show a message
     * that does not send the user chasing their api key over a plain connectivity blip
     */
    suspend fun getTorBoxUser(): TorBoxUserResult {
        val key = torBoxKey() ?: return TorBoxUserResult.BadKey
        return try {
            val response = torBoxApi.getUserInfo("Bearer $key")
            val user = response.body()?.data
            when {
                response.isSuccessful && user != null -> TorBoxUserResult.Success(user)
                response.code() == 401 || response.code() == 403 -> {
                    Timber.w("TorBox user call returned ${response.code()}")
                    TorBoxUserResult.BadKey
                }
                else -> {
                    Timber.w("TorBox user call returned ${response.code()}")
                    TorBoxUserResult.Unknown
                }
            }
        } catch (e: IOException) {
            // network hiccup or timeout: transient, unrelated to the key itself
            Timber.w(e, "Network error fetching the torbox user")
            TorBoxUserResult.NetworkIssue
        } catch (e: Exception) {
            Timber.w(e, "Error fetching the torbox user")
            TorBoxUserResult.Unknown
        }
    }
}

/** result of [TorBoxRepository.getTorBoxUser], keeping a network failure distinguishable from a key one */
sealed class TorBoxUserResult {
    data class Success(val user: TorBoxUser) : TorBoxUserResult()

    /** the key is missing, was rejected (401/403) or another non network error occurred */
    data object BadKey : TorBoxUserResult()

    /** an IOException/timeout was thrown while calling torbox: likely a transient connection issue */
    data object NetworkIssue : TorBoxUserResult()

    /** a call succeeded with an unexpected body, or failed with an http error other than 401/403 */
    data object Unknown : TorBoxUserResult()
}
