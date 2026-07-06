package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.DownloadItem
import com.github.livingwithhippos.unchained.data.model.isDownloadReady
import com.github.livingwithhippos.unchained.data.model.parseTorBoxFileLink
import com.github.livingwithhippos.unchained.data.model.toDownloadItem
import com.github.livingwithhippos.unchained.utilities.TORBOX_ERROR_WEBDL_QUEUED
import javax.inject.Inject
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import timber.log.Timber

class UnrestrictApiHelperImpl
@Inject
constructor(
    private val unrestrictApi: UnrestrictApi,
    private val torBoxApi: TorBoxApi,
    private val preferences: SharedPreferences,
) : UnrestrictApiHelper {

    /**
     * the raw torbox api key, used as a query parameter by requestdl (no "Bearer " prefix): the
     * dedicated preference when available, otherwise the logged in token itself when torbox was
     * used for the main login. Null when the torbox account is not active
     */
    private fun torBoxRawKey(token: String): String? {
        preferences.torBoxApiKey()?.let {
            return it
        }
        val bareToken = token.removePrefix("Bearer").trim()
        return if (isTorBoxApiKey(bareToken)) bareToken else null
    }

    override suspend fun getUnrestrictedLink(
        token: String,
        link: String,
        password: String?,
        remote: Int?,
    ): Response<DownloadItem> {
        // synthetic torbox file links are exchanged for a CDN url via requestdl
        parseTorBoxFileLink(link)?.let { torBoxLink ->
            val key = torBoxRawKey(token) ?: return torBoxErrorResponse(401)
            val response =
                torBoxApi.requestDownloadLink(key, torBoxLink.torrentId, torBoxLink.fileId)
            val downloadUrl = response.body()?.data
            return if (response.isSuccessful && !downloadUrl.isNullOrBlank())
                Response.success(
                    torBoxLink.toDownloadItem(originalLink = link, downloadUrl = downloadUrl)
                )
            else torBoxErrorResponse(response.code(), torBoxErrorMessage(response))
        }
        // pasted hoster links go to real debrid whenever it is active (instant unrestricting is
        // the better experience), only torbox-only accounts use the torbox web downloads
        if (!isTorBoxApiKey(token)) {
            return unrestrictApi.getUnrestrictedLink(token, link, password, remote)
        }
        return unrestrictThroughTorBox(token, link, password)
    }

    /**
     * queues [link] as a torbox web download, then polls briefly for the file to be fetched: when
     * it becomes available within the polling window the first file is exchanged for a CDN url and
     * returned like a real debrid unrestrict would. Otherwise a synthetic error with
     * [TORBOX_ERROR_WEBDL_QUEUED] tells the user the download will appear in the list later
     */
    private suspend fun unrestrictThroughTorBox(
        token: String,
        link: String,
        password: String?,
    ): Response<DownloadItem> {
        val key = torBoxRawKey(token) ?: return torBoxErrorResponse(401)
        val auth = "Bearer $key"

        val createResponse = torBoxApi.createWebDownload(auth, link, password)
        if (!createResponse.isSuccessful)
            return torBoxErrorResponse(createResponse.code(), torBoxErrorMessage(createResponse))
        val created = createResponse.body()?.data
        val webId = created?.webDownloadId ?: created?.id
        if (webId == null) {
            val detail = createResponse.body()?.detail ?: createResponse.body()?.error
            Timber.w("createwebdownload returned no id: $detail")
            return torBoxErrorResponse(500, detail)
        }

        // fetching from the hoster is asynchronous, poll for a bit before giving up
        repeat(WEBDL_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(WEBDL_POLL_INTERVAL_MS)
            val webDownload =
                try {
                    torBoxApi.getWebDownload(auth, webId.toString()).body()?.data
                } catch (e: Exception) {
                    Timber.w(e, "Error polling the torbox web download $webId")
                    null
                }
            if (webDownload != null && webDownload.isDownloadReady()) {
                val file = webDownload.files?.firstOrNull() ?: return@repeat
                val dlResponse = torBoxApi.requestWebDownloadLink(key, webId, file.id)
                val downloadUrl = dlResponse.body()?.data
                return if (dlResponse.isSuccessful && !downloadUrl.isNullOrBlank())
                    Response.success(webDownload.toDownloadItem(file, downloadUrl))
                else torBoxErrorResponse(dlResponse.code(), torBoxErrorMessage(dlResponse))
            }
        }

        // still fetching: the queued download will show up in the downloads list once ready
        val body =
            "{\"error\":\"torbox_webdl_queued\",\"error_code\":$TORBOX_ERROR_WEBDL_QUEUED}"
                .toResponseBody("application/json".toMediaTypeOrNull())
        return Response.error(400, body)
    }

    override suspend fun getUnrestrictedFolder(
        token: String,
        link: String,
    ): Response<List<String>> = unrestrictApi.getUnrestrictedFolder(token, link)

    override suspend fun uploadContainer(
        token: String,
        container: RequestBody,
    ): Response<List<String>> = unrestrictApi.uploadContainer(token, container)

    override suspend fun getContainerLinks(token: String, link: String): Response<List<String>> =
        unrestrictApi.getContainerLinks(token, link)

    companion object {
        /** first check plus five retries, three seconds apart: about fifteen seconds in total */
        private const val WEBDL_POLL_ATTEMPTS = 6
        private const val WEBDL_POLL_INTERVAL_MS = 3_000L
    }
}
