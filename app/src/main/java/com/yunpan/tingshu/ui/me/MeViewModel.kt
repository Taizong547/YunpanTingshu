package com.yunpan.tingshu.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.AccountStore
import com.yunpan.tingshu.data.SettingsStore
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.ui.browse.ShareSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 我的页 UI 状态。 */
data class MeUiState(
    val accounts: List<CloudAccount> = emptyList(),
    val activeId: String? = null,
    val busy: Boolean = false,
    /** 最近一次保存并校验成功的账号（用于展示手机号+到期时间）。 */
    val lastSaved: CloudAccount? = null,
    /** 一次性事件文案（Snackbar/Toast）。 */
    val message: String? = null,
    val defaultDepth: ScanDepth = ScanDepth.CURRENT,
    /** 打开分享成功后由 Fragment 导航到浏览页。 */
    val shareOpened: ShareSession.Pending? = null,
    /** 该链接需要提取码时，携带原始链接让 Fragment 弹输入框；null=不需要。 */
    val needPasscodeLink: String? = null
)

class MeViewModel(app: Application) : AndroidViewModel(app) {

    private val locator = (app as YunpanApp).locator
    private val accountStore: AccountStore = locator.accountStore
    private val shareRepository: ShareRepository = locator.shareRepository
    private val settings: SettingsStore = locator.settingsStore

    private val _state = MutableStateFlow(MeUiState())
    val state: StateFlow<MeUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settings.defaultDepth.collect { d -> _state.value = _state.value.copy(defaultDepth = d) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val accounts = accountStore.accounts()
            val active = accountStore.active()
            _state.value = _state.value.copy(accounts = accounts, activeId = active?.id)
        }
    }

    /** 保存并校验 Authorization；成功更新 lastSaved，失败把中文异常放进 message。 */
    fun addFromAuthorization(raw: String) {
        if (raw.isBlank()) {
            _state.value = _state.value.copy(message = "请先粘贴 Authorization")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = runCatching { accountStore.addFromAuthorization(raw) }
            result.onSuccess { acc ->
                _state.value = _state.value.copy(
                    busy = false,
                    lastSaved = acc,
                    accounts = accountStore.accounts(),
                    activeId = acc.id,
                    message = "已保存：${acc.phone.ifEmpty { acc.displayName }}"
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    message = e.message ?: "Authorization 校验失败"
                )
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            accountStore.setActive(id)
            _state.value = _state.value.copy(accounts = accountStore.accounts(), activeId = id)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            accountStore.remove(id)
            // 退出登录/换号清理：路由 host 缓存，避免残留旧账号 host 导致点文件播不了
            runCatching { locator.cloud139Repository.clearHostCache() }
            _state.value = _state.value.copy(
                accounts = accountStore.accounts(),
                activeId = accountStore.active()?.id
            )
        }
    }

    fun setDefaultDepth(depth: ScanDepth) {
        viewModelScope.launch { settings.setDefaultDepth(depth) }
    }

    /** 解析并打开分享链接；成功后 offer 到 ShareSession 并触发 shareOpened 导航。 */
    fun openShare(link: String, passwd: String) {
        val cleaned = com.yunpan.tingshu.cloud139.share.ShareLinkExtractor.extract(link) ?: link.trim()
        if (cleaned.isBlank()) {
            _state.value = _state.value.copy(message = "请先粘贴分享链接")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, needPasscodeLink = null)
            val result = runCatching {
                val info = shareRepository.openShare(cleaned, passwd.trim())
                // 探测：列出根目录。若该分享需要提取码且没给对，会抛 need-passcode 错误。
                runCatching {
                    shareRepository.listShareFolder(info.linkId, "root", passwd.trim(), 1, 1)
                }.getOrThrow()
                val title = info.name.ifEmpty { "分享" }
                ShareSession.Pending(info.linkId, passwd.trim(), title)
            }
            result.onSuccess { pending ->
                locator.shareSession.offer(pending)
                runCatching {
                    settings.upsertShareSource(
                        com.yunpan.tingshu.data.SavedShare(
                            linkId = pending.linkId,
                            name = pending.title,
                            passwd = pending.passwd
                        )
                    )
                }
                _state.value = _state.value.copy(busy = false, shareOpened = pending)
            }.onFailure { e ->
                // 需要提取码：弹出输入框让用户补
                val needPwd = e is com.yunpan.tingshu.cloud139.share.ShareLinkInvalidException &&
                    e.errorCode in setOf("200000100", "200000101", "200000400", "200000401")
                if (needPwd) {
                    _state.value = _state.value.copy(
                        busy = false,
                        needPasscodeLink = cleaned,
                        message = "该分享需要提取码，请输入后重试"
                    )
                } else {
                    _state.value = _state.value.copy(
                        busy = false,
                        message = e.message ?: "打开分享失败，请检查链接是否有效"
                    )
                }
            }
        }
    }

    fun onNeedPasscodeConsumed() {
        _state.value = _state.value.copy(needPasscodeLink = null)
    }

    /** Fragment 消费完一次性事件后调用，避免重复展示/导航。 */
    fun onMessageConsumed() {
        _state.value = _state.value.copy(message = null)
    }

    fun onShareOpenedConsumed() {
        _state.value = _state.value.copy(shareOpened = null)
    }

    /** 仅供测试：当前默认深度（first）。 */
    suspend fun currentDefaultDepth(): ScanDepth = settings.defaultDepth.first()
}
