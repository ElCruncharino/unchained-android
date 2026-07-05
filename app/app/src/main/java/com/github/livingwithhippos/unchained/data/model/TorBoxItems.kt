package com.github.livingwithhippos.unchained.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
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

/** maps a torbox torrent to the real debrid [TorrentItem] model used by the rest of the app */
fun TorBoxTorrent.toTorrentItem(): TorrentItem {
    return TorrentItem(
        id = id.toString(),
        filename = name ?: "",
        originalFilename = name,
        hash = hash ?: "",
        bytes = size ?: 0L,
        originalBytes = size,
        host = "torbox.app",
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
        links = emptyList(),
        ended = null,
        speed = downloadSpeed?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        seeders = seeds,
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
