package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.APIError
import com.github.livingwithhippos.unchained.data.model.TorBoxControlRequest
import com.github.livingwithhippos.unchained.data.model.TorBoxControlResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxCreateTorrentResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxCreateWebDownloadResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxErrorEnvelope
import com.github.livingwithhippos.unchained.data.model.TorBoxRequestDownloadResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxTorrentListResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxTorrentResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxUserResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxWebControlRequest
import com.github.livingwithhippos.unchained.data.model.TorBoxWebDownloadListResponse
import com.github.livingwithhippos.unchained.data.model.TorBoxWebDownloadResponse
import com.github.livingwithhippos.unchained.utilities.KEY_TORBOX_API_KEY
import com.github.livingwithhippos.unchained.utilities.TORBOX_API_KEY_PATTERN
import com.github.livingwithhippos.unchained.utilities.TORBOX_ERROR_RATE_LIMITED
import com.github.livingwithhippos.unchained.utilities.TORBOX_ERROR_SERVICE_UNAVAILABLE
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
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

    /** when the id parameter is used mylist returns a single torrent object instead of a list */
    @GET("torrents/mylist")
    suspend fun getTorrent(
        @Header("Authorization") token: String,
        @Query("id") id: String,
        @Query("bypass_cache") bypassCache: Boolean = true,
    ): Response<TorBoxTorrentResponse>

    @Multipart
    @POST("torrents/createtorrent")
    suspend fun createTorrentFromMagnet(
        @Header("Authorization") token: String,
        @Part("magnet") magnet: RequestBody,
    ): Response<TorBoxCreateTorrentResponse>

    @Multipart
    @POST("torrents/createtorrent")
    suspend fun createTorrentFromFile(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
    ): Response<TorBoxCreateTorrentResponse>

    @POST("torrents/controltorrent")
    suspend fun controlTorrent(
        @Header("Authorization") token: String,
        @Body operation: TorBoxControlRequest,
    ): Response<TorBoxControlResponse>

    /**
     * exchanges a torrent file for a CDN download url, valid to start for 3 hours. This endpoint
     * authenticates with the raw api key as a query parameter instead of the header
     */
    @GET("torrents/requestdl")
    suspend fun requestDownloadLink(
        @Query("token") token: String,
        @Query("torrent_id") torrentId: Int,
        @Query("file_id") fileId: Int,
    ): Response<TorBoxRequestDownloadResponse>

    /**
     * queues a hoster link as a torbox web download. Unlike the real debrid unrestrict this is
     * asynchronous: the file has to be fetched by torbox before download links are available
     */
    @FormUrlEncoded
    @POST("webdl/createwebdownload")
    suspend fun createWebDownload(
        @Header("Authorization") token: String,
        @Field("link") link: String,
        @Field("password") password: String? = null,
    ): Response<TorBoxCreateWebDownloadResponse>

    @GET("webdl/mylist")
    suspend fun getWebDownloadsList(
        @Header("Authorization") token: String,
        @Query("bypass_cache") bypassCache: Boolean = true,
        @Query("offset") offset: Int? = null,
        @Query("limit") limit: Int? = null,
    ): Response<TorBoxWebDownloadListResponse>

    /** when the id parameter is used mylist returns a single web download object instead */
    @GET("webdl/mylist")
    suspend fun getWebDownload(
        @Header("Authorization") token: String,
        @Query("id") id: String,
        @Query("bypass_cache") bypassCache: Boolean = true,
    ): Response<TorBoxWebDownloadResponse>

    /**
     * exchanges a web download file for a CDN download url, same raw key query parameter
     * authentication as the torrents variant
     */
    @GET("webdl/requestdl")
    suspend fun requestWebDownloadLink(
        @Query("token") token: String,
        @Query("web_id") webId: Int,
        @Query("file_id") fileId: Int,
    ): Response<TorBoxRequestDownloadResponse>

    @POST("webdl/controlwebdownload")
    suspend fun controlWebDownload(
        @Header("Authorization") token: String,
        @Body operation: TorBoxWebControlRequest,
    ): Response<TorBoxControlResponse>
}

private val torBoxKeyRegex = TORBOX_API_KEY_PATTERN.toRegex()

/** the torbox api key saved on its own, null when the torbox account is not active */
fun SharedPreferences.torBoxApiKey(): String? =
    getString(KEY_TORBOX_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * true when a token (with or without the "Bearer " prefix) is shaped like a torbox api key (a
 * uuid). Real debrid tokens never look like this, so this is used to route calls per token
 */
fun isTorBoxApiKey(token: String): Boolean =
    token.removePrefix("Bearer").trim().matches(torBoxKeyRegex)

private val torBoxErrorEnvelopeAdapter: JsonAdapter<TorBoxErrorEnvelope> =
    Moshi.Builder().build().adapter(TorBoxErrorEnvelope::class.java)

private val apiErrorAdapter: JsonAdapter<APIError> = Moshi.Builder().build().adapter(APIError::class.java)

/**
 * pulls a human readable message out of a failed torbox response, preferring the "detail" field of
 * its envelope (the more specific one in practice) over "error". Null when the error body is
 * missing, empty or not shaped like a torbox envelope. Reads (and consumes) [response]'s error
 * body, so it must only be called once per response
 */
fun torBoxErrorMessage(response: Response<*>): String? {
    val raw =
        try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
    if (raw.isNullOrBlank()) return null
    return try {
        val envelope = torBoxErrorEnvelopeAdapter.fromJson(raw)
        envelope?.detail?.trim()?.takeIf { it.isNotEmpty() }
            ?: envelope?.error?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * builds a real debrid style error response from a torbox error so the existing error parsing keeps
 * working. 401/403 are mapped to the real debrid bad token error code, 429 and 5xx get their own
 * torbox specific synthetic codes ([TORBOX_ERROR_RATE_LIMITED]/[TORBOX_ERROR_SERVICE_UNAVAILABLE])
 * so they get a clear torbox branded message instead of the generic real debrid ones. Everything
 * else falls back to the generic -1 code, carrying [rawMessage] (torbox's own error/detail text,
 * see [torBoxErrorMessage]) as the error_details field so getApiErrorMessage can show it instead of
 * a plain "internal error" when there is no specific mapping for the code
 */
fun <T> torBoxErrorResponse(code: Int, rawMessage: String? = null): Response<T> {
    val errorCode =
        when {
            code == 401 || code == 403 -> 8
            code == 429 -> TORBOX_ERROR_RATE_LIMITED
            code in 500..599 -> TORBOX_ERROR_SERVICE_UNAVAILABLE
            else -> -1
        }
    val json =
        apiErrorAdapter.toJson(
            APIError(error = "torbox_error", errorDetails = rawMessage, errorCode = errorCode)
        )
    val body = json.toResponseBody("application/json".toMediaTypeOrNull())
    return Response.error(if (code in 400..599) code else 500, body)
}
