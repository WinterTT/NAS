# MyUPNP

从零手写实现的 **DLNA / UPnP 控制点 Android App**（Kotlin，自研协议栈，不依赖第三方 UPnP 库）：
把家里的 DLNA 音箱 / 电视 / PC 媒体服务器统一成一个"遥控器 + 媒体库"。

## 想快速接手 / 换电脑继续开发？

👉 **先看 [`AGENTS.md`](AGENTS.md)** —— 项目上下文、工作约定、代码地图、进度、已知问题、下一步候选。
（AI 助手会自动加载这个文件；新电脑 clone 下来就自带上下文。）

## 文档

| 文件 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 项目上下文与交接（**先看这份**） |
| `docs/01~06` | UPnP 协议学习笔记：SSDP / SCPD+SOAP / GENA / DLNA 播放 / 曲库浏览 / 手写设备端 |
| `docs/07-代码架构.md` | 分层、线程模型、文件职责、扩展指南、收费口子、已知问题 |
| `docs/08-换电脑继续开发.md` | 换机清单：代码搬迁、JDK/SDK/Gradle、构建命令、数据位置 |

## 构建

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
$env:ANDROID_USER_HOME="$PWD\.android-home"
.\gradlew.bat --no-daemon :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```
