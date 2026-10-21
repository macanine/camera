# Camera · 相机

[![CI](https://github.com/macanine/camera/actions/workflows/ci.yml/badge.svg)](https://github.com/macanine/camera/actions/workflows/ci.yml)
[![Release](https://github.com/macanine/camera/actions/workflows/release.yml/badge.svg)](https://github.com/macanine/camera/actions/workflows/release.yml)

基于 Kotlin、Compose、CameraX 的 Android 相机，支持照片处理、流式内容、离线公式渲染和本地记录。

## 安装与开始使用

1. 从 [最新正式版](https://github.com/macanine/camera/releases/latest) 下载 `camera-v*.apk`，按 Android 提示允许安装。
2. 打开设置，添加支持图片输入的模型，填写服务商的 HTTPS 接口地址、模型 ID 和 API 密钥。可以先运行视觉测试，再保存设置。
3. 允许相机权限后拍摄。照片会保存在本机；配置可用连接后，排队的照片会自动开始处理。

相册入口是快门右侧的隐藏双击区域；TalkBack 可使用“打开相册”操作。相册支持记录切换、复制内容、阅读字号、查看原图与删除确认。没有连接时仍能拍摄并保留照片。

服务商与 API 密钥由使用者自行提供。密钥经 Android Keystore 加密保存在本机；请求图片会发送给所配置的服务商。仓库和 APK 不包含共享密钥。

## 自动更新

正式版在应用进入前台时检查 GitHub 最新正式版，成功检查间隔为六小时；设置提供手动检查及关闭自动检查的开关。更新包通过大小、SHA-256、包名、版本和签名校验后交给 Android 系统安装器确认。RC 需要手动安装。

## 本地开发

需要 JDK 17 或 21、Android SDK platform 36 和 build tools 36.0.0。通过 `ANDROID_HOME` 或被忽略的 `local.properties` 配置 SDK。

```sh
./gradlew --no-daemon --dependency-verification strict :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -v
# 接入模拟器或设备后运行真实 UI 测试
./gradlew --no-daemon --dependency-verification strict :app:connectedDebugAndroidTest
```

调试版使用 `com.nic.photoanswer.debug`，可与正式版并存。无正式签名时，可使用 `:app:assembleRelease -PsmokeRelease=true` 验证优化构建；用于分发的版本必须配置发行签名。Lint 不允许警告，依赖使用锁文件和校验和。

## GitHub Actions 发版

PR 和 `main` 提交自动运行测试、Lint、Release 编译、发布元数据校验和 API 26/36 设备测试。

日常发版只需：运行 **Prepare release** 选择版本增量并填写说明 → 检查并合并生成的 PR → 在 `main` 运行 **Publish current version**。工作流会创建 tag、重复验证、签名构建、上传草稿、回下载校验，最后发布 APK 和自动更新清单。

需要配置的仅有四个发行签名 Secrets，详见 [发版操作指南](docs/RELEASE.md)。不需要额外的个人访问 token。[架构文档](docs/ARCHITECTURE.md) 记录代码边界、数据存储及后台行为。
