# Worker 版中继

把被控端的直连端口（默认 21118）搬到 Cloudflare Workers 上，用一条**出站 WSS 长连接**代替公网入站端口。手机端不需要额外安装任何东西：它本来就能用 WSS 连被控端。

```
手机（Java 客户端，ID 填 wss://<worker 域名>/websocket）
        │  WSS
        ▼
Cloudflare Worker ── Durable Object（每个 key 一个实例）── 双向转发二进制帧
        ▲  WSS
        │
bridge（跑在被控端机器上）── TCP 127.0.0.1:21118 ──> rustdesk 被控端
```

为什么能直接转发：被控端的 WSS 传输本身就是「一串二进制帧走一条 socket」（`host/libs/hbb_common/src/websocket.rs` 的 `WsFramedStream`），所以中继只按帧搬字节，不需要理解 RustDesk 协议。

## 目录

| 路径 | 作用 |
| --- | --- |
| `wrangler.toml` | Worker 配置、Durable Object 绑定与迁移、`BRIDGE_KEY` |
| `src/index.js` | 入口路由：`/websocket`（手机）、`/bridge?key=…`（被控端）、`/health` |
| `src/relay-session.js` | `RelaySession`：按 key 配对两条 socket 并转发 |
| `bridge/` | 被控端的出站桥接（Rust，独立 crate，不属于 `host/` 工作区） |

## 部署

```bash
cd worker
npx wrangler deploy
```

部署前把 `wrangler.toml` 里的 `BRIDGE_KEY` 换成自己的随机串（或 `wrangler secret put BRIDGE_KEY`）。没配的话 Worker 直接 400，避免任何人都能接管你的中继。

### 已部署实例

| 项 | 值 |
| --- | --- |
| 自定义域名 | `rdworker.5704524.xyz`（`[[routes]]` + `custom_domain = true`，wrangler 自动建 DNS 与证书） |
| 健康检查 | https://rdworker.5704524.xyz/health → `ok` |
| 观看端（手机 ID 填） | `wss://rdworker.5704524.xyz/websocket` |
| 桥接 URL | `wss://rdworker.5704524.xyz/bridge?key=change-me`（`change-me` 部署前必须换掉） |

绑定自定义域名后 `workers.dev` 子域默认关闭，这是 wrangler 的行为，不是故障。该域名解析到 Cloudflare 边缘（172.67.x / 104.21.x），**境内可直连**，不需要代理；`workers.dev` 那个域名在本机是直连超时的。

然后在 Cloudflare 面板给 Worker 绑定自定义域名（或直接用 `<name>.<account>.workers.dev`），手机端 ID 填：

```
wss://<域名>/websocket
```

## 被控端

```bash
cd worker/bridge
cargo build --release        # 产物 target/release/relay_bridge.dll
copy ..\..\host\target\release\relay_bridge.dll <rustdesk.exe 所在目录>\
```

桥接**不是独立程序**：它编译成 `relay_bridge.dll`，由被控端按需加载（`LoadLibrary`），只有带 `--relay-bridge` 时才会被映射进内存：

```powershell
.\rustdesk.exe --headless --relay-bridge "wss://<域名>/bridge?key=<BRIDGE_KEY>"
.\rustdesk.exe --relay-bridge "wss://<域名>/bridge?key=<BRIDGE_KEY>" 21118   # 托盘 + 中继
```

- 与被控端的连接空闲 30 秒会被对端断开，因此桥接在被控端挂断后会立刻重拨，重拨那条连接会带上新的开局消息。
- 一次会话对应一个观看端；第二个观看端会顶掉第一个，与被控端直连端口的语义一致。
- 观看端要**在桥接连上之后**再连；Worker 只把帧转发给当时已配对的一端，早到的帧会被丢弃。
- 两条文本控制帧（只发给桥接，不进对端流量）：
  - `viewer-up`：观看端接入。桥接**丢弃当前对端连接并重拨**，逼出一次新的主机开场消息——主机每次连接只打招呼一次，
    被上一次会话用掉的开场不会重来。只有该连接还没转发过观看端数据时才重拨，避免打断正在跑的会话。
  - `viewer-down`：观看端断开（关帧、错误或链路被切断）。桥接把这条件记为“过期”，下一个 `viewer-up` 一定重拨；
    没有它，网络抖动后重连的观看端会一直等一个永远不会再来的开场消息。
- 桥接会打印中继侧的关闭码（`[bridge] relay closed by peer: code ... reason ...`），排障时先看这一行。
- 直连 Worker，不读 `HTTPS_PROXY`（避免被 shell 里的代理静默改道）。TLS 用内置的 webpki 根证书，不依赖系统证书存储（服务方式启动时读不到当前用户证书库）。

## 验证

```bash
curl -s https://<域名>/health     # ok
```

不依赖 Cloudflare 的本机回路自测：`node ../tmpscripts/relay-loopback.mjs <relay-bridge 路径>`，用 Node 内置模块起一个 WS 服务端（扮演 Worker）和一个 TCP 回显对端（扮演被控端），桥接来回转发一帧即 PASS。

## 已知边界

- 只承载 TCP/WSS：RustDesk 的 UDP 打洞与 hbbr 中继（21116/21117）不经过这里，ID 直连仍走官方服务器。
- 同一 key 同时只有一个观看端；要多端并发就按 key 派生（`?key=host-2`）。
- Worker 不校验帧内容，安全性依赖 `BRIDGE_KEY` 保密与 Cloudflare 的 TLS。
- 被控端机器需要能出站访问 `wss://<域名>`（不需要任何入站端口或路由器映射）。
- 移动网络上长连接可能被链路直接中断（客户端侧表现为 `SocketException: Software caused connection abort`，
  没有 WebSocket 关闭帧）。这不是 Worker 的错，重连依赖客户端重试；`viewer-down` 保证重连后能立刻重新握手。
