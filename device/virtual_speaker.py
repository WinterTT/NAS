#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B1: 虚拟音响 —— 一台极简 DLNA MediaRenderer（纯 Python 标准库）
=========================================================================
用途：在电脑上伪装成一台"网络音响"，让手机上的 MyUPNP App
（或任何 DLNA 控制点）发现它、向它推歌、调它音量、订阅它的事件。

这是前几课的"镜像"：前面我们用 Kotlin 写控制点（主动问/调/订），
现在用 Python 写设备端，把同样的协议反过来实现：
  控制点                             本脚本（设备）
  ------------------------------------------------------------
  发 M-SEARCH (组播)        <--应答--> 监听 1900，回 200 OK
  读 description.xml        <--提供--> /rootDesc.xml
  读 SCPD                   <--提供--> /scpd/AVTransport.xml ...
  发 SOAP 控制(POST)        <--执行--> /upnp/control/...
  发 SUBSCRIBE              <--管理--> /upnp/event/...
  收 NOTIFY 事件            <--推送--> 状态变了主动发 LastChange

运行：  python virtual_speaker.py        （Windows/Linux/macOS 均可）
依赖： 仅 Python 3.8+ 标准库，零 pip 安装。
注意： 需与手机同一 Wi-Fi；首次运行 Windows 会弹防火墙，请允许"专用网络"。
"""

import socket
import struct
import threading
import time
import uuid
import sys
import re
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
SSDP_ADDR = "239.255.255.250"
SSDP_PORT = 1900
HTTP_PORT = 8200                     # 本设备的 HTTP 服务端口（描述/控制/订阅）
DEVICE_UUID = "3f2a1b4c-1111-4000-8000-010203040506"
DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
AVT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"       # 播放控制
RCS_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"  # 音量/静音
NS_AVT = "urn:schemas-upnp-org:metadata-1-0/AVT/"                # LastChange 命名空间

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def now() -> str:
    """HTTP 日期头格式"""
    return time.strftime("%a, %d %b %Y %H:%M:%S GMT", time.gmtime())

def local_ipv4() -> str:
    """找本机对外 IPv4（跳过回环/虚拟网卡）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # 不发包，仅靠 connect 让系统选路由
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

