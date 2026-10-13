package com.yunpan.tingshu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunpan.tingshu.data.model.ScanDepth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 一条已保存的分享源（可反复进入，非一次性跳转）。 */
data class SavedShare(
    val linkId: String,
    val name: String,
    val passwd: String = ""
)

/**
 * UI 设置存储（DataStore preferences）：默认递归扫描深度、响度增益、分享源列表、自动续播。
 * 不改动既有 data/cloud139/player 逻辑，仅作 UI 偏好。
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>
) {
    private val defaultDepthKey = stringPreferencesKey("default_scan_depth")

    /** 默认扫描深度，未设置回退 [ScanDepth.CURRENT]。 */
    val defaultDepth: Flow<ScanDepth> = dataStore.data.map { p ->
        p[defaultDepthKey]?.let { runCatching { ScanDepth.valueOf(it) }.getOrNull() }
            ?: ScanDepth.CURRENT
    }

    suspend fun setDefaultDepth(depth: ScanDepth) {
        dataStore.edit { it[defaultDepthKey] = depth.name }
    }

    private val volumeGainDbKey = intPreferencesKey("volume_gain_db")

    /** 响度增益档位(dB)，未设置回退 0（关闭）。 */
    val volumeGainDb: Flow<Int> = dataStore.data.map { p -> p[volumeGainDbKey] ?: 0 }

    suspend fun setVolumeGainDb(db: Int) {
        dataStore.edit { it[volumeGainDbKey] = db }
    }

    // ---- 分享源持久化 ----
    private val shareSourcesKey = stringPreferencesKey("share_sources")
    private val ENTRY_SEP = ""
    private val FIELD_SEP = ""

    /** 已保存分享源列表（最近在前）。 */
    val shareSources: Flow<List<SavedShare>> = dataStore.data.map { p ->
        p[shareSourcesKey]?.deserializeShareSources() ?: emptyList()
    }

    /** 新增/更新分享源（按 linkId 去重），置顶到最前。 */
    suspend fun upsertShareSource(share: SavedShare) {
        dataStore.edit { p ->
            val cur = (p[shareSourcesKey]?.deserializeShareSources() ?: emptyList()).toMutableList()
            cur.removeAll { it.linkId == share.linkId }
            cur.add(0, share)
            p[shareSourcesKey] = cur.serializeShareSources()
        }
    }

    suspend fun removeShareSource(linkId: String) {
        dataStore.edit { p ->
            val cur = (p[shareSourcesKey]?.deserializeShareSources() ?: emptyList()).toMutableList()
            cur.removeAll { it.linkId == linkId }
            p[shareSourcesKey] = cur.serializeShareSources()
        }
    }

    private fun String?.deserializeShareSources(): List<SavedShare> {
        if (this.isNullOrEmpty()) return emptyList()
        return split(ENTRY_SEP).filter { it.isNotEmpty() }.mapNotNull { entry ->
            val f = entry.split(FIELD_SEP)
            if (f.size >= 2) SavedShare(f[0], f[1], f.getOrElse(2) { "" }) else null
        }
    }

    private fun List<SavedShare>.serializeShareSources(): String =
        joinToString(ENTRY_SEP) { listOf(it.linkId, it.name, it.passwd).joinToString(FIELD_SEP) }

    // ---- 启动自动续播开关 ----
    private val autoResumeKey = androidx.datastore.preferences.core.booleanPreferencesKey("auto_resume_on_launch")

    /** 启动自动续播（默认 false，用户手动开启）。 */
    val autoResumeOnLaunch: Flow<Boolean> = dataStore.data.map { p -> p[autoResumeKey] ?: false }

    suspend fun setAutoResumeOnLaunch(enabled: Boolean) {
        dataStore.edit { it[autoResumeKey] = enabled }
    }

    // ---- 智能跳过片头/片尾（秒）----
    private val skipIntroSecKey = intPreferencesKey("skip_intro_sec")
    private val skipOutroSecKey = intPreferencesKey("skip_outro_sec")

    /** 片头跳过秒数（0/15/30/60/90），默认 0（不跳过）。 */
    val skipIntroSec: Flow<Int> = dataStore.data.map { p ->
        (p[skipIntroSecKey] ?: 0).coerceIn(0, 90)
    }

    suspend fun setSkipIntroSec(sec: Int) {
        dataStore.edit { it[skipIntroSecKey] = sec.coerceIn(0, 90) }
    }

    /** 片尾跳过秒数（0/15/30/60/90），默认 0（不跳过）。 */
    val skipOutroSec: Flow<Int> = dataStore.data.map { p ->
        (p[skipOutroSecKey] ?: 0).coerceIn(0, 90)
    }

    suspend fun setSkipOutroSec(sec: Int) {
        dataStore.edit { it[skipOutroSecKey] = sec.coerceIn(0, 90) }
    }

    /** 片头跳过毫秒流（引擎用）。 */
    fun skipIntroMsFlow(): Flow<Long> = skipIntroSec.map { it.toLong() * 1000L }

    /** 片尾跳过毫秒流（引擎用）。 */
    fun skipOutroMsFlow(): Flow<Long> = skipOutroSec.map { it.toLong() * 1000L }

    companion object {
        fun create(context: Context): SettingsStore =
            SettingsStore(context.settingsDataStore)
    }
}
