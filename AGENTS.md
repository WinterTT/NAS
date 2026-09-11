# MyUPNP 项目上下文（交给助手/新同事看这份）

> 这个文件会被 AI 助手**自动加载**到每个新会话里。新电脑上 clone 工程后即可开工，
> 不需要额外"喂上下文"。详细文档见 `docs/`。

---

## 1. 这个项目是什么

从零手写实现的 **DLNA / UPnP 控制点 Android App**（Kotlin，自研协议栈，不依赖第三方 UPnP 库），
用于把家里的 DLNA 音箱 / 电视 / PC 媒体服务器统一成一个"遥控器 + 媒体库"。
包名 `com.example.myupnp`（**发布前必须改**）。中文界面，面向普通用户做产品化。

定位：**精简易用**；广告将来接入但不能打断播放；后续收费方向是"帮用户管理服务器"（建目录/清理/推荐）。

---

## 2. 工作方式约定（请遵守）

- **语言**：与用户交流、UI 文案、代码注释全用**中文**。
- **不加单元测试**（用户明确要求）：验证方式是 **编译 + 真机手动验证**。
  仓库里既有的单测可以跑（`testDebugUnitTest`）用来回归，但不要新增。
- **每个功能都要能编过**：改完必须 `assembleDebug` 成功，并让用户在手机上验证后再继续下一个。
- **代码分层纪律**：
  - 协议包（`ssdp/ soap/ gena/ dlna/ device/`）不依赖 Activity；
  - 状态类（`DeviceRegistry / SubscriptionManager / NowPlayingSession / PlaybackQueue / LocalPlayer`）
    通过 Listener 通知 UI，自己不碰 View；
  - Activity **只做 UI 编排**，不创建线程池（线程池都在 `MainViewModel`）；
  - 新状态进 `UiState`（StateFlow），Activity 用 `collect` 渲染。
- **提交习惯**：每个可编译的功能/修复单独提交，信息用中文 + 前缀（`feat(x):` / `fix(x):` / `polish(x):` / `docs:`）。
  用户会自己 `git push`（沙箱常连不上 GitHub）。
- **能力探测要诚实**：拿不到设备声明的信息就标"未知/推测"，不要假装知道。

### 已知的"踩坑纪律"（务必遵守）

- **改数据库 schema 必须同时 +1 `MediaIndexStore.DB_VERSION`**，否则 `onUpgrade` 不执行 → 线上崩
  （历史上就因为这个崩过一次：`no such column: top_id`）。
- **MainViewModel 里不要在 `init {}`/属性初始化里触发会回调 UI 状态的操作**：
  `_deviceRows` / `_uiState` 声明在后面，构造期回调会 NPE（也有 `flowsReady` 防御）。
  需要"启动时恢复"的动作改为 Activity 就绪后显式调用（如 `vm.restoreCachedDevicesOnce()`）。
- **属性初始化里引用自身**（如监听器里用 `localPlayer`）→ 用 `lateinit var` + `init` 赋值。
- **UI 里不要用 unicode 符号当图标**（▶ ⏹ − ＋ 在用户机型上不渲染）→ 一律用矢量 drawable。
- **不要依赖主题色给的按钮字色**：显式设 `textColor` / 背景 drawable。
- **DLNA 服务器返回的 URL 常未转义**（含空格/中文）→ 本机播放前做百分号编码（见 `LocalPlayer.encodeUrl`）。

---

## 3. 构建与验证命令

```powershell
$env:GRADLE_USER_HOME='<工程根>\.gradle-home'
$env:ANDROID_USER_HOME='<工程根>\.android-home'
.\gradlew.bat --no-daemon :app:assembleDebug        # 产物 app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat --no-daemon :app:testDebugUnitTest    # 既有单测回归（不新增）
```

- 真机：`adb install -r app\build\outputs\apk\debug\app-debug.apk`，日志 `adb logcat -s MyUPNP`
- 环境要求（JDK 17/21、Android SDK platform 37、`local.properties` 的 `sdk.dir`）见 `docs/08-换电脑继续开发.md`

---

## 4. 代码地图（速查）

