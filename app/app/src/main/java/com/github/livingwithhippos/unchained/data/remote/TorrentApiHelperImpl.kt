package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.AvailableHost
import com.github.livingwithhippos.unchained.data.model.TorrentItem
import com.github.livingwithhippos.unchained.data.model.UploadedTorrent
import com.github.livingwithhippos.unchained.data.model.toTorrentItem
import com.github.livingwithhippos.unchained.utilities.PROVIDER_TORBOX
import com.github.livingwithhippos.unchained.utilities.TORBOX_TORRENT_ID_PREFIX
import javax.inject.Inject
import okhttp3.RequestBody
import retrofit2.Response
import timber.log.Timber

class TorrentApiHelperImpl
@Inject
constructor(
    private val torrentsApi: TorrentsApi,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
) : TorrentApiHelper {

    /**
     * the tokens the repositories pass come from the single credentials storage: when the stored
     * token is not a torbox api key the user is logged into real debrid
     */
    private fun isRealDebridActive(token: String): Boolean = !isTorBoxApiKey(token)

    /**
     * authentication header for the torbox calls: the dedicated preference when available,
     * otherwise the logged in token itself when torbox was used for the main login. Null when the
     * torbox account is not active
     */
    private fun torBoxAuth(token: String): String? {
        val key = preferences.torBoxApiKey()
        if (key != null) return "Bearer $key"
        return if (isTorBoxApiKey(token)) token else null
    }

    override suspend fun getAvailableHosts(token: String): Response<List<AvailableHost>> =
        if (isRealDebridActive(token)) torrentsApi.getAvailableHosts(token)
        // torbox has no hosts concept but the add torrent flows need a non empty list
        else Response.success(listOf(AvailableHost(host = PROVIDER_TORBOX, maxFileSize = 0)))

    override suspend fun getTorrentInfo(token: String, id: String): Response<TorrentItem> =
        if (id.startsWith(TORBOX_TORRENT_ID_PREFIX)) torBoxErrorResponse(501)
        else torrentsApi.getTorrentInfo(token, id)

    override suspend fun addTorrent(
        token: String,
        binaryTorrent: RequestBody,
        host: String,
    ): Response<UploadedTorrent> =
        if (isRealDebridActive(token)) torrentsApi.addTorrent(token, binaryTorrent, host)
        else torBoxErrorResponse(501)

    override suspend fun addMagnet(
        token: String,
        magnet: String,
        host: String,
    ): Response<UploadedTorrent> =
        if (isRealDebridActive(token)) torrentsApi.addMagnet(token, magnet, host)
        else torBoxErrorResponse(501)

    override suspend fun getTorrentsList(
        token: String,
        offset: Int?,
        page: Int?,
        limit: Int?,
        filter: String?,
    ): Response<List<TorrentItem>> {
        val realDebridActive = isRealDebridActive(token)
        val torBoxAuth = torBoxAuth(token)
        // torbox returns up to 1000 items in a single call, so its whole list is merged into the
        // first page only
        val firstPage = (offset == null || offset == 0) && (page == null || page <= 1)

        var realDebridTorrents: List<TorrentItem> = emptyList()
        if (realDebridActive) {
            val rdResponse = torrentsApi.getTorrentsList(token, offset, page, limit, filter)
            // without an active torbox account (or beyond the first page) the real debrid
            // response is used as is
            if (torBoxAuth == null || !firstPage || !rdResponse.isSuccessful) return rdResponse
            realDebridTorrents = rdResponse.body().orEmpty()
        } else if (!firstPage) {
            return Response.success(emptyList())
        }

        if (torBoxAuth == null) return Response.success(realDebridTorrents)

        val torBoxTorrents =
            try {
                val tbResponse =
                    torBoxApi.getTorrentsList(torBoxAuth, offset = 0, limit = TORBOX_LIST_LIMIT)
                when {
                    tbResponse.isSuccessful ->
                        tbResponse.body()?.data.orEmpty().map { it.toTorrentItem() }
                    !realDebridActive -> return torBoxErrorResponse(tbResponse.code())
                    else -> {
                        Timber.w("TorBox torrents list returned ${tbResponse.code()}, skipping")
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                // do not lose the real debrid page when only the torbox call fails
                if (!realDebridActive) throw e
                Timber.w(e, "Error fetching the torbox torrents list, skipping")
                emptyList<TorrentItem>()
            }

        return Response.success(realDebridTorrents + torBoxTorrents)
    }

    override suspend fun selectFiles(token: String, id: String, files: String): Response<Unit> =
        // torbox has no file selection phase, report success if it gets called anyway
        if (id.startsWith(TORBOX_TORRENT_ID_PREFIX)) Response.success(Unit)
        else torrentsApi.selectFiles(token, id, files)

    override suspend fun deleteTorrent(token: String, id: String): Response<Unit> =
        if (id.startsWith(TORBOX_TORRENT_ID_PREFIX)) torBoxErrorResponse(501)
        else torrentsApi.deleteTorrent(token, id)

    companion object {
        /** torbox caps the list endpoint at 1000 items */
        const val TORBOX_LIST_LIMIT = 1000
    }
}
