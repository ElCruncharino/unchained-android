package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.User
import com.github.livingwithhippos.unchained.data.model.toUser
import javax.inject.Inject
import retrofit2.Response

class UserApiHelperImpl
@Inject
constructor(
    private val userApi: UserApi,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
) : UserApiHelper {
    override suspend fun getUser(token: String): Response<User> {
        // the stored token is a real debrid one unless torbox was used for the main login, so
        // when both accounts are active the real debrid user is shown
        if (!isTorBoxApiKey(token)) return userApi.getUser(token)
        // route the call to torbox and map the result to the real debrid user model
        val response = torBoxApi.getUserInfo(token)
        val user = response.body()?.data
        return if (response.isSuccessful && user != null) Response.success(user.toUser())
        else torBoxErrorResponse(response.code(), torBoxErrorMessage(response))
    }
}