# ---------------------------------------------------------------------------
# 设备状态（这台"音响"的心脏）
# ---------------------------------------------------------------------------
class Renderer:
    """模拟一台 DLNA 音响的全部内部状态 + 事件推送能力"""

    def __init__(self):
        self.ip = local_ipv4()
        self.http_port = HTTP_PORT
        # ---- 播放状态（AVTransport）----
        self.transport_state = "STOPPED"    # STOPPED / PLAYING / PAUSED_TRANSPORT_STOPPED ...
        self.current_uri = ""
        self.position = 0                   # 已播秒数
        self.duration = 0
        # ---- 音量（RenderingControl）----
        self.volume = 30
        self.mute = 0
        # ---- GENA 订阅：sid -> (callback_url, expire_ts) ----
        self.subscribers = {}
        self.lock = threading.Lock()
        self._seq = 0
        print(f"[speaker] 本机地址 {self.ip}:{self.http_port}  UUID {DEVICE_UUID}")
        print(f"[speaker] 这是一台虚拟 DLNA 音响，状态: STOPPED，音量 30")

    # ---------- URL 构造 ----------
    def base(self) -> str:
        return f"http://{self.ip}:{self.http_port}"

    def location(self) -> str:
        return f"{self.base()}/rootDesc.xml"

    # ---------- 状态变化：统一走这里，自动推事件 ----------
    def set_state(self, new_state: str, reason: str):
        with self.lock:
            old = self.transport_state
            self.transport_state = new_state
        print(f"[speaker] ▶ 状态 {old} -> {new_state}   ({reason})")
        self.push_event()   # 状态一变就通知所有订阅者（控制点）

    def set_volume(self, v: int):
        with self.lock:
            self.volume = max(0, min(100, v))
        print(f"[speaker] 🔊 音量 -> {self.volume}")
        self.push_event()

    def set_uri(self, uri: str):
        with self.lock:
            self.current_uri = uri
            self.position = 0
        print(f"[speaker] 📼 收到播放地址: {uri}")
        # 设源后停在 STOPPED，等控制点再发 Play

    # ---------- LastChange 事件体 ----------
    def _last_change_xml(self) -> str:
        """RenderingControl/AVTransport 状态打包成 LastChange（AVT 风格）"""
        state = self.transport_state
        return (
            f'<Event xmlns="{NS_AVT}">'
            f'<InstanceID val="0">'
            f'<TransportState val="{state}"/>'
            f'<CurrentTrackURI val="{self.current_uri}"/>'
            f'<Volume val="{self.volume}"/>'
            f'<Mute val="{self.mute}"/>'
            f'</InstanceID></Event>'
        )

    def push_event(self):
        """向所有订阅者发一帧 NOTIFY propchange"""
        if not self.subscribers:
            return
        lc = html.escape(self._last_change_xml(), quote=True)   # 内层 XML 转义
        body = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">'
            '<e:property>'
            f'<LastChange>{lc}</LastChange>'
            '</e:property>'
            '</e:propertyset>'
        )
        with self.lock:
            self._seq += 1
            seq = self._seq
            subs = list(self.subscribers.items())
        for sid, (cb, _exp) in subs:
            threading.Thread(target=self._send_notify,
                             args=(sid, cb, seq, body), daemon=True).start()

    def _send_notify(self, sid, callback, seq, body):
        """NOTIFY /cb HTTP/1.1 —— 推到控制点给的回调地址"""
        try:
            # CALLBACK 形如 <http://192.168.1.50:39000/upnp/event/cb>
            m = re.search(r"<(http://[^>]+)>", callback)
            if not m:
                return
            url = m.group(1)
            host, path = url[len("http://"):].split("/", 1)
            h, _, p = host.partition(":")
            port = int(p) if p else 80
            req = (
                f"NOTIFY /{path} HTTP/1.1\r\n"
                f"HOST: {h}:{port}\r\n"
                f"CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                f"NT: upnp:event\r\n"
                f"NTS: upnp:propchange\r\n"
                f"SID: {sid}\r\n"
                f"SEQ: {seq}\r\n"
                f"Content-Length: {len(body.encode())}\r\n\r\n"
            ).encode() + body.encode()
            s = socket.create_connection((h, port), timeout=3)
            s.sendall(req)
            s.recv(1024)          # 读掉 200 OK
            s.close()
        except Exception as e:
            print(f"[speaker] 推送失败 {callback}: {e}")

    # ---------- GENA 订阅管理 ----------
    def subscribe(self, callback, timeout_sec):
        sid = f"uuid:{uuid.uuid4()}"
        with self.lock:
            self.subscribers[sid] = (callback, time.time() + timeout_sec)
        print(f"[speaker] ✉ 新订阅 SID={sid}  callback={callback}  共{len(self.subscribers)}个")
        # 订阅成功后立刻推一帧当前状态（很多设备的做法）
        self.push_event()
        return sid

    def renew(self, sid, timeout_sec):
        with self.lock:
            if sid in self.subscribers:
                cb, _ = self.subscribers[sid]
                self.subscribers[sid] = (cb, time.time() + timeout_sec)
                return True
        return False

    def unsubscribe(self, sid):
        with self.lock:
            if self.subscribers.pop(sid, None):
                print(f"[speaker] ✂ 退订 SID={sid}  剩{len(self.subscribers)}个")
                return True
        return False

# ---------------------------------------------------------------------------
# 设备（单例）
# ---------------------------------------------------------------------------
renderer = Renderer()

