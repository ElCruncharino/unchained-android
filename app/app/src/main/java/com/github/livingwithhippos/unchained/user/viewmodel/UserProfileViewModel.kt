package com.github.livingwithhippos.unchained.user.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.model.User
import com.github.livingwithhippos.unchained.data.repository.TorBoxRepository
import com.github.livingwithhippos.unchained.data.repository.UserRepository
import com.github.livingwithhippos.unchained.utilities.Event
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
                val user = torBoxRepository.getTorBoxUser()
                torBoxStatusLiveData.postValue(
                    if (user != null) TorBoxAccountStatus.Connected(user)
                    else TorBoxAccountStatus.Error
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
}

sealed class TorBoxAccountStatus {
    data class Connected(val user: TorBoxUser) : TorBoxAccountStatus()

    data object NotConnected : TorBoxAccountStatus()

    data object Error : TorBoxAccountStatus()
}

sealed class UserProfileEvent {
    data object RealDebridConnected : UserProfileEvent()

    data object RealDebridTokenError : UserProfileEvent()
}
