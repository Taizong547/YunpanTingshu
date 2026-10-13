# 播放引擎 UI 接入说明（UI 代理必读）

本模块只交付“可被 ViewModel/Fragment 观察的播放控制器”，不做界面。所有入口：

```kotlin
val pm = (requireContext().applicationContext as YunpanApp).locator.playbackManager
```

## 1. 观察播放态

```kotlin
lifecycleScope.launch {
  repeatOnLifecycle(Lifecycle.State.STARTED) {
    pm.state.collect { st: PlaybackState -> render(st) }
  }
}
```

`PlaybackState` 字段（`player/PlaybackState.kt`）：

| 字段 | 含义 |
|---|---|
| `current: CloudFile?` | 当前曲目（null=无播放） |
| `position: Int` / `size: Int` | 队列下标 / 总数（迷你条可用） |
| `isPlaying` / `isBuffering` | 播放中 / 缓冲中 |
| `positionMs` / `durationMs` | 当前位置 / 总时长（ms；未知为 0） |
| `speed: Float` | 当前倍速 |
| `playMode: PlayMode` | SEQUENTIAL / REPEAT_ONE / SHUFFLE |
| `timer: TimerUiState` | 睡眠定时状态（见 §4） |
| `albumTitle: String` | 当前专辑名 |
| `error: String?` | 中文错误（如直链过期），非空时弹 Snackbar |

~1s 自动刷新 positionMs（仅播放态累计）。seekbar 拖动时用 `pm.seekTo(ms)`。

## 2. 启动播放

由扫描结果构造。Browse/递归扫描给出 `List<ScannedTrack>`：

```kotlin
// tracks: List<ScannedTrack>；startIndex = 用户点击项在 tracks 中的下标
pm.playScannedTracks(tracks, startIndex = clickedIndex, albumTitle = "老梁评书")
```

或直接用 `List<CloudFile>`：`pm.playTracks(files, startIndex, albumTitle)`。
- 首次调用会拉起前台服务；之后迷你条常驻。
- 历史页“续播”：直接 `playTracks(listOf(historyFile), 0, albumTitle)`；想带同专辑队列就把同专辑音频列表传进来。
- 点击正在播的同一集可调用 `pm.isCurrent(file.id)` 判断是否高亮/切回播放页。

## 3. 控制方法（按钮映射）

| UI | 调用 |
|---|---|
| 播放/暂停 | `pm.toggle()`（或 `pause()`/`resume()`） |
| 上一首 | `pm.prev()` |
| 下一首 | `pm.next()` |
| 拖动进度条 | `pm.seekTo(ms)` |
| 倍速 0.75/1/1.25/1.5/2 | `pm.setSpeed(1.5f)` |
| 循环模式按钮（三态） | `pm.cyclePlayMode()`（图标随 `state.playMode` 切） |
| 停止 | `pm.stop()` |

通知栏 / 锁屏的 播放·暂停·上一首·下一首 已由 MediaSession 自动联动，无需额外代码。
POST_NOTIFICATIONS 运行时申请（API33+）由 UI 层做；服务已建好通知渠道 `yunpan_playback`。

## 4. 睡眠定时器（SleepTimerSheet）

选项到 `TimerConfig` 映射：

| Sheet 选项 | 调用 |
|---|---|
| 15/30/45/60/90 分钟 | `pm.setSleep(TimerConfig.Minutes(30))` |
| 本集结束后停止 | `pm.setSleep(TimerConfig.EndOfEpisode)` |
| 再播完 1/2/3/5/10 集 | `pm.setSleep(TimerConfig.Episodes(2))` |

倒计时 chip 显示（读 `state.timer`）：

```kotlin
when (val t = st.timer) {
  TimerUiState.Idle -> /* 不显示 chip */
  is TimerUiState.MinutesLeft -> show("剩余 ${fmt(t.remainMs)}")
  TimerUiState.EndOfEpisodeActive -> show("本集结束后停止")
  is TimerUiState.EpisodesLeft -> show("剩 ${t.remaining} 集")
  TimerUiState.Fired -> /* 引擎已 pause+释放服务，chip 自动消失 */
}
```

chip 上的“取消 / 续期”：`pm.cancelSleep()`、`pm.extendSleep()`
（Minutes +10 分；Episodes +1；EndOfEpisode 续期无操作）。

到点后引擎自动 `pause()` 并 `stopSelf()`（释放前台与通知），`state` 回到 Idle。

## 5. 错误 / 过期提示

- `state.error != null`：弹 Snackbar，文案已含“直链可能已过期，请重新进入或刷新”。
- CLOUD139 直链约 8h 会话时效；切歌时实时 `PlaybackResolver.resolve`，过期会走到 error 分支，不崩。
- 未登录账号时 resolve 抛中文异常（“未登录 139 云盘账号…”），同样进 error。

## 6. 已做 / 未做说明

- 已做：纯逻辑三模式队列、分钟/本集/N集睡眠定时、历史 upsert + 听书时长日累计 + 95% 置完成、断点续播、HLS/渐进式按 `isHls` 自动选源、个人云盘 OkHttp 带头(UA/Referer/Origin)、前台服务+通知。
- 架构说明：本 app 单进程，`PlaybackManager` 直接持有进程内 `PlaybackEngine`（即 MediaSession 背后的同一 ExoPlayer），未额外走 MediaController IPC——等价且更稳，UI 只面对 `playbackManager.state`。
- 未在真机/模拟器验证：后台播放、通知栏按钮、锁屏控件、真实 m3u8 直播（Robolectric 跑完整 HLS 过重；仅对“按 isHls 选 HlsMediaSource/ProgressiveMediaSource”做了工厂级单测）。
