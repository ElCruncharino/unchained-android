package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.AvailableHost
import com.github.livingwithhippos.unchained.data.model.TorrentItem
import com.github.livingwithhippos.unchained.data.model.UploadedTorrent
import com.github.livingwithhippos.unchained.data.model.toTorrentItem
import javax.inject.Inject
import okhttp3.RequestBody
import retrofit2.Response

class TorrentApiHelperImpl
@Inject
constructor(
    private val torrentsApi: TorrentsApi,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
) : TorrentApiHelper {
    override suspend fun getAvailableHosts(token: String): Response<List<AvailableHost>> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.getAvailableHosts(token)

    override suspend fun getTorrentInfo(token: String, id: String): Response<TorrentItem> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.getTorrentInfo(token, id)

    override suspend fun addTorrent(
        token: String,
        binaryTorrent: RequestBody,
        host: String,
    ): Response<UploadedTorrent> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.addTorrent(token, binaryTorrent, host)

    override suspend fun addMagnet(
        token: String,
        magnet: String,
        host: String,
    ): Response<UploadedTorrent> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.addMagnet(token, magnet, host)

    override suspend fun getTorrentsList(
        token: String,
        offset: Int?,
        page: Int?,
        limit: Int?,
        filter: String?,
    ): Response<List<TorrentItem>> {
        if (!preferences.isTorBoxProvider())
            return torrentsApi.getTorrentsList(token, offset, page, limit, filter)
        // torbox uses offset based paging while real debrid uses pages
        val torBoxLimit = limit ?: 50
        val torBoxOffset = offset ?: (((page ?: 1) - 1) * torBoxLimit)
        val response = torBoxApi.getTorrentsList(token, offset = torBoxOffset, limit = torBoxLimit)
        val torrents = response.body()?.data
        return if (response.isSuccessful)
            Response.success(torrents.orEmpty().map { it.toTorrentItem() })
        else torBoxErrorResponse(response.code())
    }

    override suspend fun selectFiles(token: String, id: String, files: String): Response<Unit> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.selectFiles(token, id, files)

    override suspend fun deleteTorrent(token: String, id: String): Response<Unit> =
        if (preferences.isTorBoxProvider()) torBoxErrorResponse(501)
        else torrentsApi.deleteTorrent(token, id)
}
