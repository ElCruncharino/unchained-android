package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.local.TorBoxDownloadDao
import com.github.livingwithhippos.unchained.data.model.DownloadItem
import com.github.livingwithhippos.unchained.data.model.TorBoxWebControlRequest
import com.github.livingwithhippos.unchained.data.model.toDownloadItem
import com.github.livingwithhippos.unchained.data.model.toDownloadItems
import com.github.livingwithhippos.unchained.utilities.TORBOX_TORRENT_ID_PREFIX
import com.github.livingwithhippos.unchained.utilities.TORBOX_WEBDL_ID_PREFIX
import com.github.livingwithhippos.unchained.utilities.sortedByRecencyDescending
import javax.inject.Inject
import retrofit2.Response
import timber.log.Timber

/**
 * the torbox download history dao is injected into this remote helper on purpose: the merged
 * downloads first page is assembled here (real debrid page plus torbox web downloads), and the
 * local history rows are just one more source of that same page. Pushing the merge up to the
 * repository would need a bigger refactoring of a layer this POC deliberately keeps untouched
 */
class DownloadApiHelperImpl
@Inject
constructor(
    private val downloadApi: DownloadApi,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
    private val torBoxDownloadDao: TorBoxDownloadDao,
) : DownloadApiHelper {

    /** when the stored token is not a torbox api key the user is logged into real debrid */
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

    /** the raw torbox api key, embedded in the requestdl permalinks as the token parameter */
    private fun torBoxRawKey(token: String): String? {
        preferences.torBoxApiKey()?.let {
            return it
        }
        val bareToken = token.removePrefix("Bearer").trim()
        return if (isTorBoxApiKey(bareToken)) bareToken else null
    }

    /**
     * mirrors the merged torrents list: the real debrid page as usual, plus the whole torbox web
     * downloads list (one [DownloadItem] per file) appended to the first page only. A torbox
     * failure never drops the real debrid page, a real debrid failure propagates as before
     */
    override suspend fun getDownloads(
        token: String,
        offset: Int?,
        page: Int,
        limit: Int,
    ): Response<List<DownloadItem>> {
        val realDebridActive = isRealDebridActive(token)
        val torBoxAuth = torBoxAuth(token)
        val firstPage = (offset == null || offset == 0) && page <= 1

        var realDebridDownloads: List<DownloadItem> = emptyList()
        if (realDebridActive) {
            val rdResponse = downloadApi.getDownloads(token, offset, page, limit)
            // without an active torbox account (or beyond the first page) the real debrid
            // response is used as is
            if (torBoxAuth == null || !firstPage || !rdResponse.isSuccessful) return rdResponse
            realDebridDownloads = rdResponse.body().orEmpty()
        } else if (!firstPage) {
            return Response.success(emptyList())
        }

        val rawKey = torBoxRawKey(token)
        if (torBoxAuth == null || rawKey == null) return Response.success(realDebridDownloads)

        val torBoxDownloads =
            try {
                val tbResponse =
                    torBoxApi.getWebDownloadsList(
                        torBoxAuth,
                        offset = 0,
                        limit = TorrentApiHelperImpl.TORBOX_LIST_LIMIT,
                    )
                when {
                    tbResponse.isSuccessful ->
                        tbResponse.body()?.data.orEmpty().flatMap { it.toDownloadItems(rawKey) }
                    !realDebridActive -> return torBoxErrorResponse(tbResponse.code())
                    else -> {
                        Timber.w("TorBox web downloads list returned ${tbResponse.code()}")
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                // do not lose the real debrid page when only the torbox call fails
                if (!realDebridActive) throw e
                Timber.w(e, "Error fetching the torbox web downloads list, skipping")
                emptyList<DownloadItem>()
            }

        // local history of the torrent files fetched through requestdl (torbox keeps no account
        // side list of those): merged newest first after the web downloads, each row carrying a
        // torrents/requestdl redirect permalink so no extra api calls are needed. Reading the
        // table is best effort, a database problem never drops the rest of the page
        val torrentFileHistory =
            try {
                torBoxDownloadDao.getAll().map { it.toDownloadItem(rawKey) }
            } catch (e: Exception) {
                Timber.w(e, "Error reading the local torbox download history, skipping")
                emptyList<DownloadItem>()
            }

        // only worth parsing dates and re-sorting when at least two of the three sources actually
        // contributed rows: with a single non-empty source the plain concatenation is already in
        // the right order
        val mergedSourceCount =
            listOf(realDebridDownloads, torBoxDownloads, torrentFileHistory).count {
                it.isNotEmpty()
            }
        val merged =
            (realDebridDownloads + torBoxDownloads + torrentFileHistory).distinctBy { it.id }
        return Response.success(
            if (mergedSourceCount > 1) merged.sortedByRecencyDescending { it.generated }
            else merged
        )
    }

    override suspend fun deleteDownload(token: String, id: String): Response<Unit> {
        // web download items: any file row of a web download deletes the whole web download,
        // torbox has no per file entries
        if (id.startsWith(TORBOX_WEBDL_ID_PREFIX)) {
            val auth = torBoxAuth(token) ?: return torBoxErrorResponse(401)
            val webId =
                id.removePrefix(TORBOX_WEBDL_ID_PREFIX).substringBefore('-').toIntOrNull()
                    ?: return torBoxErrorResponse(400)
            val response =
                torBoxApi.controlWebDownload(
                    auth,
                    TorBoxWebControlRequest(webId, TorrentApiHelperImpl.OPERATION_DELETE),
                )
            return if (response.isSuccessful && response.body()?.success == true)
                Response.success(Unit)
            else torBoxErrorResponse(response.code())
        }
        // download items minted from torbox torrent files exist only in the local history table
        // (torbox keeps no account side list of them), so deleting one removes the local row.
        // This is also how rows whose parent torrent was deleted on torbox (dead permalinks) are
        // cleaned up. Failures are logged but still reported as success: there is nothing to
        // delete server side either way
        if (id.startsWith(TORBOX_TORRENT_ID_PREFIX)) {
            try {
                torBoxDownloadDao.remove(id)
            } catch (e: Exception) {
                Timber.w(e, "Error removing the local torbox download history row $id")
            }
            return Response.success(Unit)
        }
        return downloadApi.deleteDownload(token, id)
    }
}
