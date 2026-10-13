# 云盘听书 (YunpanTingshu)

> 139 移动云盘听书客户端 - 专为听书体验优化的Android应用

## 📖 项目介绍

这是一个基于139移动云盘的音频播放应用，把云盘里的音频文件变成随身听书库。  
因为移动云盘自带的播放器没有定时关闭功能且过于花哨（个人观点），本项目完全由豆包工作完成。  
项目目前仍处于毛胚阶段，仅仅满足基本功能，希望能抛砖引玉，一起创造更有意思的项目  

- 🔐 手机号验证码登录，Token自动过期检测
- 📁 浏览云盘文件夹，一键播放整个目录
- 🎵 分享链接免登录直接播放
- 📚 书架收藏、歌单管理、播放历史
- ⏱️ 睡眠定时、智能跳过片头片尾、倍速播放
- 🌙 深色模式、锁屏通知、后台播放

## ✨ 功能特性

| 分类 | 功能 |
|---|---|
| **播放** | ExoPlayer内核、倍速、睡眠定时、智能跳过片头片尾、播放队列排序 |
| **云盘** | 139个人云盘登录、分享链接免登、文件夹一键播放 |
| **收藏** | 书架收藏、自定义歌单、自定义封面 |
| **历史** | 播放记录、按时间范围清除、一键清空 |
| **UI** | 深色模式、Material Design、圆角卡片 |

## 🛠 技术栈

- **语言**：Kotlin
- **UI**：XML ViewBinding + Material Components
- **架构**：MVVM + 单Activity Navigation
- **网络**：OkHttp
- **播放**：Media3 / ExoPlayer
- **数据库**：Room
- **配置**：DataStore Preferences
- **图片**：Coil

## 🚀 快速开始

### 环境要求
- Android Studio Hedgehog 或更新版本
- JDK 17
- minSdk 24 (Android 7.0)

### 运行
1. Clone 项目
2. 用 Android Studio 打开项目根目录
3. 等待 Gradle 同步完成
4. 连接手机，点 Run

### 打包APK
```bash
# Debug版本
./gradlew assembleDebug

# Release版本
./gradlew assembleRelease
```

## 📂 项目结构

```
app/src/main/java/com/yunpan/tingshu/
├── cloud139/          # 139云盘API对接
│   ├── share/         # 分享链接模块
│   └── Cloud139Api.kt  # 核心API请求
├── player/            # 播放模块
│   ├── PlaybackService.kt  # 前台服务
│   ├── PlaybackEngine.kt   # ExoPlayer封装
│   └── PlaybackManager.kt   # 对外接口
├── data/              # 数据层
│   ├── db/            # Room数据库
│   ├── cover/         # 封面管理
│   └── AccountStore.kt  # 账号管理
├── ui/               # UI层
│   ├── browse/        # 云盘浏览
│   ├── player/       # 播放器
│   ├── bookshelf/    # 书架
│   ├── history/      # 历史
│   └── me/           # 我的
└── YunpanApp.kt      # Application入口
```

## ⚠️ 免责声明

- 本项目仅供学习交流使用，请勿用于商业用途
- 139云盘接口随时可能变更，不保证长期可用
- 请使用自己的账号访问自己的云盘文件
- 未破解任何加密、未绕过权限验证

## 📄 许可证

[MIT License](LICENSE)
