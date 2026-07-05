package com.github.livingwithhippos.unchained.data.model

import com.github.livingwithhippos.unchained.utilities.PROVIDER_TORBOX
import com.github.livingwithhippos.unchained.utilities.TORBOX_LINK_SCHEME
import com.github.livingwithhippos.unchained.utilities.TORBOX_TORRENT_ID_PREFIX
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import timber.log.Timber

/*
 * torbox api models, see https://api.torbox.app/v1/api/
 * every response is wrapped in an envelope like
 * {"success": bool, "error": string|null, "detail": string, "data": ...}
 * concrete envelope classes are used instead of a generic one to keep moshi codegen simple
 */

@JsonClass(generateAdapter = true)
data class TorBoxUserResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
    @param:Json(name = "data") val data: TorBoxUser?,
)

@JsonClass(generateAdapter = true)
data class TorBoxTorrentListResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
    @param:Json(name = "data") val data: List<TorBoxTorrent>?,
)

@JsonClass(generateAdapter = true)
data class TorBoxTorrentResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
    @param:Json(name = "data") val data: TorBoxTorrent?,
)

@JsonClass(generateAdapter = true)
data class TorBoxCreateTorrentResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
    @param:Json(name = "data") val data: TorBoxCreatedTorrent?,
)

@JsonClass(generateAdapter = true)
data class TorBoxCreatedTorrent(
    @param:Json(name = "torrent_id") val torrentId: Int?,
    // torrents over the plan active limit only get a queued_id and cannot be followed
    @param:Json(name = "queued_id") val queuedId: Int?,
    @param:Json(name = "hash") val hash: String?,
    @param:Json(name = "name") val name: String?,
)

/** body of the controltorrent call, the operation is a string like "delete" */
@JsonClass(generateAdapter = true)
data class TorBoxControlRequest(
    @param:Json(name = "torrent_id") val torrentId: Int,
    @param:Json(name = "operation") val operation: String,
)

@JsonClass(generateAdapter = true)
data class TorBoxControlResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
)

/** response of torrents/requestdl, without redirect=true the data field is the download url */
@JsonClass(generateAdapter = true)
data class TorBoxRequestDownloadResponse(
    @param:Json(name = "success") val success: Boolean,
    @param:Json(name = "error") val error: String?,
    @param:Json(name = "detail") val detail: String?,
    @param:Json(name = "data") val data: String?,
)

@JsonClass(generateAdapter = true)
data class TorBoxUser(
    @param:Json(name = "id") val id: Int,
    @param:Json(name = "email") val email: String?,
    // 0 free, 1 essential, 2 pro, 3 standard
    @param:Json(name = "plan") val plan: Int?,
    @param:Json(name = "total_downloaded") val totalDownloaded: Long?,
    @param:Json(name = "premium_expires_at") val premiumExpiresAt: String?,
    @param:Json(name = "is_subscribed") val isSubscribed: Boolean?,
    @param:Json(name = "customer") val customer: String?,
    @param:Json(name = "cooldown_until") val cooldownUntil: String?,
)

@JsonClass(generateAdapter = true)
data class TorBoxTorrent(
    @param:Json(name = "id") val id: Int,
    @param:Json(name = "hash") val hash: String?,
    @param:Json(name = "name") val name: String?,
    @param:Json(name = "size") val size: Long?,
    @param:Json(name = "download_state") val downloadState: String?,
    // 0..1 float
    @param:Json(name = "progress") val progress: Double?,
    @param:Json(name = "download_speed") val downloadSpeed: Long?,
    @param:Json(name = "upload_speed") val uploadSpeed: Long?,
    @param:Json(name = "eta") val eta: Long?,
    @param:Json(name = "seeds") val seeds: Int?,
    @param:Json(name = "peers") val peers: Int?,
    @param:Json(name = "ratio") val ratio: Double?,
    @param:Json(name = "download_finished") val downloadFinished: Boolean?,
    @param:Json(name = "download_present") val downloadPresent: Boolean?,
    @param:Json(name = "active") val active: Boolean?,
    @param:Json(name = "created_at") val createdAt: String?,
    @param:Json(name = "files") val files: List<TorBoxTorrentFile>?,
)

