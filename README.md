# HavenCast

从零手写实现的 **DLNA / UPnP 控制点 Android App**（Kotlin，自研协议栈，不依赖第三方 UPnP 库）：
把家里的 DLNA 音箱 / 电视 / PC 媒体服务器统一成一个"遥控器 + 媒体库"。

## 想快速接手 / 换电脑继续开发？

👉 **第一步看 [`docs/12-待办与发布清单.md`](docs/12-待办与发布清单.md)** —— 当前进度快照、关键 ID、
还没做完的事（含只有用户能在 AdMob/Play 后台做的操作）。
👉 **第二步看 [`AGENTS.md`](AGENTS.md)** —— 项目上下文、工作约定与踩坑纪律、代码地图、已完成功能、已知问题。
（AI 助手会自动加载 `AGENTS.md`；新电脑 clone 下来就自带上下文。）

## 文档

| 文件 | 内容 |
|---|---|
| [`docs/12-待办与发布清单.md`](docs/12-待办与发布清单.md) | **接手先读**：状态与关键 ID、发布前必做、代码侧待办、换机开工步骤 |
| [`AGENTS.md`](AGENTS.md) | 项目上下文与交接：约定、纪律、代码地图、进度、已知问题 |
| `docs/01~06` | UPnP 协议学习笔记：SSDP / SCPD+SOAP / GENA / DLNA 播放 / 曲库浏览 / 手写设备端 |
| `docs/07-代码架构.md` | 分层、线程模型、文件职责、扩展指南、收费口子、已知问题 |
| `docs/08-换电脑继续开发.md` | 换机清单：代码搬迁、JDK/SDK/Gradle、构建命令、数据位置 |
| [`docs/09-Release发布.md`](docs/09-Release发布.md) | Release 双密钥签名、AAB/APK 构建、验证与 Google Play 内部测试 |
| [`docs/10-隐私政策.md`](docs/10-隐私政策.md) | 隐私政策正文（App 内 `raw/privacy_policy.txt` 是同一份内容） |
| [`docs/11-广告接入.md`](docs/11-广告接入.md) | AdMob 接入：产品原则、debug/release 双套 ID、上线前清单、合规与已知坑 |

## 构建

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-home"   # 只设这个
.\gradlew.bat --no-daemon :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

> ⚠️ 不要设 `ANDROID_USER_HOME` 到工程内：AGP 会改用它下面的 `debug.keystore` 签名，
> 与默认 `%USERPROFILE%\.android\debug.keystore` 不一致，导致 `adb install -r` 直接失败。
> 原因与解决办法见 `AGENTS.md` 的踩坑纪律。