# ===========================================================================
# 一、SSDP 端：设备"被找到"
# ===========================================================================
def ssdp_loop():
    """加入 239.255.255.250:1900，应答 M-SEARCH，收 alive 通知"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", SSDP_PORT))
    except OSError as e:
        print(f"[ssdp] 绑定 {SSDP_PORT} 失败（可能已被占用），改用临时端口: {e}")
        sock.bind(("", 0))
    mreq = struct.pack("4sl", socket.inet_aton(SSDP_ADDR), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.settimeout(1.0)
    print(f"[ssdp] 监听组播 {SSDP_ADDR}:{SSDP_PORT}（按 Ctrl+C 退出）")

    while True:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            continue
        except OSError:
            return
        text = data.decode("utf-8", "ignore")
        if not text.startswith("M-SEARCH"):
            continue
        headers = parse_headers(text)
        st = headers.get("st", "ssdp:all")
        # 只回应我们支持的搜索目标
        if not (st == "ssdp:all" or "rootdevice" in st or "MediaRenderer" in st
                or "AVTransport" in st or "RenderingControl" in st):
            continue
        reply = build_msearch_reply(st)
        sock.sendto(reply.encode(), addr)   # 单播回给控制点！
        print(f"[ssdp] → 应答 M-SEARCH (ST={st}) 来自 {addr[0]}")

def parse_headers(text):
    h = {}
    for line in text.split("\r\n")[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            h[k.strip().lower()] = v.strip()
    return h

def build_msearch_reply(st) -> str:
    """按 SSDP 规范回 200 OK。USN 随 ST 换后缀。"""
    uuid_part = f"uuid:{DEVICE_UUID}"
    if "rootdevice" in st:
        usn = f"{uuid_part}::upnp:rootdevice"
        st_out = "upnp:rootdevice"
    elif "RenderingControl" in st:
        usn = f"{uuid_part}::{RCS_SERVICE}"
        st_out = RCS_SERVICE
    elif "AVTransport" in st:
        usn = f"{uuid_part}::{AVT_SERVICE}"
        st_out = AVT_SERVICE
    else:  # MediaRenderer / ssdp:all
        usn = f"{uuid_part}::{DEVICE_TYPE}"
        st_out = DEVICE_TYPE
    return (
        "HTTP/1.1 200 OK\r\n"
        "CACHE-CONTROL: max-age=1800\r\n"
        f"DATE: {now()}\r\n"
        f"LOCATION: {renderer.location()}\r\n"
        "SERVER: Python/3.11 UPnP/1.0 MyVirtualSpeaker/1.0\r\n"
        f"ST: {st_out}\r\n"
        f"USN: {usn}\r\n"
        "EXT:\r\n"
        "\r\n"
    )

def send_notify_alive():
    """上线广播 3 遍（按规范），让已经在扫的控制点立刻发现我们"""
    for st, usn_suffix in [
        ("upnp:rootdevice", "upnp:rootdevice"),
        (DEVICE_TYPE, DEVICE_TYPE),
        (AVT_SERVICE, AVT_SERVICE),
    ]:
        for _ in range(3):
            msg = (
                "NOTIFY * HTTP/1.1\r\n"
                f"HOST: {SSDP_ADDR}:{SSDP_PORT}\r\n"
                "CACHE-CONTROL: max-age=1800\r\n"
                f"LOCATION: {renderer.location()}\r\n"
                "NT: " + st + "\r\n"
                "NTS: ssdp:alive\r\n"
                "SERVER: Python/3.11 UPnP/1.0 MyVirtualSpeaker/1.0\r\n"
                f"USN: uuid:{DEVICE_UUID}::{usn_suffix}\r\n\r\n"
            )
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.sendto(msg.encode(), (SSDP_ADDR, SSDP_PORT))
            s.close()
    print("[ssdp] 已广播 3 次 NOTIFY alive")

def send_notify_byebye():
    """关停前广播 byebye，让控制点及时清掉我们"""
    for st, usn_suffix in [
        ("upnp:rootdevice", "upnp:rootdevice"),
        (DEVICE_TYPE, DEVICE_TYPE),
        (AVT_SERVICE, AVT_SERVICE),
    ]:
        msg = (
            "NOTIFY * HTTP/1.1\r\n"
            f"HOST: {SSDP_ADDR}:{SSDP_PORT}\r\n"
            "NT: " + st + "\r\n"
            "NTS: ssdp:byebye\r\n"
            f"USN: uuid:{DEVICE_UUID}::{usn_suffix}\r\n\r\n"
        )
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.sendto(msg.encode(), (SSDP_ADDR, SSDP_PORT))
        s.close()
    print("[ssdp] 已广播 byebye")

# ===========================================================================
# 二、HTTP 端：description / SCPD / SOAP 控制 / GENA 订阅
# ===========================================================================
DESC_XML = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>{DEVICE_TYPE}</deviceType>
    <friendlyName>虚拟音响(MyUPNP B1)</friendlyName>
    <manufacturer>MyUPNP</manufacturer>
    <modelName>Virtual Speaker</modelName>
    <UDN>uuid:{DEVICE_UUID}</UDN>
    <serviceList>
      <service>
        <serviceType>{AVT_SERVICE}</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
        <controlURL>/upnp/control/AVTransport</controlURL>
        <eventSubURL>/upnp/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>{RCS_SERVICE}</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
        <controlURL>/upnp/control/RenderingControl</controlURL>
        <eventSubURL>/upnp/event/RenderingControl</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
"""

