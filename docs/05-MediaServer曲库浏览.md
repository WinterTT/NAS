# MyUPNP 学习笔记

用 Android 工程从零手写 UPnP（不依赖任何 UPnP/DLNA 库），一边学协议一边写代码。

---

## 第 5 课：MediaServer 曲库浏览 + 点歌播放（已实现 ✅）

### 5.1 一句话

> 前面四课我们是"遥控器+手动填 URL"。这一课 MediaServer 真正派上用场：
> App 能**逛**它的曲库（文件夹一层层进），看到歌名点一下，
> 就把那首歌推给音响播 —— 不用手输任何 URL，闭环了。

### 5.2 角色回顾：现在三台设备凑齐了

```
MediaServer（电脑）           控制点（手机 App）             MediaRenderer（音响）
  曲库文件夹树  ──ContentDirectory──▶  逛歌单     ──AVTransport──▶  出声
  Browse(文件夹) ◀────────────────┘             SetAVTransportURI+Play ─┘
```

### 5.3 ContentDirectory 的 Browse 动作

MediaServer 用一棵**对象树**组织媒体，核心服务是 ContentDirectory：

```
Browse(
  ObjectID = "0",              <- 根；进文件夹就传文件夹的 id
  BrowseFlag = "BrowseDirectChildren",  <- 只列"直接子项"（一层），不递归
  Filter = "*",                <- 要全部元数据
  StartingIndex = "0", RequestedCount = "0", SortCriteria = ""
)
```

响应里 `Result` 是一段 **DIDL-Lite** XML（在 SOAP 里被转义过一次）：

```xml
<DIDL-Lite ...>
  <container id="2">      <- 文件夹：继续 Browse(id=2) 进入
    <dc:title>流行</dc:title>
    ...
  </container>
  <item id="12">          <- 歌曲/视频：res 里是播放地址
    <dc:title>晴天.mp3</dc:title>
    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
    <res protocolInfo="http-get:*:audio/mpeg:...">http://ip/file.mp3</res>
  </item>
</DIDL-Lite>
```

**逛的机制**：Browse 只给你"一层"；要下钻就把 container 的 id 当
ObjectID 再 Browse。这就是"目录树导航"。

### 5.4 代码地图

```
model/MediaObject.kt                容器 container / 条目 item（带 res 播放地址）
dlna/ContentDirectoryClient.kt      Browse 动作 + Result 抽取(反转义) + DIDL 解析
MainActivity.kt
   - 点 MediaServer 设备 -> 「浏览媒体库」
   - 逐层 Browse，面包屑路径支持「返回上级」
   - 点条目 -> 自动找局域网第一台 MediaRenderer -> DlnaPlayer.pushAndPlay
单测 ContentDirectoryClientTest     4 个：转义抽取/容器条目解析/空库/无前缀兼容
docs/05-MediaServer曲库浏览.md       本课笔记
```

### 5.5 常见坑（协议层面都踩过/防住了）

- **Result 是双重转义**：SOAP 里 `<Result>` 内容是 `&lt;container...`，
  必须先反转义才能解析 —— 直接正则找 `<container` 会一无所获。
- **命名空间前缀不统一**：有的设备写 `<dc:title>`，老设备只写 `<title>`；
  解析器按"可选前缀"兼容。
- **Browse 只返回一层**：不要指望一次拿全树，递归 Browse 才对。
- **childCount 也有前缀**：`<container:childCount>`，解析要灵活。
- 点歌播放没有播放器时要给提示，而不是静默失败。

### 5.6 真机玩法

手机 App 扫到 MediaServer → 点它 → 「浏览媒体库」→ 看到你的音乐文件夹
（📁）→ 点进去 → 看到 mp3（🎵）→ 点歌曲 → 自动推给音响开播，
日志区实时显示 Browse / SetAVTransportURI / Play / 事件进度。