```
app/src/main/java/com/example/myupnp/
├── MainActivity.kt          UI 编排：三个 Tab（媒体库/播放/设置）+ 搜索页 + 服务器分类页 + 各种对话框
├── MainViewModel.kt         唯一编排中枢：线程池/Handler、UiState、扫描子系统、队列记账、索引进度回调
├── DeviceRegistry.kt        设备注册表：增删/心跳清理/描述拉取/快照恢复
├── SubscriptionManager.kt   GENA 订阅生命周期
├── NowPlayingSession.kt     DLNA 播放会话：进度/音量/播完判定（事件 + 兜底轮询）
├── LocalPlayer.kt           本机音频播放（MediaPlayer，URL 编码 + 错误诊断）
├── PlaybackQueue.kt         队列：待播 pending / 当前 current / 已播 history（纯逻辑）
├── PlayHistory.kt           最近播放（按网络，JSON 持久化）
├── DeviceBookmarks.kt       设备收藏 / 别名（按 UDN 稳定键）
├── DeviceCache.kt           设备列表快照（按网络；切后台/杀进程后列表不空）
├── MediaIndexStore.kt       曲库索引（SQLite：分类/去重/搜索/分专辑歌手查询）
├── LibraryIndexer.kt        后台 BFS 建索引（可停/可续扫，统计跳过重复）
├── LocalFileServer.kt       手机当媒体源：HTTP 文件服务（支持 Range）
├── ImagePreviewActivity.kt  图片预览（双指缩放/拖动/轻点关闭）
├── NetworkScope.kt          网络作用域（按网段 net:192.168.1 隔离数据）
├── core/                    FeatureGate / FeatureId / EverythingFreeGate（收费口子，现全免费）
├── dlna/  ssdp/  soap/  gena/  device/  model/    协议层（无 UI 依赖）
```

关键设计：
- **队列三段式**：`pending`（队首=下一首）+ `current` + `history`（上一首）；先预览、推送成功才 commit。
- **播完判定**：GENA 事件优先；事件不新鲜（>15s）时用 GetTransportInfo/GetPositionInfo/GetVolume 兜底轮询。
- **能力探测**：ConnectionManager 的 `GetProtocolInfo` → `SinkProtocolInfo` 判定音乐/视频/图片；
  拿不到就按设备类型"推测"（标 `?`）或标 `❔`，不用于拦截。
- **索引去重三重键**：归一化 URL / **文件大小+时长** / 标题+歌手+专辑（DLNA 同一文件常有多视图多 URL）。
- **数据按网络隔离**：设备列表、队列、历史、上次会话、设备快照各按网段存一份；收藏/别名跟设备走。

---

## 5. 已完成（进度）

- 协议：SSDP 发现 → 描述 → SCPD → SOAP 控制 → GENA 订阅/事件（`docs/01~04`）+ 手写 Python 虚拟音箱（`docs/06`）
- 播放：曲库浏览（常驻对话框、可连续排队）、推送到设备、进度/音量实时、Seek、记忆上次播放
- 队列：排队 / 播完自动连播 / 删除·上移下移·插队 / 持久化（按网络）
- 历史：最近播放（按网络）一键重播
- 设备管理：收藏置顶、别名重命名、能力探测与标注、记住上次推送的设备
- 索引与搜索：SQLite 本地索引（可停/续扫/去重/分类）→ 音乐/视频/图片分类 → 专辑/歌手/歌曲 →
  全部播放·全部入队·单个入队（行尾 ⋮ 菜单）、服务器 `Search` 兜底、跨服务器去重
- 本机媒体：音乐用 App 播放页本机播放（含本机连播）、视频交系统播放器（VLC 等）、图片预览页
- UI：底部三 Tab（媒体库/播放/设置）、播放大卡（深色沉浸）、深浅色主题、首启三步引导
- 本地文件推送：手机起 HTTP 服务（支持 Range）推给设备

## 6. 已知问题

1. **部分设备 GENA 事件不可靠**：进度/音量/状态靠兜底轮询校准（已缓解）。
2. **活动设备自动订阅不生效**（历史问题）：见 `docs/07-代码架构.md` 已知问题 #1，暂时搁置。
3. **自动连播触发依赖 GENA 或"有时长"**：设备既不推事件又不报时长的轨道可能不自动下一首。
4. 迁移后（DB schema 升级）会**清空曲库索引**，需要用户重新建一次索引。

## 7. 下一步候选（按讨论顺序）

1. **本机播放格式兜底**：接入 **AndroidX Media3 / ExoPlayer**（Apache-2.0，体积可控），覆盖 MKV/OPUS/FLAC；
   仅当需要 WMA/APE/DSD 才考虑 libVLC/FFmpeg（体积与许可成本高）。
2. 播放模式：随机 / 单曲循环 / 列表循环。
3. 媒体库页内嵌浏览（替代弹窗式浏览）。
4. **可发布化**：改包名（`com.example.myupnp` 必须改）、定应用名、真图标、关于/隐私政策/开源许可页、
   Release 签名 + AAB、商店素材（截图/描述/Data safety）、`NO_ADS` 与广告接入时机。
5. 服务器助手（收费方向）：基于索引做重复清理、目录整理、推荐。

## 8. 相关文档

| 文件 | 内容 |
|---|---|
| `docs/01~06` | UPnP 各协议的学习笔记（SSDP/SCPD/SOAP/GENA/DLNA/曲库/设备端） |
| `docs/07-代码架构.md` | 分层、线程模型、文件职责、扩展指南、收费口子、已知问题 |
| `docs/08-换电脑继续开发.md` | 换机清单：代码搬迁、JDK/SDK/Gradle、构建、数据位置、DSH 迁移 |
