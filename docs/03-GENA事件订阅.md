# MyUPNP 学习笔记

用 Android 工程从零手写 UPnP（不依赖任何 UPnP/DLNA 库），一边学协议一边写代码。

---

## 第 3 课：GENA 事件订阅（已实现 ✅）

### 3.1 一句话

> 前两课都是"控制点主动问/主动调"；第 3 课反过来 —— **设备状态一变化
> （音量调了、播放结束、进度变了），主动把新值推到控制点**，不用轮询。

### 3.2 核心概念：控制点也要当一次 HTTP 服务器

GENA 最反直觉的一点：**设备往"你的地址"发 HTTP 请求**。所以 App 里
内嵌了一个极简 HTTP 服务器（`LocalEventServer`），先把自己的地址
`http://手机IP:端口/cb` 交给设备，设备之后就用 NOTIFY 往这推。

流程：

```
1. 订阅（控制点 → 设备）
   SUBSCRIBE /upnp/event/AVTransport HTTP/1.1     (→ 服务的 eventSubURL)
   CALLBACK: <http://手机IP:39000/cb>              ← 尖括号不能省
   NT: upnp:event
   TIMEOUT: Second-1800
   设备回: 200 + SID: uuid:xxxx  ← 订阅凭证号（后续续订/退订都靠它）

2. 状态变化（设备 → 控制点，多次）
   NOTIFY /cb HTTP/1.1
   SID: uuid:xxxx
   NTS: upnp:propchange
   body: <e:propertyset><e:property><Volume>45</Volume>...</e:property></e:propertyset>

3. 续订（订阅有时效，到期前自动 SUBSCRIBE + SID 延长）
   SUBSCRIBE ... + SID: uuid:xxxx + TIMEOUT: Second-1800

4. 退订
   UNSUBSCRIBE ... + SID: uuid:xxxx
```

### 3.3 关键实现点（对应代码）

- `gena/GenaClient.kt`：SUBSCRIBE / RENEW / UNSUBSCRIBE。
  为什么不用 HttpURLConnection？它只允许标准方法，而 GENA 要
  SUBSCRIBE/UNSUBSCRIBE —— 所以**用裸 Socket 手写 HTTP**（跟第 1 课学的一致）。
  成功标志：200 + SID。
- `gena/LocalEventServer.kt`：App 内的极简 HTTP 服务器。
  刻意**不用任何 android.\*** 类 → 可以在 JVM 单测里完整验证。
  收到请求：读首部 → 按 Content-Length 读正文 → 回 200 → 只把 NOTIFY 交给上层。
- `gena/EventProperties.kt`：解析推送正文 `<e:propertyset><e:property>…`，
  得到 (状态变量名, 新值) 列表。注意 LastChange 这类变量值是转义 XML 原文。
- `MainActivity.kt`：服务菜单新增 订阅/续订/退订；每个订阅存 SID；
  到期前一半时间自动续订（`scheduleRenewal`）。

### 3.4 常见坑

- CALLBACK 必须用 `<>` 包住；NT 固定 `upnp:event`。
- 订阅的 SID 一定要存好，续订/退订都要带。
- 订阅有 TIMEOUT（通常 1800s），必须**提前续订**，否则设备停止推送。
- 手机 IP 变 / 被路由器 AP 隔离时，设备推不过来（回调失败无提示，只能查日志）。
- 服务器要能容忍坏报文：设读超时、首部/正文长度上限，单个连接异常不能拖垮整体。

### 3.5 验证

- 单测：GenaClientTest（4 个，假设备回 SID/500）+ LocalEventServerTest
  （真 socket 发 NOTIFY 验证收推 + 属性解析 + 容错）。
- 真机玩法：扫描 → 点设备 → 选服务（如 RenderingControl）→ 「订阅事件推送」，
  然后去电视上调音量/静音，日志区会实时刷出 `Volume = 45` 这样的推送。
