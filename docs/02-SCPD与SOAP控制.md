# MyUPNP 学习笔记

用 Android 工程从零手写 UPnP（不依赖任何 UPnP/DLNA 库），一边学协议一边写代码。

---

## 第 2 课：SCPD + SOAP 控制（已实现 ✅）

### 2.1 一句话

> 第 1 课找到了设备并拿到了服务列表；第 2 课开始**命令设备干活**：
> 每个服务给了一份"说明书"(SCPD)，告诉你它能做哪些动作(Play/Pause/SetVolume…)；
> 调用动作时把动作+参数装进 SOAP XML"信封"，POST 给服务的 `controlURL`。

### 2.2 两个新概念

**SCPD —— 服务的"菜单"**
- 设备描述里的每个 `<service>` 带一个 `<SCPDURL>`，指向该服务的 SCPD 文档。
- SCPD 里是 `<actionList>`：每个 `<action>` 有动作名 + 参数表。
- 每个参数有 `direction`：`in`（我们要传进去）/ `out`（设备返回给我们）。

**SOAP —— 执行动作的协议**
- 本质就是一次 HTTP POST，请求体是 XML 信封。
- 信封格式（UPnP 用的是 SOAP 1.1）：

```xml
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>
```

- HTTP 头里最重要的一个是：
  `SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"`（服务类型#动作名，**必须带双引号**）。
- 设备回 `200` 表示成功（响应信封里含 out 参数）；
  出错回 `500`，正文是 `<s:Fault>`，其中 `<errorCode>` 是 UPnP 错误码
  （常见：401 无权限 / 402 参数错 / 501 动作失败 / 701 无此动作 / 702 无此参数）。

### 2.3 代码地图（本课新增）

```
soap/SoapCaller.kt            构造 SOAP 信封 + POST + 解析 200/500 Fault
device/ScpdLoader.kt          拉取并解析 SCPD 的 actionList -> List<UpnpAction>
model/UpnpAction.kt           UpnpAction / UpnpArgument 模型（in/out 参数）
MainActivity.kt               点击设备 -> 选服务 -> 拉SCPD -> 选动作 -> 填参数 -> 调用
```

- 代码可读性：SOAP 调用全在一个 `SoapCaller.call()`，UI 层只拼参数。
- 顺带修复：description 里的 controlURL 等可能是相对路径，加载时用
  `java.net.URL(base, rel)` 解析成绝对地址（`DeviceDescriptionLoader.resolve`）。

### 2.4 常见坑

- **SOAPACTION 引号**：规范要求 `"serviceType#action"` 带双引号，很多设备没引号就 500。
- **相对 URL**：老设备的 `<controlURL>/upnp/control/xxx</controlURL>` 是相对路径，
  不拼 LOCATION 就直接 POST 必然失败。
- **错误码藏在 Fault 里**：SOAP 出错也是 HTTP 200？不 —— 一定是 500，
  且错误详情在 `<s:Fault><detail><UPnPError>`，要用正则/解析器挖出来，别只看状态码。
- 不同服务类型 = 不同命名空间：Play 只属于 AVTransport，SetVolume 属于
  RenderingControl，`SOAPACTION` 头必须严格对应。

---

## 第 3 课预告：GENA 事件订阅

设备状态变化（音量变了、播放进度走了）由设备主动推送，控制点不用轮询。
订阅用 HTTP `SUBSCRIBE` 到服务的 `eventSubURL`，推送走回调端点 —— 我们会在 App 里开一个
轻量 HTTP 服务来接收事件。