def scpd_xml(service_type: str) -> str:
    """极简 SCPD：只声明我们会实现的几个动作。真实设备这里会列出全部。"""
    if "AVTransport" in service_type:
        actions = [
            ("SetAVTransportURI", [("InstanceID", "in"), ("CurrentURI", "in"),
                                   ("CurrentURIMetaData", "in")]),
            ("Play",  [("InstanceID", "in"), ("Speed", "in")]),
            ("Pause", [("InstanceID", "in")]),
            ("Stop",  [("InstanceID", "in")]),
            ("GetTransportInfo", [("InstanceID", "in"), ("CurrentTransportState", "out"),
                                  ("CurrentTransportStatus", "out"), ("CurrentSpeed", "out")]),
        ]
    else:  # RenderingControl
        actions = [
            ("SetVolume", [("InstanceID", "in"), ("Channel", "in"), ("DesiredVolume", "in")]),
            ("GetVolume", [("InstanceID", "in"), ("Channel", "in"), ("CurrentVolume", "out")]),
            ("SetMute",   [("InstanceID", "in"), ("Channel", "in"), ("DesiredMute", "in")]),
            ("GetMute",   [("InstanceID", "in"), ("Channel", "in"), ("CurrentMute", "out")]),
        ]
    parts = [f'<scpd xmlns="urn:schemas-upnp-org:service-1-0">',
             '<specVersion><major>1</major><minor>0</minor></specVersion>',
             '<actionList>']
    for name, args in actions:
        parts.append(f"<action><name>{name}</name><argumentList>")
        for aname, direction in args:
            parts.append(
                f'<argument><name>{aname}</name><direction>{direction}</direction>'
                f'<relatedStateVariable>__dummy__</relatedStateVariable></argument>'
            )
        parts.append("</argumentList></action>")
    parts.append("</actionList></scpd>")
    return "".join(parts)

def soap_response(action, namespace, out_args: dict = None) -> str:
    """包一层 SOAP 200 信封。成功响应用处最多，够用即可。"""
    parts = ['<?xml version="1.0" encoding="utf-8"?>',
             '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
             's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">',
             '<s:Body>',
             f'<u:{action}Response xmlns:u="{namespace}">']
    for k, v in (out_args or {}).items():
        parts.append(f"<{k}>{v}</{k}>")
    parts += [f"</u:{action}Response>", "</s:Body>", "</s:Envelope>"]
    return "".join(parts)

def soap_fault(code, desc) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
        '<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>'
        '<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">'
        f"<errorCode>{code}</errorCode><errorDescription>{desc}</errorDescription>"
        '</UPnPError></detail></s:Fault></s:Body></s:Envelope>'
    )

def read_arg(body, name):
    """从 SOAP 正文抠参数值：<CurrentURI>xxx</CurrentURI>"""
    m = re.search(rf"<{name}>(.*?)</{name}>", body, re.S)
    return html.unescape(m.group(1).strip()) if m else ""