@JsonClass(generateAdapter = true)
data class TorBoxTorrentFile(
    @param:Json(name = "id") val id: Int,
    @param:Json(name = "name") val name: String?,
    @param:Json(name = "short_name") val shortName: String?,
    @param:Json(name = "size") val size: Long?,
    @param:Json(name = "mimetype") val mimetype: String?,
    @param:Json(name = "s3_path") val s3Path: String?,
)

/** maps a torbox user to the real debrid [User] model used by the rest of the app */
fun TorBoxUser.toUser(): User {
    val isPremium = isSubscribed == true || (plan ?: 0) > 0
    var premiumSeconds = premiumSecondsLeft(premiumExpiresAt)
    // keep the premium label even if the expiration date is missing or unparsable
    if (isPremium && premiumSeconds <= 0) premiumSeconds = 1
    return User(
        id = id,
        username = email?.substringBefore("@") ?: "torbox user",
        email = email ?: "",
        points = 0,
        locale = "en",
        avatar = "",
        type = if (isPremium) "premium" else "free",
        premium = if (isPremium) premiumSeconds else 0,
        expiration = premiumExpiresAt ?: "",
    )
}

/**
 * maps a torbox torrent to the real debrid [TorrentItem] model used by the rest of the app. The id
 * is prefixed with [TORBOX_TORRENT_ID_PREFIX] so every action on the item can be routed back to
 * torbox, and the host is set to "torbox" to tell the items apart in the lists
 */
fun TorBoxTorrent.toTorrentItem(): TorrentItem {
    // requestdl only works once the files are on the torbox servers
    val downloadReady = downloadFinished == true || downloadPresent == true
    return TorrentItem(
        id = TORBOX_TORRENT_ID_PREFIX + id,
        filename = name ?: "",
        originalFilename = name,
        hash = hash ?: "",
        bytes = size ?: 0L,
        originalBytes = size,
        host = "torbox",
        split = 0,
        // torbox progress is a 0..1 float, real debrid uses 0..100
        progress = ((progress ?: 0.0) * 100).toFloat().coerceIn(0f, 100f),
        status = mapTorBoxStatus(),
        added = createdAt ?: "",
        files =
            files?.map {
                InnerTorrentFile(
                    id = it.id,
                    path = "/" + (it.name ?: it.shortName ?: ""),
                    bytes = it.size ?: 0L,
                    selected = 1,
                )
            },
        links =
            if (downloadReady) files.orEmpty().map { torBoxFileLink(id, it) } else emptyList(),
        ended = null,
        speed = downloadSpeed?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        seeders = seeds,
    )
}

/**
 * maps a createtorrent result to the real debrid [UploadedTorrent] model. Null when torbox only
 * queued the torrent (no torrent_id), since a queued torrent cannot be followed by the app flow
 */
fun TorBoxCreatedTorrent.toUploadedTorrent(): UploadedTorrent? {
    val newId = torrentId ?: return null
    // the app only uses the uri field for logging, pass the hash through
    return UploadedTorrent(id = TORBOX_TORRENT_ID_PREFIX + newId, uri = hash ?: "")
}

/**
 * builds the synthetic link representing a single torbox file, see [TORBOX_LINK_SCHEME]. The file
 * name, size and mimetype are encoded into the link so the unrestrict layer can synthesize a
 * [DownloadItem] without fetching the torrent again
 */
