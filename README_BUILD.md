# 云盘听书（YunpanTingshu）骨架构建说明

本工程为原生 Android 骨架（Kotlin + XML Views + Material3，非网页套壳），仅含主题/导航/占位页，
未实现网络与播放业务（由后续代理填充）。

## 环境一次性准备

工具链全部位于 `/home/user`，无需 sudo/apt：

- JDK17（Temurin）：`/home/user/jdk17`
- Android SDK：`/home/user/android-sdk`
  - 已装：`platform-tools`、`platforms;android-34`、`build-tools;34.0.0`
  - cmdline-tools 位于 `/home/user/android-sdk/cmdline-tools/latest`

## 每次构建前

```bash
source /home/user/android-env.sh
```

该脚本会导出 `JAVA_HOME=/home/user/jdk17`、`ANDROID_HOME=/home/user/android-sdk`，
并把 `$JAVA_HOME/bin`、`$ANDROID_HOME/cmdline-tools/latest/bin`、`$ANDROID_HOME/platform-tools`
加入 `PATH`。

## 构建命令

```bash
cd /home/user/Doubao/chats/38445351782891266/YunpanTingshu
source /home/user/android-env.sh
./gradlew --no-daemon clean assembleDebug
```

产出 APK：`app/build/outputs/apk/debug/app-debug.apk`

Lint（error 需为 0）：

```bash
./gradlew --no-daemon lintDebug
```

> 4G 内存机器：所有 gradle 命令一律加 `--no-daemon`；`gradle.properties` 已固定
> `-Xmx2048m`、`workers.max=1`、`parallel=false`、`caching=true`，请勿同时跑多个构建。

## 关键坐标

- applicationId：`com.yunpan.tingshu`，versionName 1.0 / versionCode 1
- AGP 8.5.2 / Gradle wrapper 8.9 / Kotlin 1.9.24 / KSP 1.9.24-1.0.20
- compileSdk/targetSdk 34，minSdk 24，JVM 17，viewBinding 开启，单 app module，无 Compose
