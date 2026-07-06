package com.github.livingwithhippos.unchained.user.viewmodel

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.model.User
import com.github.livingwithhippos.unchained.data.remote.torBoxApiKey
import com.github.livingwithhippos.unchained.data.repository.TorBoxRepository
import com.github.livingwithhippos.unchained.data.repository.TorBoxUserResult
import com.github.livingwithhippos.unchained.data.repository.UserRepository
import com.github.livingwithhippos.unchained.utilities.Event
import com.github.livingwithhippos.unchained.utilities.KEY_TORBOX_API_KEY
import com.github.livingwithhippos.unchained.utilities.PRIVATE_TOKEN
import com.github.livingwithhippos.unchained.utilities.postEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * A [ViewModel] subclass for the user profile screen. Loads the status of both debrid services so
 * each account card can be populated independently of which service backs the main login. The
 * real debrid user data itself keeps coming from the activity view model, whose fetch also feeds
 * the authentication state machine.
 */
@HiltViewModel
class UserProfileViewModel
@Inject
constructor(
    private val torBoxRepository: TorBoxRepository,
    private val userRepository: UserRepository,
    private val protoStore: ProtoStore,
    private val preferences: SharedPreferences,
) : ViewModel() {

    val realDebridActiveLiveData = MutableLiveData<Boolean>()
    val torBoxStatusLiveData = MutableLiveData<TorBoxAccountStatus>()
    val eventLiveData = MutableLiveData<Event<UserProfileEvent>>()

    /**
     * The user returned by the token check of the last successful [connectRealDebrid], used to
     * fill the real debrid card right away instead of the activity level cached user, which may
     * still be the mapped torbox one when torbox backed the main login before the connection
     */
    var connectedRealDebridUser: User? = null
        private set

    fun fetchAccountsStatus() {
        viewModelScope.launch {
            realDebridActiveLiveData.postValue(torBoxRepository.isRealDebridActive())
            if (!torBoxRepository.isTorBoxActive()) {
                torBoxStatusLiveData.postValue(TorBoxAccountStatus.NotConnected)
            } else {
                torBoxStatusLiveData.postValue(
                    when (val result = torBoxRepository.getTorBoxUser()) {
                        is TorBoxUserResult.Success -> TorBoxAccountStatus.Connected(result.user)
                        // a transient connection problem is not the user's key being wrong, say so
                        TorBoxUserResult.NetworkIssue -> TorBoxAccountStatus.NetworkError
                        TorBoxUserResult.BadKey,
                        TorBoxUserResult.Unknown -> TorBoxAccountStatus.Error
                    }
                )
            }
        }
    }

    /**
     * Connects a real debrid account from its user page card: the private token is checked against
     * the real debrid user endpoint and stored in the datastore only when it works. The torbox api
     * key preference is never touched, so an active torbox account stays active, and no
     * authentication machine event is needed: this only runs while the machine is already
     * authenticated with the torbox key as the app private token, and a real debrid private token
     * is a private token login as well
     */
    fun connectRealDebrid(token: String) {
        viewModelScope.launch {
            val user = userRepository.getUserInfo(token)
            if (user == null) {
                eventLiveData.postEvent(UserProfileEvent.RealDebridTokenError)
            } else {
                protoStore.setCredentials(
                    deviceCode = PRIVATE_TOKEN,
                    clientId = PRIVATE_TOKEN,
                    clientSecret = PRIVATE_TOKEN,
                    accessToken = token,
                    refreshToken = PRIVATE_TOKEN,
                )
                connectedRealDebridUser = user
                eventLiveData.postEvent(UserProfileEvent.RealDebridConnected)
            }
        }
    }

    /** true when the stored login token belongs to real debrid */
    suspend fun isRealDebridActive(): Boolean = torBoxRepository.isRealDebridActive()

    /** true when a torbox api key is available, from the preference or the main login */
    suspend fun isTorBoxActive(): Boolean = torBoxRepository.isTorBoxActive()

    /**
     * Disconnects torbox from its user page card while real debrid is the main login: only the
     * api key preference is cleared, matching what blanking the settings field does. The
     * datastore credentials and the state machine are left completely untouched. Only call this
     * when [isRealDebridActive] is true, otherwise this would leave no service connected and a
     * full logout should happen instead
     */
    fun disconnectTorBox() {
        preferences.edit { remove(KEY_TORBOX_API_KEY) }
        eventLiveData.postEvent(UserProfileEvent.ServiceDisconnected)
    }

    /**
     * Disconnects real debrid from its user page card while torbox is connected as a secondary
     * account: torbox is promoted to the main login by storing its api key as the datastore
     * private token, the same sentinel fields the connect torbox flow already uses when nothing
     * else is logged in. No state machine event is needed: this only runs while the machine is
     * already authenticated with a private token, and a torbox key is a private token login too.
     * Only call this when [isTorBoxActive] is true, otherwise this would leave no service
     * connected and a full logout should happen instead
     */
    fun disconnectRealDebrid() {
        viewModelScope.launch {
            val torBoxKey = preferences.torBoxApiKey()
            if (torBoxKey != null) {
                protoStore.setCredentials(
                    deviceCode = PRIVATE_TOKEN,
                    clientId = PRIVATE_TOKEN,
                    clientSecret = PRIVATE_TOKEN,
                    accessToken = torBoxKey,
                    refreshToken = PRIVATE_TOKEN,
                )
            }
            eventLiveData.postEvent(UserProfileEvent.ServiceDisconnected)
        }
    }
}

sealed class TorBoxAccountStatus {
    data class Connected(val user: TorBoxUser) : TorBoxAccountStatus()

    data object NotConnected : TorBoxAccountStatus()

    /** the key is missing/invalid or an unexpected error occurred */
    data object Error : TorBoxAccountStatus()

    /** a network/timeout issue occurred while fetching the account, unrelated to the key itself */
    data object NetworkError : TorBoxAccountStatus()
}

sealed class UserProfileEvent {
    data object RealDebridConnected : UserProfileEvent()

    data object RealDebridTokenError : UserProfileEvent()

    data object ServiceDisconnected : UserProfileEvent()
}
