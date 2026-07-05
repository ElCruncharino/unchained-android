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
        if (!preferences.isTorBoxProvider()) return userApi.getUser(token)
        // route the call to torbox and map the result to the real debrid user model
        val response = torBoxApi.getUserInfo(token)
        val user = response.body()?.data
        return if (response.isSuccessful && user != null) Response.success(user.toUser())
        else torBoxErrorResponse(response.code())
    }
}
