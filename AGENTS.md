# HavenCast 项目上下文（交给助手/新同事看这份）

> 这个文件会被 AI 助手**自动加载**到每个新会话里。新电脑上 clone 工程后即可开工，
> 不需要额外"喂上下文"。详细文档见 `docs/`。

---

## 1. 这个项目是什么

从零手写实现的 **DLNA / UPnP 控制点 Android App**（Kotlin，自研协议栈，不依赖第三方 UPnP 库），
用于把家里的 DLNA 音箱 / 电视 / PC 媒体服务器统一成一个"遥控器 + 媒体库"。
正式包名 `com.havencast.remote`。中文界面，面向普通用户做产品化。

定位：**精简易用**；广告只在「媒体库」页底部放一条横幅、绝不打断播放；
后续收费方向是"帮用户管理服务器"（建目录/清理/推荐）与去广告。

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
- **发邮件用 `ACTION_SENDTO` + `mailto:` 时，主题/正文只能写进 URI 查询参数**：
  `EXTRA_SUBJECT` / `EXTRA_TEXT` 是给 `ACTION_SEND` 的，Gmail 等客户端会直接忽略（用户收到空邮件）。
  而且**不能**用 `Uri.parse("mailto:x@y.com").buildUpon().appendQueryParameter(...)`——
  `mailto:` 是 opaque URI，追加 query 会把地址丢掉，实测变成 `mailto:?subject=…`（收件人凭空消失）。
  正确做法是手工拼串 + `Uri.encode`，换行按 RFC 6068 用 `%0D%0A`（见 `FeedbackReporter.mailtoUri`）。
- **别把 `ANDROID_USER_HOME` 指到工程内**：AGP 会改用它下面的 `debug.keystore` 签名，
  与默认 `%USERPROFILE%\.android\debug.keystore` 签出来的 APK 签名不一致 →
  `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装（会清掉索引/收藏）。
  要么不设这个变量（推荐，SDK 位置由 `local.properties` 的 `sdk.dir` 决定），要么始终用同一把 debug key。
  换机后如果手机上的旧包是别的 key 签的，先 `apksigner verify --print-certs` 对比再决定是否卸载。

---

## 3. 构建与验证命令

```powershell
$env:GRADLE_USER_HOME='<工程根>\.gradle-home'   # 只设这个；不要设 ANDROID_USER_HOME（见上）
.\gradlew.bat --no-daemon :app:assembleDebug        # 产物 app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat --no-daemon :app:testDebugUnitTest    # 既有单测回归（不新增）
```

- 真机：`adb install -r app\build\outputs\apk\debug\app-debug.apk`，日志 `adb logcat -s HavenCast`
- 自动化截图（本机 `adb shell input tap` 被系统拒绝时）：
  临时在 `MainActivity.onCreate` 里按 intent extra 直开目标页面/对话框 → `adb shell am start -n ... --es xxx`，
  **截完图必须删掉这段临时代码**；截图用 `adb shell screencap -p /sdcard/x.png` + `adb pull`
  （`exec-out > file` 在 Windows 上会写坏 PNG），超过 2000px 需先缩放。
- 环境要求（JDK 17/21、Android SDK platform 37、`local.properties` 的 `sdk.dir`）见 `docs/08-换电脑继续开发.md`

---

## 4. 代码地图（速查）

```
app/src/main/java/com/havencast/remote/
├── MainActivity.kt          UI 编排：三个 Tab（媒体库/播放/设置）+ 搜索页 + 服务器分类页 + 各种对话框
├── MainViewModel.kt         唯一编排中枢：线程池/Handler、UiState、扫描子系统、队列记账、索引进度回调
├── DeviceRegistry.kt        设备注册表：增删/心跳清理/描述拉取/快照恢复
├── SubscriptionManager.kt   GENA 订阅生命周期
├── NowPlayingSession.kt     DLNA 播放会话：进度/音量/播完判定（事件 + 兜底轮询）
├── LocalPlayer.kt           本机音频播放（**Media3 ExoPlayer**，URL 编码 + 错误诊断 + 读内嵌封面）
├── PlaybackQueue.kt         队列：待播 pending / 当前 current / 已播 history（纯逻辑）
├── PlayHistory.kt           最近播放（按网络，JSON 持久化）
├── DeviceBookmarks.kt       设备收藏 / 别名（按 UDN 稳定键）
├── DeviceCache.kt           设备列表快照（按网络；切后台/杀进程后列表不空）
├── MediaIndexStore.kt       曲库索引（SQLite：分类/去重/搜索/分专辑歌手查询）
├── LibraryIndexer.kt        后台 BFS 建索引（可停/可续扫，统计跳过重复）
├── LocalMusicIndexer.kt     手机本地音乐索引（MediaStore → 同一张索引表，key=local:mediastore）
├── LocalFileServer.kt       手机当媒体源：HTTP 文件服务（支持 Range）
├── FeedbackReporter.kt      意见反馈：内容成文 + 环境信息 + 投递（邮件/分享/剪贴板）
├── ImagePreviewActivity.kt  图片预览（双指缩放/拖动/轻点关闭）
├── NetworkScope.kt          网络作用域（按网段 net:192.168.1 隔离数据）
├── ads/                     AdMob：AdsManager（UMP 同意 → 初始化 → 媒体库页底部横幅）/ AdPolicy（去广告开关）
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
- **手机本地音乐**是同一张索引表里的一个"伪源"（`local:mediastore`），因此**复用同一套分类浏览与搜索**；
  手机文件的 `content://` 地址设备拿不到 → 只能本机播放（或交系统播放器）。

