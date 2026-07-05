package com.github.livingwithhippos.unchained.data.remote

import android.content.SharedPreferences
import com.github.livingwithhippos.unchained.data.model.DownloadItem
import com.github.livingwithhippos.unchained.data.model.parseTorBoxFileLink
import com.github.livingwithhippos.unchained.data.model.toDownloadItem
import javax.inject.Inject
import okhttp3.RequestBody
import retrofit2.Response

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
        // synthetic torbox file links are exchanged for a CDN url via requestdl, everything else
        // goes to the real debrid unrestrict endpoint as before
        val torBoxLink =
            parseTorBoxFileLink(link)
                ?: return unrestrictApi.getUnrestrictedLink(token, link, password, remote)
        val key = torBoxRawKey(token) ?: return torBoxErrorResponse(401)
        val response = torBoxApi.requestDownloadLink(key, torBoxLink.torrentId, torBoxLink.fileId)
        val downloadUrl = response.body()?.data
        return if (response.isSuccessful && !downloadUrl.isNullOrBlank())
            Response.success(torBoxLink.toDownloadItem(originalLink = link, downloadUrl = downloadUrl))
        else torBoxErrorResponse(response.code())
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
}
