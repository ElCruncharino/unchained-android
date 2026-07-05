package com.github.livingwithhippos.unchained.data.local

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local download history row for a torbox torrent file. TorBox has no account side list of the
 * files unrestricted from torrents (unlike the real debrid downloads history or the torbox web
 * downloads), so every file exchanged for a CDN url is recorded here to give the downloads tab the
 * same history real debrid users get. A row can outlive its torrent: deleting the torrent on
 * torbox does not touch this table, the row is only removed when the user deletes it from the
 * downloads list
 */
@Keep
@Entity(tableName = "torbox_download")
data class TorBoxDownload(
    /** the download item id, tb-[torrentId]-[fileId] */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** the synthetic torbox:// link, encodes name/size/mime and can be unrestricted again */
    @ColumnInfo(name = "link") val link: String,
    @ColumnInfo(name = "filename") val filename: String,
    /** bytes, 0 if unknown */
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mimetype") val mimeType: String?,
    @ColumnInfo(name = "torrent_id") val torrentId: Int,
    @ColumnInfo(name = "file_id") val fileId: Int,
    /** last time the file was fetched, epoch millis */
    @ColumnInfo(name = "added_date") val addedDate: Long,
)