private fun torBoxFileLink(torrentId: Int, file: TorBoxTorrentFile): String {
    val fileName = file.shortName ?: file.name?.substringAfterLast('/') ?: ""
    return buildString {
        append(TORBOX_LINK_SCHEME)
        append(torrentId)
        append('/')
        append(file.id)
        append("?name=")
        append(URLEncoder.encode(fileName, "UTF-8"))
        append("&size=")
        append(file.size ?: 0L)
        if (!file.mimetype.isNullOrBlank()) {
            append("&mime=")
            append(URLEncoder.encode(file.mimetype, "UTF-8"))
        }
    }
}

/** the pieces encoded into a synthetic torbox file link */
data class TorBoxFileLink(
    val torrentId: Int,
    val fileId: Int,
    val name: String?,
    val size: Long?,
    val mimeType: String?,
)

/**
 * parses a link built by [torBoxFileLink] back into its components. Null when the link does not use
 * [TORBOX_LINK_SCHEME] or the ids are missing, the metadata parameters are optional
 */
fun parseTorBoxFileLink(link: String): TorBoxFileLink? {
    if (!link.startsWith(TORBOX_LINK_SCHEME)) return null
    val trimmed = link.removePrefix(TORBOX_LINK_SCHEME)
    val path = trimmed.substringBefore('?')
    val torrentId = path.substringBefore('/').toIntOrNull() ?: return null
    val fileId = path.substringAfter('/', "").toIntOrNull() ?: return null
    var name: String? = null
    var size: Long? = null
    var mime: String? = null
    trimmed.substringAfter('?', "").split('&').forEach { parameter ->
        val value = parameter.substringAfter('=', "")
        when (parameter.substringBefore('=')) {
            "name" -> name = decodeOrNull(value)?.takeIf { it.isNotBlank() }
            "size" -> size = value.toLongOrNull()
            "mime" -> mime = decodeOrNull(value)?.takeIf { it.isNotBlank() }
        }
    }
    return TorBoxFileLink(torrentId, fileId, name, size, mime)
}

private fun decodeOrNull(value: String): String? =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        Timber.w(e, "Could not decode torbox link parameter $value")
        null
    }

/**
 * builds a real debrid style [DownloadItem] for a torbox file: [downloadUrl] is the CDN url
 * returned by requestdl, [originalLink] the synthetic link it was exchanged for. Files with a
 * video or audio mimetype are marked streamable so the media buttons show up
 */
fun TorBoxFileLink.toDownloadItem(originalLink: String, downloadUrl: String): DownloadItem {
    val streamable =
        mimeType?.startsWith("video/") == true || mimeType?.startsWith("audio/") == true
    return DownloadItem(
        id = "$TORBOX_TORRENT_ID_PREFIX$torrentId-$fileId",
        filename = name ?: "torbox file $fileId",
        mimeType = mimeType,
        fileSize = size ?: 0L,
        link = originalLink,
        host = PROVIDER_TORBOX,
        hostIcon = null,
        chunks = 1,
        crc = null,
        download = downloadUrl,
        streamable = if (streamable) 1 else 0,
        generated = null,
        type = null,
        alternative = null,
    )
}

/** translates a torbox download_state into a real debrid torrent status */
private fun TorBoxTorrent.mapTorBoxStatus(): String {
    if (downloadFinished == true) return "downloaded"
    val state = downloadState ?: return "downloading"
    return when {
        state == "completed" || state == "cached" -> "downloaded"
        state == "uploading" -> "uploading"
        state == "paused" -> "queued"
        state.contains("error", ignoreCase = true) || state == "failed" -> "error"
        // downloading, metaDL, stalled (no seeds), checkingResumeData...
        else -> "downloading"
    }
}

private fun premiumSecondsLeft(expiration: String?): Int {
    if (expiration.isNullOrBlank()) return 0
    return try {
        val expires = OffsetDateTime.parse(expiration).toInstant()
        Duration.between(Instant.now(), expires)
            .seconds
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    } catch (e: Exception) {
        Timber.w(e, "Could not parse torbox expiration date $expiration")
        0
    }
}
