# 播放控制器 UI 接入说明（v2）

本文给后续 UI 代理。v2 在 v1 基础上新增：后台播放稳定性（锁/焦点/错误自愈/Service 保活）、会话持久化与冷启动续播、电池优化引导。播放页 UI 重做不属本期，本文只描述控制器契约。

## 1. 拿到控制器

```kotlin
val manager = (application as YunpanApp).locator.playbackManager   // PlaybackManager
```

所有播放控制都经它；UI 不直接碰 ExoPlayer / Service。

## 2. 观察播放态

```kotlin
lifecycleScope.launch {
  manager.state.collect { st: PlaybackState -> /* 刷新迷你条/播放页 */ }
}
```

`PlaybackState`（v1 已定义，不变）：
`current: CloudFile?`、`position: Int`、`size: Int`、`isPlaying`、`isBuffering`、`positionMs`、`durationMs`、`speed`、`playMode`、`timer: TimerUiState`、`albumTitle`、`error: String?`。

- `error != null` 时给中文 toast/横幅（错误已含中文，如“播放多次失败…直链可能已过期”）；`error` 在成功切歌后自动清空。

## 3. 命令方法（v1 已稳定）

| 按钮/动作 | 调用 |
|---|---|
| 点击某集播放 | `manager.playTracks(list, startIndex, albumTitle)` 或 `playScannedTracks(tracks, idx, title)` |
| 暂停/继续/切换 | `pause() / resume() / toggle()` |
| 上一首/下一首 | `prev() / next()` |
| 拖动进度 | `seekTo(ms)` |
| 倍速 | `setSpeed(0.75/1/1.25/1.5/2)` |
| 循环模式 | `cyclePlayMode()`（SEQUENTIAL→REPEAT_ONE→SHUFFLE 循环） |
| 停止释放 | `stop()` |
| 迷你条“是否在播该文件” | `manager.isCurrent(fileId)` |

**分享来源播放**：从分享页点集播放时，必须传来源：
```kotlin
manager.playTracks(list, idx, "分享名", origin = PlayOrigin.Share(linkId, passwd, shareName))
```
云端来源用默认 `PlayOrigin.Cloud` 即可。

## 4. 睡眠定时（v1 不变）

```kotlin
manager.setSleep(TimerConfig.Minutes(30))
manager.setSleep(TimerConfig.EndOfEpisode)
manager.setSleep(TimerConfig.Episodes(2))   // n in 1..99
manager.extendSleep()                       // +10 分钟 / 集数 +1
manager.cancelSleep()
```
观察 `state.timer: TimerUiState`（Idle / MinutesLeft(remainMs) / EndOfEpisodeActive / EpisodesLeft(remaining) / Fired）刷新睡眠 sheet。Fired 时控制器已自动暂停并落盘。

## 5. 冷启动续播（v2 新增）

- `manager.restoredSession: StateFlow<RestoredSessionInfo?>` —— 启动后异步填充。非空即有可恢复会话，可显示“继续收听：{albumTitle} / {firstTrackName}（已听 X 秒）”卡片。
- `manager.hasRestorableSession(): Boolean`
- `manager.resumeLast(autoplay: Boolean)`：
  - `autoplay = false`：prepare 到暂停态（推荐默认），迷你条出现、UI 显示“继续收听”；
  - `autoplay = true`：直接开始播放。
- `manager.autoResumeOnLaunch: Boolean`（默认 true）/ `manager.setAutoResume(enabled)`：设置页开关。开启后 App 启动可自动 `resumeLast(autoResumeOnLaunch)`；关闭则不自动播，只在用户点“继续收听”时 `resumeLast(true)`。

建议启动流程：MainActivity `onCreate` 观察 `restoredSession`，非空且 `autoResumeOnLaunch` 为 true 时调用 `resumeLast(false)`（先不自动出声，等用户点）；设置项可允许“启动直接播放”→ 调 `resumeLast(true)`。

## 6. 电池优化引导（v2 新增 util/BatteryOptimize.kt）

```kotlin
BatteryOptimize.isIgnoringBatteryOptimizations(context): Boolean
BatteryOptimize.requestIgnoreIntent(context)            // 申请忽略电池优化（系统弹窗）
BatteryOptimize.availableAutostartIntents(context)     // List<Pair<厂商说明, Intent>>，已 resolveActivity 守卫
```
设置页引导：若未忽略电池优化，弹“请允许后台运行”说明 → 启动 `requestIgnoreIntent`；再按厂商提供自启动跳转（小米/华为/OPPO/vivo/魅族/三星），无可跳转项给中文说明。所有 Intent 都有 `FLAG_ACTIVITY_NEW_TASK` 守卫。

## 7. v2 稳定性行为（UI 无需接线，仅知悉）

- 播放器唯一归属 `PlaybackService`（MediaSessionService）；Activity 销毁/划掉不释放播放器。
- 播放期间持有 PartialWakeLock + WifiLock，息屏/后台不中断；暂停/释放自动释放。
- 音频焦点：永久失焦自动暂停；短暂失焦暂停、回来后原在播则自动续播；有线耳机拔出自动暂停。
- 错误自愈：403/IO/缓冲中断按 1/2/4/8s 指数退避最多 4 次，重取直链后原位续播；超限转中文 `error` 态，不闪退。
- 会话每 ~4s 节流 + 暂停/切歌/停止/划后台自动落盘；不存会过期的 URL，冷启动按 fileId/contentID 懒解析。

## 8. 响度增益（人声增强，v2.1 新增）

档位：**关闭(0dB) / +3 / +6 / +10dB**（LoudnessEnhancer）。
- 状态：`state.volumeGainDb: Int`、`state.availableGainStages: List<Int>`（`[0,3,6,10]`）。
- 命令：`manager.setVolumeGain(db)`（自动 clamp）、`manager.cycleVolumeGain()`（OFF→+3→+6→+10→OFF）。
- 档位已持久化（SettingsStore），启动自动恢复。UI 可放“人声增强”按钮/分段控件，按 `availableGainStages` 渲染。

## 9. 封面（播放项 / 系统通知 / App 内）

- **系统通知/锁屏**：Engine 构建 MediaItem 时已据 `coverArtProvider.artworkFor(file)` 写入 `MediaMetadata.artworkUri`（Local→`file://`，Remote→`http(s)://`，null 不设）；HLS/Progressive 两种源都带 title=文件名、artist/albumTitle=专辑名。系统通知可能不带自定义头，Remote 封面 best-effort。
- **App 内播放页/迷你条**：UI 直接 `locator.coverArtProvider.artworkFor(file)` 取 `ArtworkRef` 交 Coil：
  - `Local(path)` → `File(path)`；
  - `Remote(url, headers)` → Coil 带头加载；
  - `null` → 主题占位图。

## 10. 听书分析（自动写，UI 只读）

- reporter 在与 listen_day 同节奏自动写 `album_day`：播放时长增量、切歌/自然结束 episode+1、95% 本集只计一次 completed+1；暂停不累加。
- UI 不要写分析，只读 `locator.analyticsRepository`（overview/dailySeries/topAlbums/completion）。

## 11. 已知未接通点（真机）

- **分享冷启动 HLS 续播**：当前冷启动重建 `CloudFile(directUrl=null)` 后走 `resolver.resolve`，而 `DefaultPlaybackResolver` 对 SHARE 用 `file.directUrl`（冷启动为 null）。云端 CLOUD 来源续播正常；分享来源冷启动续播需后续在 resolver 增加“按 linkId+contentID 重开分享取 presentURL”的分支后才能真正接通（测试用 fake resolver 覆盖了逻辑）。
