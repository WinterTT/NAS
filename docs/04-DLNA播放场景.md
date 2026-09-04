# MyUPNP 学习笔记

用 Android 工程从零手写 UPnP（不依赖任何 UPnP/DLNA 库），一边学协议一边写代码。

---

## 第 4 课：DLNA 播放器场景（已实现 ✅）

### 4.1 一句话

> 前三课的工具（发现/描述/控制/事件）凑齐了，这一课把它们串成一个能玩的应用：
> **在 App 里输入一个媒体 URL，让局域网里的电视/音箱真的播起来**。

### 4.2 DLNA = UPnP + 媒体扩展

DLNA（数字生活网络联盟）基于 UPnP 协议，对媒体设备定义了两类标准服务：

| 服务 | 干什么 | 关键动作 | 我们在第几课学的传输 |
|---|---|---|---|
| **AVTransport** | 播放控制 | SetAVTransportURI / Play / Pause / Stop / Seek | SOAP(第2课) |
| **RenderingControl** | 画面/音量 | GetVolume / SetVolume / SetMute | SOAP(第2课) |
| **ConnectionManager** | 连接管理 | （本课暂不深入） | - |

### 4.3 播放一个 URL 的完整动作链

```
① 找到播放器（有 AVTransport 服务的设备 = MediaRenderer）
② SetAVTransportURI(InstanceID=0, CurrentURI=媒体URL, CurrentURIMetaData=DIDL描述)
     告诉电视"要播这个资源"；DIDL 描述声明资源类型/标题，很多电视缺了它拒播
③ Play(InstanceID=0, Speed=1)   -> 开播
④ 音量：RenderingControl.GetVolume(读当前) -> SetVolume(DesiredVolume=目标值)
⑤ 状态：GENA 订阅 AVTransport -> 事件里 LastChange 展开出 TransportState
       = PLAYING / PAUSED / STOPPED 和播放进度（第 3 课的工具在这里派上用场）
```

### 4.4 本课代码地图

```
dlna/DlnaPlayer.kt           场景编排：找 AVTransport/RCS、pushAndPlay 两连击、
                             didlMetadata 构造、音量步进、GetVolume 响应解析
dlna/LastChangeParser.kt     把事件里转义嵌套的 LastChange XML 展开成可读状态
                             （TransportState / 进度 / 音量 / 静音）
MainActivity.kt              设备是播放器 -> 播放器面板：输入 URL 推送播放、
                             暂停/停止、音量±、订阅状态实时刷日志
docs/04-DLNA播放场景.md       本课笔记
```

关键实现细节：
- **DIDL-Lite 元数据**：`CurrentURIMetaData` 是 XML 字符串，里面要 XML 转义
  （`&` -> `&amp;`），否则整个 SOAP 信封坏掉。代码 `dlna/DlnaPlayer.kt:didlMetadata`。
- **SOAP 动作的 out 参数**：`GetVolume` 的响应里 `<CurrentVolume>45</CurrentVolume>`
  就是 out 参数 —— 读取设备状态不再只靠事件，动作返回也能带值。
- **两连击的失败短路**：`SetAVTransportURI` 失败就不发 `Play`，
  错误日志能看到设备具体拒播原因（701 没这动作 / 712 协议不支持等）。

### 4.5 验证

- 单测：LastChangeParserTest（3 个，AVT/RCS 展开）+ DlnaPlayerTest（4 个，
  播放器识别/DIDL 转义/GetVolume 解析）。
- 真机玩法：扫到电视/音箱 -> 点设备 -> 「播放器场景」-> 填一个局域网内的
  视频 URL（如 http://电脑IP/xxx.mp4）-> 推送播放，电视应该开始放；
  播放中按「订阅状态」，看日志实时刷 TransportState 和播放进度。
