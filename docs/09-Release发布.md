# HavenCast Release 发布

本文覆盖 Google Play AAB 与本地/其他商店 APK。签名密钥和密码均不得进入仓库，也不得放在工作区内。

## 1. 双密钥策略

HavenCast 使用两把不同的 PKCS12 密钥：

- `havencast-app-signing.p12`：永久应用签名密钥。用于本地/其他商店 APK，并在首次配置 Google Play App Signing 时提供给 Google。它决定 APK 能否相互覆盖升级，必须永久离线备份。
- `havencast-upload.p12`：Google Play 上传密钥。只用于签署上传的 AAB；丢失后可以向 Google 申请重置，但仍应妥善备份。

当前默认保存在工作区外：

```text
C:\Users\Administrator\HavenCast-keys\havencast-app-signing.p12
C:\Users\Administrator\HavenCast-keys\havencast-upload.p12
```

不要把密钥或密码发送到聊天、邮件、网盘公开链接或 Git 仓库。建议将两份密钥及对应密码分别保存到加密离线介质和密码管理器。

## 2. Release 签名环境变量

Gradle 只读取以下通用环境变量，具体使用哪把密钥由构建脚本决定：

- `HAVENCAST_RELEASE_STORE_FILE`
- `HAVENCAST_RELEASE_STORE_PASSWORD`
- `HAVENCAST_RELEASE_KEY_ALIAS`
- `HAVENCAST_RELEASE_KEY_PASSWORD`

Debug 和 IDE Sync 不要求这些变量。Release 签名任务在变量缺失、密钥不存在或密钥位于仓库内时会明确失败，不会退回为未签名产物。

通常无需手工设置这些变量，应使用下一节的安全脚本。脚本通过 `Read-Host -AsSecureString` 获取密码，只在当前子进程中临时设置变量，并在结束后清除。

## 3. 构建与验证

### 3.1 Google Play AAB

```powershell
.\scripts\build-release.ps1
```

脚本使用：

```text
密钥：havencast-upload.p12
别名：havencast-upload
任务：:app:bundleRelease
产物：app\build\outputs\bundle\release\app-release.aab
```

脚本会执行 `jarsigner -verify -verbose -certs` 和 `keytool -printcert -jarfile`。如已安装 `bundletool`，还应执行：

```powershell
bundletool validate --bundle=app\build\outputs\bundle\release\app-release.aab
```

AAB 不能直接通过 `adb install` 安装；应上传 Google Play 内部测试轨道，或用 bundletool 生成 `.apks` 后安装。

### 3.2 本地/其他商店 APK

```powershell
.\scripts\build-direct-release.ps1
```

脚本使用：

```text
密钥：havencast-app-signing.p12
别名：havencast-app-signing
任务：:app:assembleRelease
产物：app\build\outputs\apk\release\app-release.apk
```

脚本会通过 Android SDK 的 `apksigner verify --verbose --print-certs` 验证 APK。使用同一 app signing key 签名的本地 APK、其他商店 APK 和 Google Play 分发 APK 才能互相覆盖升级。

## 4. R8 与版本归档

Release 已通过 AGP 9.4 的 `optimization { enable = true }` 启用 R8 代码优化和资源压缩。当前业务没有反射或 JNI，Media3 自带 consumer rules，因此不添加宽泛的包级 keep 规则；若真机发现具体压缩问题，再补充最小范围规则。

每次上传前必须提升 `app/build.gradle.kts` 中的 `versionCode`。每个上传到 Google Play 任一轨道的 AAB 都必须使用从未用过且更大的值。`versionName` 使用面向用户的语义版本号，例如 `1.0.0`。

每次发布归档：

- AAB 或 APK 原始产物
- `app\build\outputs\mapping\release\mapping.txt`
- 上传证书及应用签名证书的 SHA-256 指纹
- 对应 Git 提交和版本说明

## 5. Google Play App Signing

首次为 `com.havencast.remote` 创建 Google Play 应用时启用 Play App Signing，并选择提供已有的应用签名密钥。按照 Play Console 当时显示的“更改应用签名密钥 / 导出并上传”流程，使用它提供的 PEPK 工具安全上传 `havencast-app-signing.p12`；不要选择由 Google 新生成一把不同密钥，否则 Play 安装版将不能与本地 APK 相互升级。

随后将 `havencast-upload.p12` 对应证书登记为 upload key。以后上传 AAB 只使用 upload key，本地和其他商店 APK 只使用 app signing key。

发布前先进入 **测试 > 内部测试**：

1. 上传并验证 `app-release.aab`。
2. 添加测试人员并通过 Google Play 安装。
3. 验证启动、权限、设备发现、曲库浏览、本机播放、DLNA 推送、队列恢复和图片/视频跳转。
4. 再安装同 `versionCode` 规则下更高版本的本地签名 APK，验证签名一致且可以覆盖升级。
