package com.github.livingwithhippos.unchained.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TorBoxDownloadDao {

    /** fetching the same file twice keeps a single row with a refreshed timestamp */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: TorBoxDownload)

    @Query("SELECT * FROM torbox_download ORDER BY added_date DESC")
    suspend fun getAll(): List<TorBoxDownload>

    @Query("DELETE FROM torbox_download WHERE id = :id") suspend fun remove(id: String)
}
