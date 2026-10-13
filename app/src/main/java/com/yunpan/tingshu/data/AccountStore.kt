package com.yunpan.tingshu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunpan.tingshu.cloud139.Cloud139Token
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.util.Md5
import kotlinx.coroutines.flow.first

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "accounts")

/** 多账号存储（DataStore preferences，SPEC §4）。 */
interface AccountStore {
    suspend fun accounts(): List<CloudAccount>
    suspend fun active(): CloudAccount?
    suspend fun setActive(id: String)
    /** 解析+校验后保存；解析失败抛 IllegalArgumentException(中文信息) */
    suspend fun addFromAuthorization(raw: String): CloudAccount
    suspend fun remove(id: String)
}

/**
 * 纯函数：把用户粘贴的 Authorization 解析成 CloudAccount。
 * UI 宽容（先对整串 trim 去掉前导空白/换行），纯解析严格（见 Cloud139Token）。
 */
object Accounts {
    fun fromAuthorization(raw: String, nowMs: Long): CloudAccount {
        val trimmed = raw.trim()
        val p = try {
            Cloud139Token.parse(trimmed)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Authorization 令牌解析失败：${e.message}", e)
        }
        val id = p.account.ifEmpty { "cloud139_" + Md5.hex(p.rawToken).take(12) }
        return CloudAccount(
            id = id,
            phone = p.account,
            rawToken = p.rawToken,
            expireAtMs = p.expireAtMs,
            displayName = p.account.ifEmpty { "139云盘" },
            addedAtMs = nowMs
        )
    }
}

class DefaultAccountStore(
    private val dataStore: DataStore<Preferences>,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : AccountStore {

    companion object {
        fun create(context: Context): DefaultAccountStore =
            DefaultAccountStore(context.accountDataStore)
    }

    private val idsKey = stringSetPreferencesKey("account_ids")
    private val activeKey = stringPreferencesKey("active_id")

    private fun rawKey(id: String) = stringPreferencesKey("acc.$id.raw")
    private fun phoneKey(id: String) = stringPreferencesKey("acc.$id.phone")
    private fun expireKey(id: String) = longPreferencesKey("acc.$id.expire")
    private fun nameKey(id: String) = stringPreferencesKey("acc.$id.name")
    private fun addedKey(id: String) = longPreferencesKey("acc.$id.added")

    override suspend fun accounts(): List<CloudAccount> {
        val prefs = dataStore.data.first()
        val ids = prefs[idsKey].orEmpty().toList()
        return ids.mapNotNull { id ->
            val raw = prefs[rawKey(id)] ?: return@mapNotNull null
            CloudAccount(
                id = id,
                phone = prefs[phoneKey(id)].orEmpty(),
                rawToken = raw,
                expireAtMs = prefs[expireKey(id)] ?: 0L,
                displayName = prefs[nameKey(id)] ?: id,
                addedAtMs = prefs[addedKey(id)] ?: 0L
            )
        }
    }

    override suspend fun active(): CloudAccount? {
        val prefs = dataStore.data.first()
        val id = prefs[activeKey] ?: return null
        return accounts().firstOrNull { it.id == id }
    }

    override suspend fun setActive(id: String) {
        dataStore.edit { it[activeKey] = id }
    }

    override suspend fun addFromAuthorization(raw: String): CloudAccount {
        val account = Accounts.fromAuthorization(raw, nowMs())
        dataStore.edit { p ->
            val ids = p[idsKey].orEmpty().toMutableSet()
            ids.add(account.id)
            p[idsKey] = ids
            p[rawKey(account.id)] = account.rawToken
            p[phoneKey(account.id)] = account.phone
            p[expireKey(account.id)] = account.expireAtMs
            p[nameKey(account.id)] = account.displayName
            p[addedKey(account.id)] = account.addedAtMs
            p[activeKey] = account.id
        }
        return account
    }

    override suspend fun remove(id: String) {
        dataStore.edit { p ->
            p[idsKey] = p[idsKey].orEmpty().filterNot { it == id }.toSet()
            p.remove(rawKey(id)); p.remove(phoneKey(id)); p.remove(expireKey(id))
            p.remove(nameKey(id)); p.remove(addedKey(id))
            if (p[activeKey] == id) p.remove(activeKey)
        }
    }
}
