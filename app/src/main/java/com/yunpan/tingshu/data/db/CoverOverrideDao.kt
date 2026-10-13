package com.yunpan.tingshu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CoverOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: CoverOverride)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun batchUpsert(overrides: List<CoverOverride>)

    @Query("SELECT * FROM cover_override WHERE scope = :scope AND target_key = :targetKey LIMIT 1")
    suspend fun getOverride(scope: String, targetKey: String): CoverOverride?

    @Query("DELETE FROM cover_override WHERE scope = :scope AND target_key = :targetKey")
    suspend fun deleteOverride(scope: String, targetKey: String)

    @Query("SELECT * FROM cover_override")
    suspend fun getAllOverrides(): List<CoverOverride>
}
