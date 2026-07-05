package com.github.livingwithhippos.unchained.user.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.repository.TorBoxRepository
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
constructor(private val torBoxRepository: TorBoxRepository) : ViewModel() {

    val realDebridActiveLiveData = MutableLiveData<Boolean>()
    val torBoxStatusLiveData = MutableLiveData<TorBoxAccountStatus>()

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
}

sealed class TorBoxAccountStatus {
    data class Connected(val user: TorBoxUser) : TorBoxAccountStatus()

    data object NotConnected : TorBoxAccountStatus()

    data object Error : TorBoxAccountStatus()
}
