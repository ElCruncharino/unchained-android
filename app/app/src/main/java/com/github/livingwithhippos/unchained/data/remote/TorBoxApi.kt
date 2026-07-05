package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.TorBoxTorrentListResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxUserResponse
import com.github.livingwithhippos.unchained.utilities.KEY_CURRENT_DEBRID_PROVIDER
import com.github.livingwithhippos.unchained.utilities.PROVIDER_REAL_DEBRID
import com.github.livingwithhippos.unchained.utilities.PROVIDER_TORBOX
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** This interface is used by Retrofit to manage the REST calls to the torbox endpoints */
interface TorBoxApi {

    @GET("user/me")
    suspend fun getUserInfo(@Header("Authorization") token: String): Response<TorBoxUserResponse>

    @GET("torrents/mylist")
    suspend fun getTorrentsList(
        @Header("Authorization") token: String,
        @Query("bypass_cache") bypassCache: Boolean = true,
        @Query("offset") offset: Int? = null,
        @Query("limit") limit: Int? = null,
    ): Response<TorBoxTorrentListResponse>
}

/** true when the user logged in with a torbox api key instead of a real debrid token */
fun SharedPreferences.isTorBoxProvider(): Boolean =
    getString(KEY_CURRENT_DEBRID_PROVIDER, PROVIDER_REAL_DEBRID) == PROVIDER_TORBOX

/**
 * builds a real debrid style error response from a torbox error so the existing error parsing keeps
 * working. 401/403 are mapped to the real debrid bad token error code
 */
fun <T> torBoxErrorResponse(code: Int): Response<T> {
    val errorCode = if (code == 401 || code == 403) 8 else -1
    val body =
        "{\"error\":\"torbox_error\",\"error_code\":$errorCode}"
            .toResponseBody("application/json".toMediaTypeOrNull())
    return Response.error(if (code in 400..599) code else 500, body)
}