---

## 5. 已完成（进度）

- 协议：SSDP 发现 → 描述 → SCPD → SOAP 控制 → GENA 订阅/事件（`docs/01~04`）+ 手写 Python 虚拟音箱（`docs/06`）
- 播放：曲库浏览（常驻对话框、可连续排队）、推送到设备、进度/音量实时、Seek、记忆上次播放
- 队列：排队 / 播完自动连播 / 删除·上移下移·插队 / 持久化（按网络）
- 历史：最近播放（按网络）一键重播
- 设备管理：收藏置顶、别名重命名、能力探测与标注、记住上次推送的设备
- 索引与搜索：SQLite 本地索引（可停/续扫/去重/分类）→ 音乐/视频/图片分类 → 专辑/歌手/歌曲 →
  全部播放·全部入队·单个入队（行尾 ⋮ 菜单）、服务器 `Search` 兜底、跨服务器去重
- 本机媒体：音乐用 App 播放页本机播放（**Media3 ExoPlayer**，含本机连播、读内嵌封面）、
  视频交系统播放器（VLC 等）、图片预览页
- **手机本地音乐**：MediaStore 扫描（只申请 `READ_MEDIA_AUDIO`，不用全盘权限）→ 写入同一张索引表 →
  媒体库页「手机本地音乐（N 首）」入口 → **同一套分类浏览（专辑/歌手/歌曲）与搜索**，点歌即本机播放
- **播放模式**：顺序 / 列表循环 / 单曲循环 / 随机（播放页按钮循环切换，按网络记忆）
- UI：底部三 Tab（媒体库/播放/设置）、播放大卡（深色沉浸）、深浅色主题、首启三步引导
- 本地文件推送：手机起 HTTP 服务（支持 Range）推给设备
- **设置页**：帮助与反馈（意见反馈：类型标签 + 描述 + 可选联系方式 + 可附带版本/机型/网络/索引诊断信息，
  经邮件/系统分享/剪贴板投递；收件邮箱见 `FeedbackReporter.FEEDBACK_EMAIL`，留空则走系统分享）、
  应用信息（关于 / 隐私政策 / 第三方开源许可）
- **广告（AdMob）**：仅「媒体库」页底部一条自适应横幅（播放页等一律不放，不做插屏/开屏）；
  走 Google UMP 同意流程后初始化 SDK，全程异步、失败即隐藏、不阻塞任何主流程；
  去广告开关是 `AdPolicy`（将来接 Billing / 激励视频）；
  测试期用 Google 官方测试 ID，Release 构建有 `checkAdMobTestIds` 闸门拦住测试 ID 上架

## 6. 已知问题

1. **部分设备 GENA 事件不可靠**：进度/音量/状态靠兜底轮询校准（已缓解）。
2. **活动设备自动订阅不生效**（历史问题）：见 `docs/07-代码架构.md` 已知问题 #1，暂时搁置。
3. **自动连播触发依赖 GENA 或"有时长"**：设备既不推事件又不报时长的轨道可能不自动下一首。
4. 迁移后（DB schema 升级）会**清空曲库索引**，需要用户重新建一次索引。

## 7. 下一步候选（按讨论顺序）

1. 媒体库页内嵌浏览（替代弹窗式浏览）。
2. **可发布化**：真图标/启动图、商店素材（截图/描述/feature graphic/Data safety）、
   `values-en` 英文文案、`NO_ADS` 与广告接入时机、隐私政策随功能更新。
3. 服务器助手（收费方向）：基于索引做重复清理、目录整理、推荐。
4. 本机播放若遇平台/ExoPlayer 都解不了的格式（WMA/APE/DSD）才考虑 libVLC/FFmpeg（体积与许可成本高）。

已完成但值得留意的扩展点：本机音乐索引只收录**系统媒体库已入库**的音频（MediaStore），
未入库的散落文件不在其中（这是"不申请全盘权限"的取舍）。

## 8. 相关文档

| 文件 | 内容 |
|---|---|
| `docs/01~06` | UPnP 各协议的学习笔记（SSDP/SCPD/SOAP/GENA/DLNA/曲库/设备端） |
| `docs/07-代码架构.md` | 分层、线程模型、文件职责、扩展指南、收费口子、已知问题 |
| `docs/08-换电脑继续开发.md` | 换机清单：代码搬迁、JDK/SDK/Gradle、构建、数据位置、DSH 迁移 |
| `docs/09-Release发布.md` | 双密钥签名、AAB/APK 构建脚本、R8、Play App Signing |
| `docs/10-隐私政策.md` | 隐私政策正文（App 内 `raw/privacy_policy.txt` 是同一份内容） |
| `docs/11-广告接入.md` | AdMob：产品原则、代码结构、上线前改 ID / Data safety、UMP 合规、已知坑 |
