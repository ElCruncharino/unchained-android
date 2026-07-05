package com.github.livingwithhippos.unchained.data.repository

import android.os.SystemClock
import android.util.LruCache
import com.github.livingwithhippos.unchained.data.local.ProtoStore
import com.github.livingwithhippos.unchained.data.local.TorBoxDownload
import com.github.livingwithhippos.unchained.data.local.TorBoxDownloadDao
import com.github.livingwithhippos.unchained.data.model.DownloadItem
import com.github.livingwithhippos.unchained.data.model.UnchainedNetworkException
import com.github.livingwithhippos.unchained.data.model.parseTorBoxFileLink
import com.github.livingwithhippos.unchained.data.remote.UnrestrictApiHelper
import com.github.livingwithhippos.unchained.utilities.EitherResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class UnrestrictRepository
@Inject
constructor(
    protoStore: ProtoStore,
    private val unrestrictApiHelper: UnrestrictApiHelper,
    private val torBoxDownloadDao: TorBoxDownloadDao,
) : BaseRepository(protoStore) {

    private val unrestrictedLinkCache =
        LruCache<UnrestrictedLinkCacheKey, CachedUnrestrictedLink>(
            UNRESTRICTED_LINK_CACHE_MAX_ENTRIES
        )

    suspend fun getEitherUnrestrictedLink(
        link: String,
        password: String? = null,
        remote: Int? = null,
    ): EitherResult<UnchainedNetworkException, DownloadItem> {
        val token = getToken()
        val cacheKey = UnrestrictedLinkCacheKey(link = link, password = password, remote = remote)

        getCachedUnrestrictedLink(cacheKey)?.let {
            recordTorBoxDownload(link, it)
            return EitherResult.Success(it)
        }

        val linkResponse =
            eitherApiResult(
                call = {
                    unrestrictApiHelper.getUnrestrictedLink(
                        token = "Bearer $token",
                        link = link,
                        password = password,
                        remote = remote,
                    )
                },
                errorMessage = "Error Fetching Unrestricted Link Info",
            )

        if (linkResponse is EitherResult.Success) {
            cacheUnrestrictedLink(cacheKey, linkResponse.success)
            recordTorBoxDownload(link, linkResponse.success)
        }

        return linkResponse
    }

    /**
     * torbox keeps no account side history of the files unrestricted from torrents, so every
     * successful exchange of a synthetic torbox:// link is recorded in a local table that the
     * downloads tab merges into its first page, mirroring the real debrid downloads history. Every
     * torbox torrent file fetch (direct download, folder list, send to player) goes through
     * [getEitherUnrestrictedLink], making this the single recording point. The insert is best
     * effort: a database problem is only logged and never breaks the download itself
     */
    private suspend fun recordTorBoxDownload(link: String, item: DownloadItem) {
        val torBoxLink = parseTorBoxFileLink(link) ?: return
        try {
            torBoxDownloadDao.upsert(
                TorBoxDownload(
                    id = item.id,
                    link = link,
                    filename = item.filename,
                    size = item.fileSize,
                    mimeType = item.mimeType,
                    torrentId = torBoxLink.torrentId,
                    fileId = torBoxLink.fileId,
                    addedDate = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not record the torbox download ${item.id} in the local history")
        }
    }

    fun clearUnrestrictedLinkCache() {
        synchronized(unrestrictedLinkCache) { unrestrictedLinkCache.evictAll() }
    }

    suspend fun getUnrestrictedLinkList(
        linksList: List<String>,
        password: String? = null,
        remote: Int? = null,
        callDelay: Long = 100,
    ): List<EitherResult<UnchainedNetworkException, DownloadItem>> {
        val unrestrictedLinks =
            mutableListOf<EitherResult<UnchainedNetworkException, DownloadItem>>()
        linksList.forEach {
            unrestrictedLinks.add(getEitherUnrestrictedLink(it, password, remote))
            // just to be on the safe side...
            delay(callDelay.milliseconds)
        }
        return unrestrictedLinks
    }

    suspend fun getEitherFolderLinks(
        link: String
    ): EitherResult<UnchainedNetworkException, List<String>> {
        val token = getToken()

        val folderResponse: EitherResult<UnchainedNetworkException, List<String>> =
            eitherApiResult(
                call = {
                    unrestrictApiHelper.getUnrestrictedFolder(token = "Bearer $token", link = link)
                },
                errorMessage = "Error Fetching Unrestricted Folders Info",
            )

        return folderResponse
    }

    suspend fun uploadContainer(
        container: ByteArray
    ): EitherResult<UnchainedNetworkException, List<String>> {
        val token = getToken()

        val requestBody: RequestBody =
            container.toRequestBody(
                "application/octet-stream".toMediaTypeOrNull(),
                0,
                container.size,
            )

        val uploadResponse =
            eitherApiResult(
                call = {
                    unrestrictApiHelper.uploadContainer(
                        token = "Bearer $token",
                        container = requestBody,
                    )
                },
                errorMessage = "Error Uploading Container",
            )

        return uploadResponse
    }

    suspend fun getContainerLinks(link: String): List<String>? {
        val token = getToken()

        val containerResponse =
            safeApiCall(
                call = {
                    unrestrictApiHelper.getContainerLinks(token = "Bearer $token", link = link)
                },
                errorMessage = "Error getting container files",
            )

        return containerResponse
    }

    private fun cacheUnrestrictedLink(key: UnrestrictedLinkCacheKey, item: DownloadItem) {
        synchronized(unrestrictedLinkCache) {
            unrestrictedLinkCache.put(
                key,
                CachedUnrestrictedLink(
                    item = item,
                    createdAtElapsedRealtime = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    private fun getCachedUnrestrictedLink(key: UnrestrictedLinkCacheKey): DownloadItem? =
        synchronized(unrestrictedLinkCache) {
            val cachedLink = unrestrictedLinkCache.get(key) ?: return@synchronized null
            val cacheAge = SystemClock.elapsedRealtime() - cachedLink.createdAtElapsedRealtime

            if (cacheAge <= UNRESTRICTED_LINK_CACHE_TTL_MS) {
                cachedLink.item
            } else {
                unrestrictedLinkCache.remove(key)
                null
            }
        }

    private data class UnrestrictedLinkCacheKey(
        val link: String,
        val password: String?,
        val remote: Int?,
    )

    private data class CachedUnrestrictedLink(
        val item: DownloadItem,
        val createdAtElapsedRealtime: Long,
    )

    companion object {
        private const val UNRESTRICTED_LINK_CACHE_MAX_ENTRIES = 5000
        private const val UNRESTRICTED_LINK_CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    }
}
