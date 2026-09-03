# MyUPNP 学习笔记

用 Android 工程从零手写 UPnP（不依赖任何 UPnP/DLNA 库），一边学协议一边写代码。

---

## 第 1 课：UPnP 是什么 + SSDP 设备发现 + 设备描述（已实现 ✅）

### 1.1 UPnP 体系一句话

> **UPnP（Universal Plug and Play）** 是一组基于 IP 的开放协议，让设备接入局域网后
> **零配置**即可被其它设备发现与控制。它的几个"字母缩写"是学习的骨架：

| 缩写 | 全称 | 干什么 | 承载协议 | 对应本课代码 |
|---|---|---|---|---|
| **SSDP** | Simple Service Discovery Protocol | 发现设备 | UDP 组播 239.255.255.250:1900 | `SsdpDiscovery` |
| **Description** | 设备/服务描述 | 说明设备长什么样 | HTTP GET XML | `DeviceDescriptionLoader` |
| **SCPD** | Service Control Protocol Description | 描述服务能调用哪些动作 | HTTP GET XML | 第 2 课 |
| **SOAP** | Simple Object Access Protocol | 真正调用动作 | HTTP POST XML | 第 2 课 |
| **GENA** | General Event Notification | 订阅服务状态变化事件 | HTTP SUBSCRIBE/NOTIFY | 第 3 课 |

**角色划分**：
- **控制点（Control Point）**：主动发现、控制别人 —— 本 App 就是。
- **设备（Device）**：被别人发现、提供服务的（路由器、智能电视、音箱、DLNA 服务器…）。

### 1.2 SSDP 组播发现（本课核心）

- **组播组地址** `239.255.255.250:1900`：所有 UPnP 设备都"蹲"在这个组里。
- 控制点发出 **M-SEARCH**（组播），设备收到后**单播**回 `HTTP/1.1 200 OK`。
- 设备上线/下线会主动广播 **NOTIFY**（`ssdp:alive` / `ssdp:byebye`），控制点即使不发
  搜索也能感知。
- 关键首部字段：
  - `LOCATION`：设备描述 XML 的 HTTP 地址（发现后靠它拿描述）
  - `USN` / `UDN`：设备唯一标识 `uuid:xxxx`
  - `ST`：搜索目标（`ssdp:all` = 全部，也可搜特定类型如 `urn:schemas-upnp-org:device:MediaRenderer:1`）
  - `MX`：最长等待应答秒数

> **踩坑经验（Android）**：默认 Wi-Fi 网卡在硬件/驱动层就把组播帧丢了，必须
> `WifiManager.createMulticastLock().acquire()` 才能收到组播；socket 要
> `setReuseAddress(true)` 以和别的 UPnP 程序共用 1900 端口；网络 IO 一律后台线程。
>
> **再补一个大坑（实测踩到）**：M-SEARCH 不能从"绑定了 1900 端口的 socket"发出去！
> 设备应答是**单播回给 M-SEARCH 包的源 IP:源端口**的，如果源端口正好是 1900，
> 在 Windows 上应答会被系统 SSDP 服务/防火墙拦掉，在部分 Android（含 MIUI/HyperOS）
> 环境也收不到 —— 表现为"扫不到任何设备"，但 PC 上用临时端口裸发 M-SEARCH 却能收到
> 一堆应答。正确姿势（本仓库 `SsdpDiscovery` 已按此实现）：
>   1) 用一个绑**临时端口**的 socket 发 M-SEARCH、收单播应答（这是 UPnP 控制点惯例）；
>   2) 另开一个尽量绑 1900 的组播 socket 专门收设备主动广播的 NOTIFY（绑不上就退化）。

### 1.3 设备描述（description.xml）

LOCATION 指向的文档描述一台设备：`friendlyName`（界面上显示的名字）、`manufacturer`、
`modelName`、`UDN`，以及最重要的 **`serviceList`** —— 该设备对外提供的服务清单。
每个服务带 `serviceType` + 三个 URL（`SCPDURL`/`controlURL`/`eventSubURL`），
它们是后续"调用动作 / 订阅事件"的入口。

### 1.4 本课代码地图

```
MainActivity.kt                    控制点主界面：扫描开关/设备列表/协议日志
ssdp/SsdpDiscovery.kt              SSDP 引擎：组播 socket、M-SEARCH、NOTIFY 监听
ssdp/SsdpMessage.kt                SSDP 报文模型与解析（首部 -> headers map）
device/DeviceDescriptionLoader.kt  HTTP 拉取 description.xml + XmlPullParser 解析
model/UpnpDevice.kt / UpnpService.kt  数据模型
```

### 1.5 真机验证方法

1. 手机与目标 UPnP 设备连**同一个 Wi-Fi**（路由器、电视盒、DLNA 服务器等都可作目标）。
2. 安装 App → 点「开始扫描」→ 授权「附近的设备」权限。
3. 观察"SSDP 协议日志"：会刷出 `[搜索应答]`/`[设备上线 NOTIFY]` 及首部；
   "发现的设备"列表出现 friendlyName、型号、服务列表。
4. 关掉某设备 → 收到 `byebye` → 设备从列表消失。

---

## 第 2 课预告：SCPD + SOAP 控制（调用设备动作）

拿到 `controlURL` 后，用 SOAP/XML 调用设备动作：
```
POST /upnp/control/AVTransport HTTP/1.1
CONTENT-TYPE: text/xml; charset="utf-8"
SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"
```
目标：点列表里的设备 → 让它播放 / 暂停 / 调音量。