class HttpHandler(BaseHTTPRequestHandler):
    def log_message(self, *a):   # 静音默认访问日志
        pass

    # ---------- GET：描述/SCPD ----------
    def do_GET(self):
        if self.path == "/rootDesc.xml":
            self.send_xml(DESC_XML)
        elif self.path.startswith("/scpd/"):
            name = self.path[len("/scpd/"):]
            svc = "AVTransport" if "AVTransport" in name else "RenderingControl"
            self.send_xml(scpd_xml(svc))
        else:
            self.send_error(404)

    def send_xml(self, xml):
        data = xml.encode()
        self.send_response(200)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # ---------- POST：SOAP 控制 ----------
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", "ignore")
        soapaction = self.headers.get("SOAPACTION", "")
        # SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"
        m = re.search(r"#(\w+)\"?$", soapaction)
        action = m.group(1) if m else ""
        print(f"[soap] ← {action}  ({self.path})")

        try:
            if "RenderingControl" in self.path:
                xml = self.handle_rc(action, body)
            else:
                xml = self.handle_avt(action, body)
            data = xml.encode()
            self.send_response(200)
            self.send_header("Content-Type", 'text/xml; charset="utf-8"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            print(f"[soap] ✗ 出错: {e}")
            xml = soap_fault(501, str(e))
            data = xml.encode()
            self.send_response(500)
            self.send_header("Content-Type", 'text/xml; charset="utf-8"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def handle_avt(self, action, body):
        if action == "SetAVTransportURI":
            renderer.set_uri(read_arg(body, "CurrentURI"))
            return soap_response("SetAVTransportURI", AVT_SERVICE)
        if action == "Play":
            renderer.set_state("PLAYING", "Play")
            return soap_response("Play", AVT_SERVICE)
        if action == "Pause":
            renderer.set_state("PAUSED", "Pause")
            return soap_response("Pause", AVT_SERVICE)
        if action == "Stop":
            renderer.set_state("STOPPED", "Stop")
            return soap_response("Stop", AVT_SERVICE)
        if action == "GetTransportInfo":
            return soap_response("GetTransportInfo", AVT_SERVICE, {
                "CurrentTransportState": renderer.transport_state,
                "CurrentTransportStatus": "OK",
                "CurrentSpeed": "1",
            })
        return soap_fault(401, f"动作 {action} 未实现")

    def handle_rc(self, action, body):
        if action == "SetVolume":
            renderer.set_volume(int(read_arg(body, "DesiredVolume") or renderer.volume))
            return soap_response("SetVolume", RCS_SERVICE)
        if action == "GetVolume":
            return soap_response("GetVolume", RCS_SERVICE,
                                 {"CurrentVolume": renderer.volume})
        if action == "SetMute":
            renderer.mute = int(read_arg(body, "DesiredMute") or 0)
            renderer.push_event()
            return soap_response("SetMute", RCS_SERVICE)
        if action == "GetMute":
            return soap_response("GetMute", RCS_SERVICE,
                                 {"CurrentMute": renderer.mute})
        return soap_fault(401, f"动作 {action} 未实现")

    # ---------- GENA：SUBSCRIBE / UNSUBSCRIBE ----------
    def do_SUBSCRIBE(self):
        callback = self.headers.get("CALLBACK", "")
        timeout = 1800
        tm = self.headers.get("TIMEOUT", "")
        if tm.startswith("Second-"):
            timeout = int(tm[7:]) or 1800
        sid = self.headers.get("SID", "")
        if sid:  # 续订
            ok = renderer.renew(sid, timeout)
            code = 200 if ok else 412
        else:    # 新订阅
            sid = renderer.subscribe(callback, timeout)
            code = 200
        self.send_response(code)
        self.send_header("SID", sid)
        self.send_header("TIMEOUT", f"Second-{timeout}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_UNSUBSCRIBE(self):
        sid = self.headers.get("SID", "")
        ok = renderer.unsubscribe(sid)
        self.send_response(200 if ok else 412)
        self.send_header("Content-Length", "0")
        self.end_headers()

# ===========================================================================
# main
# ===========================================================================
def main():
    # 1) HTTP 服务（描述/控制/订阅）
    httpd = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), HttpHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f"[http] 服务启动: {renderer.base()}  （描述 {renderer.location()}）")

    # 2) SSDP：先广播 alive，再进监听循环
    try:
        send_notify_alive()
        ssdp_loop()
    except KeyboardInterrupt:
        print("\n[speaker] 正在退出…")
        send_notify_byebye()

if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台中文安全
    except Exception:
        pass
    main()
