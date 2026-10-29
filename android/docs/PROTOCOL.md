# RustDesk Java 客户端协议实现文档

> 固化 `android/`（纯 Java viewer）里散落的协议结论，逐条给出源码来源。标「未验证」的条目表示
> 未在真实环境跑通，勿当既定事实。上游对照只读：`host/libs/hbb_common`、`host/libs/base/protos`、
> `host/src`；下文的「上游 `src/...`」「`libs/...`」都相对仓库根的 `host/`，别和 Java 包里的
> `com/rustdesk/java/host/` 混为一谈。

## 目录

1. [帧格式](#1-帧格式) 2. [加密层](#2-加密层) 3. [hbbs 注册与打洞](#3-hbbs-注册与打洞)
4. [中继](#4-中继) 5. [两级身份验签](#5-两级身份验签) 6. [登录](#6-登录) 7. [输入](#7-输入)
8. [视频](#8-视频) 9. [踩过的坑清单](#9-踩过的坑清单)

## 1. 帧格式

帧头低 2 位即头字节数减一，读端只看 byte 0 就知道该读几个字节：

```
value = (payloadLen << 2) | (headerBytes - 1)   // 小端写出，tag = value & 0x3，len = value >>> 2
```

| 负载长度 | 头字节 | 示例（len → 头） |
|---|---|---|
| `0..0x3F` | 1 | `0x3F` → `fc`；空负载 → `00` |
| `0x40..0x3FFF` | 2 | `0x40` → `01 01` |
| `0x4000..0x3F_FFFF` | 3 | — |
| `0x40_0000..0x3FFF_FFFF` | 4 | `MAX` → `fd ff ff ff` |

`MAX_FRAME_LENGTH = 0x3FFF_FFFF`（4 字节头把 30 位留给长度）。
来源：net/FrameCodec.java:17-27；上游 libs/hbb_common/src/bytes_codec.rs:8,129-141

- 编码端按长度选头宽，一次写「头 + 负载」并 `flush()`；`send()` 为 `synchronized`，同一 socket
  上的消息不能交错。来源：net/FrameCodec.java:29-47；net/StreamTransport.java:36-39
- 解码端在「一个头字节都没读到时」返回 `null`（对端干净关闭），读一半 EOF 抛 `EOFException`，上层
  据此区分正常结束与传输中断。来源：net/FrameCodec.java:50-82；net/Transport.java:9-13
- 空负载合法（`len=0` → `00`）。Rust 握手失败时正是发一个**空 `Message`** 表示「不加密」，这条路
  径不能当错误。来源：net/FrameCodec.java:22-27；上游 bytes_codec.rs:185-194
- 上游另有 `raw` 模式、`max_packet_length`、256 KiB 预分配上限；本实现无后两项（编码端只校验
  `MAX_FRAME_LENGTH`，解码端按头声明直接开数组）。来源：net/FrameCodec.java:29-47,71-82；bytes_codec.rs:6,46-76

```
Socket ── StreamTransport ──【帧】── SecureTransport ──【明文】── protobuf
              FrameCodec                     Encrypt(secretbox)
```

`SecureTransport` 只封**负载**，帧头保持明文，对端在还没密钥时也能先量出本帧长度（上游
`Stream::send_raw`/`next` 同序）；MAC 失败被翻成 `IOException`，不会被误当成对端关闭。
来源：net/SecureTransport.java:5-37；上游 bytes_codec.rs:90-117

## 2. 加密层

### 2.1 secretbox 布局（tag 在前）

```
wire      = tag(16) || ciphertext(n)          总长 16+n
keystream = XSalsa20(key, nonce) 的前 32+n 字节
tag       = Poly1305(key = keystream[0..32], msg = ciphertext)
plaintext[i] = ciphertext[i] ^ keystream[32+i]      // 即 libsodium crypto_secretbox_easy 布局
```

来源：crypto/Sodium.java:26-30,159-183,190-219；net/EncryptCipher.java:15-30

`crypto_box`（握手期封会话密钥）同布局，key 换成 `HSalsa20(X25519(ownSk, peerPk), 0^16)`；
BouncyCastle 未暴露 HSalsa20，故本地实现 20 轮 Salsa 核心，取 `x[0],x[5],x[10],x[15],x[6..9]`。
来源：crypto/Sodium.java:107-149,271-307

### 2.2 nonce 计数器

24 字节 nonce 的**前 8 字节**是小端 u64 计数器，其余全零：
`nonce[i] = (byte)(seq >>> (8*i))`（`i = 0..7`）。

每个方向**独立计数**、从 0 开始、**先自增再用**（首帧计数为 1）；`enc`/`dec` 均 `synchronized`，因
计数器是可变状态；握手封密钥用**全零 nonce**，与流计数器无关。
来源：crypto/Encrypt.java:27-34,109-115,128-138,216-222；上游 libs/hbb_common/src/tcp.rs:248-252,398-402

两个易错点：① **长度 ≤ 1 的帧原样返回且不推进计数器**（上游 `if bytes.len() <= 1 { return
Ok(()) }`），对端发空 `Message` 表示不加密，漏掉这条会导致之后所有帧解不开；② MAC 失败必须抛异常、
不返回明文，且用定时安全比较。来源：crypto/Encrypt.java:132-134；crypto/Sodium.java:190-219（:207）

### 2.3 v1 分向子密钥 KDF

`kx_version=0`（缺省/老对端）是一份密钥双向共用；`=1` 时派生两把子密钥，initiator 与 responder
各用其一：

```
subkey = BLAKE2b(key = key32, out = 32,
                 "rdkx-spl" || [direction] || le32(advertised) || le32(picked)
                 || initiatorPk(32) || responderPk(32))      // direction: 1=initiator, 2=responder
```

initiator 用 `(send=1, recv=2)`，responder 反之；transcript 绑定临时公钥与协商版本，任一处不一致
即首帧 MAC 失败。来源：crypto/Encrypt.java:24-34,76-95,194-214,224-231；tcp.rs:44-59,348-380

`kxVersionFor` 必须能降级且先按 u32 处理：`min(advertised & 0xffffffffL, 1)`。实测对端可能给
`kx_version=0`，此时落回 v0；自测钉死 `(0)=0`、`(1)=1`、`(3)=1`、`(0xffffffff)=1`，另有 5 组线上
向量（v0 一帧 + 4 把 split 子密钥）是「KDF 抄对了」的唯一硬证据：`key=0x11*32`、
`advertised=1,picked=1` 时 initiator send key 应为 `c189ec6e…4e0ecf`。
来源：crypto/Encrypt.java:164-170；crypto/SelfTest.java:52-105

## 3. hbbs 注册与打洞

```
→ [收] KeyExchange{keys=[signedPk], version, signed_params}      ← 服务器先说
→ [发] KeyExchange{keys=[ourPk, sealedKey], version=picked}      ── 此后全部加密 ──
→ [发] RegisterPk{id, uuid, pk=签名公钥, no_register_device=true}  → [收] RegisterPkResponse
→ [发] PunchHoleRequest{id=对端ID, version, conn_type=DEFAULT_CONN} → [收] PunchHoleResponse
→ （无直连时）[发] RequestRelay{id, uuid, relay_server, secure=true} → [收] RelayResponse{uuid}
```

来源：net/RendezvousClient.java:75-93,101-130,151-186

### 3.1 KeyExchange 必须先行

**hbbs 连上先发 `KeyExchange`，不应答会被直接断连。** 上游 `key_exchange()` 收到首帧只认它，验签
失败或类型不对即 false，`secure_tcp_required` 报 "the rendezvous server did not complete the key
exchange" 并放弃连接；在此之前发出的明文 `RegisterPk` 会被当成协议违规。
来源：上游 src/common.rs:2259-2326,2341-2350；net/RendezvousKeyExchange.java:16-23

Java 侧要点：首帧不是 `KeyExchange`（老服务器直接进协议）→ **保留明文流**返回不报错；`keys` 必须
恰好 1 个，用部署公钥验 `keys[0]` 的 Ed25519 签名，验出的 32 字节即服务器 X25519 临时公钥；
`serverPk[31] & 0x80 != 0` 或 `signed_params` 非空时必须校验 `signed_params`（签名体为
`"rdkx-params" || KxParams{pk, version}` 且须与本帧一致，防同密钥签的其它消息被重放）；回包
`keys=[ourPk, sealedKey]` 用全零 nonce 封 32 字节流密钥，随后按 initiator 建 v1 分向密钥（服务器
是 responder）。来源：net/RendezvousKeyExchange.java:33-102；rendezvous.proto:236-251；common.rs:2276-2293

### 3.2 RegisterPk / RegisterPeer

`RegisterPk{ id = 本机ID, uuid = 16 字节随机, pk = Ed25519 签名公钥, no_register_device = true }`

`pk` 必须是**签名公钥**，填成 box 公钥则对端验不过身份（第 5 节）。`OK`/`NOT_SUPPORT`/
`NOT_DEPLOYED` 视为成功（后两者表示服务器未开启该能力，连接仍可用），`ID_EXISTS`/`TOO_FREQUENT`
等抛异常；读响应期间服务器会插播 `ConfigUpdate`/`TestDelay`，这类帧要跳过继续读。
来源：net/RendezvousClient.java:101-130；rendezvous.proto:113-134

`RegisterPeer{id, serial}` 是**被控端把自己发布到服务器**用的；viewer 只 `RegisterPk` 就够，之后
在同一条连接上直接问 `PunchHoleRequest`（代码里的 `registerPeer()` 是 private 且主流程不调用）。
来源：net/RendezvousClient.java:86-88,132-149；rendezvous.proto:15-29

### 3.3 PunchHole 请求与响应

请求三个字段有意义：`id`（对端 ID）、`version`（本端版本串，如 `"1.5.1"`）、
`conn_type = DEFAULT_CONN`。响应：

| 字段 | 用途 |
|---|---|
| `socket_addr` (bytes) | 直连地址，`AddrMangle.decode` 解；空 = 无直连 |
| `pk` (bytes) | 服务器签的对端身份 blob（第 5 节，**不是** box 公钥本体） |
| `failure` | `ID_NOT_EXIST`/`OFFLINE`/`LICENSE_*`，仅 `socket_addr` 为空时取 |
| `other_failure` / `relay_server` | 字符串补充原因 / 中继地址 |

`AddrMangle` 只解码：`tm = (n>>17) & 0xFFFFFFFF`、`ipRaw = ((n>>49) - tm) & 0xFFFFFFFF`（再按小端
组装 IPv4）、`port = (n & 0xFFFFFF) - (tm & 0xFFFF)`；长度正好 18 字节时按「16 字节 IPv6 + 小端
u16 端口」解，其它超长返回 `null`。
来源：net/RendezvousClient.java:151-186；net/AddrMangle.java:22-64；rendezvous.proto:31-47,136-157

## 4. 中继

中继是**两次 `RequestRelay`**，目标服务器不同：

| 次数 | 目标 | 目的 | uuid 来源 |
|---|---|---|---|
| 1 | hbbs:21116（已加密的 rendezvous 连接） | 登记配对、分配 uuid | 本端 `UUID.randomUUID()` |
| 2 | hbbr:21117（新开裸 TCP） | 把连接挂到配对另一半 | 第 1 次回包里的 uuid |

来源：net/RendezvousClient.java:188-215；net/RelayClient.java:25-46

- hbbs 那条带 `secure=true`；hbbr 那条带 `licence_key=""` 与 `conn_type=DEFAULT_CONN`（上游
  `create_relay` 只填 `licence_key/id/uuid/conn_type`）。
  来源：net/RendezvousClient.java:192-197；net/RelayClient.java:37-42；上游 src/client.rs:1899-1907
- `RelayResponse.uuid` 可能与请求值不同（服务器有权改写），**要用服务器回的那个**去连 hbbr，回包为
  空才退回本地值；`refuse_reason` 非空即失败。来源：net/RendezvousClient.java:206-213
- hbbr 固定比 hbbs 大 1 端口（21117），故 `relay_server` 常不带端口；`hostOf`/`portOf` 只在仅一个
  冒号且不含 `]` 时拆，IPv6 字面量整条保留。连接参数同直连：connect 10s、`setTcpNoDelay(true)`、
  `setSoTimeout(0)`（会话长时间无帧正常，不能设读超时）。
  来源：net/RelayClient.java:20-21,29-35,48-69；net/Session.java:159-163

hbbr 收到 `RequestRelay` 后**不再回确认帧**：连接直接变成对端流，下一个字节就是另一半的
`SignedId`。所以 `open()` 发完即返回，由 `PeerHandshake` 读首帧，**不要在中继连接上等
`RelayResponse`**（hbbr 只在拒绝时回包）。排障：首帧不是 `Message` 或立刻 EOF，通常说明 uuid 用错
（用了本地生成的那个而非服务器回包里的）。
来源：net/RelayClient.java:11-17,43-45；net/RendezvousClient.java:206-212；net/PeerHandshake.java:60-71；上游 src/client.rs:1882-1908

中继腿在传输层**不套 `SecureTransport`**：`open()` 返回的仍是裸帧流，加密完全由随后的对端握手
（`SignedId` → `PublicKey`）建立，故中继与直连共用同一段握手代码。
来源：net/RelayClient.java:36-45；net/Session.java:156-191

## 5. 两级身份验签

**最容易搞错的一节。** 同一个 `IdPk{pk}` 出现两次，`pk` 含义**完全不同**，取决于谁签的：

```
① 服务器签的 IdPk（PunchHoleResponse.pk / RelayResponse.pk）
   signer  = 部署 Ed25519 公钥（RS_PUB_KEY）；IdPk.pk = 对端的 **Ed25519 签名公钥** ← 用来验 ②
② 对端自己签的 IdPk（它发来的 Message.signed_id.id）
   signer  = ①里拿到的对端签名公钥；      IdPk.pk = 对端的 **Curve25519 box 公钥** ← 密钥封给它
```

服务器只认识注册时那对 ed25519 公钥，它替对端签「我是这个 ID，这是我的 box 公钥」—— 但那是
**服务器**在担保、服务器自己就能伪造，所以对端还要用**它自己的私钥**重签一份。
来源：session/PeerIdentity.java:20-33；上游 src/client.rs:1704-1751、src/common.rs:2372-2393

| 步骤 | 代码 | 验什么 / 得到什么 | 失败后果 |
|---|---|---|---|
| ① | `PeerIdentity.verify(addr.peerPk, rsKey)` | 服务器签名 → 对端**签名公钥** | 报 "rendezvous identity rejected"，`peerSigningKey=null` |
| ② | `PeerHandshake.connect(raw, peerId, rsKey, peerSigningKey)` | 用①的密钥验 `SignedId.id` → 对端**box 公钥** | 抛 "peer identity rejected" |

来源：net/Session.java:174-187；session/PeerHandshake.java:80-91

降级：① 失败时不能直接放弃 —— 老对端请服务器代签 `SignedId`，此时退回用部署公钥验 ②
（`peerSigningKey == null` 分支）；首帧完全没有 `SignedId` 时也回一个空 `Message`，之后按**明文**继续
（`Security.UNSECURED`），与上游 `sign_pk.is_none()` 分支一致。
来源：session/PeerHandshake.java:27-32,53-60,73-78；上游 src/client.rs:1717-1727

封会话密钥：

```java
streamKey = 32 随机字节
sealedKey = crypto_box(streamKey, nonce = 0^24, peerBoxPk, ourEphemeralSk)
回包 PublicKey{ asymmetric_value = ourEphemeralPk, symmetric_value = sealedKey,
                kx_version = kxVersionFor(identity.kxVersion) }
```

必须封给**②里的 box 公钥**（`identity.publicKey`），不是①的签名公钥，否则得到一条永远解不开的
流；`IdPk.pk` 必须正好 32 字节、`id` 必须等于请求的 ID（不等即 "peer identity mismatch"，防服务器
把 A 的身份塞给 B）；`IdPk.kx_version` 缺省 0，故 `kxVersionFor(0)=0` 落回 v0。
来源：session/PeerHandshake.java:88-114；session/PeerIdentity.java:34-40,56-79；rendezvous.proto:4-13

## 6. 登录

被控端在加密流建立后先发 `Hash{salt, challenge}`（两个都是 **string**），viewer 据此算摘要。

### 6.1 密码是两段 sha256

```
h1     = sha256( password_utf8 || salt_utf8 )
digest = sha256( h1 || challenge_utf8 )          // LoginRequest.password = digest(32 bytes)
```

必须**两层**：漏掉「`pw || salt`」那层直接算 `sha256(pw||salt||challenge)`，服务器回
`"Wrong Password"`（实测踩过）。`salt`/`challenge` 按 UTF-8 取字节，不是 hex/base64。服务器对照：
`validate_password_plain` 算 `h1`，`verify_h1` 再算 `sha256(h1||challenge)` 并定时安全比较。
来源：net/Session.java:280-301；上游 src/server/connection.rs:2523-2544

### 6.2 username 必须是对端 ID

```java
LoginRequest{ username = peerId,        // ← 对端自己的 ID，不是我们的
              my_id = 本机ID, my_name = "rustdesk-java", my_platform = "Android",
              version = "1.5.1", password = digest, session_id = 32 位时间戳 }
```

服务器用 `lr.username != Config::get_id()` 确认「你连的是我」，不等就回 `"Offline"` 并断连，所以
填错表现为 `"Offline"` 而不是密码错误 —— 这个区分很好用。
来源：net/Session.java:288-299；上游 src/server/connection.rs:3014-3019、src/client.rs:120

### 6.3 协议细节

- `LoginRequest` **没有 `conn_type` 字段**：连接类型在 `oneof union`（`file_transfer`/
  `port_forward`/`view_camera`/`terminal`），默认会话不填即 `DEFAULT_CONN`；`conn_type` 只出现在
  `PunchHoleRequest`、`RequestRelay`。来源：message.proto:68-87；rendezvous.proto:31-47,164-176
- `Hash.challenge` 是 `string`（字段 2），`Hash.salt` 也是 `string`（字段 1），没有对应的 bytes
  字段。来源：libs/base/protos/message.proto:328-331
- 响应是 `oneof`（`error` / `peer_info`）：先判 `hasError()`，再从 `peer_info.displays[0]` 取
  `width/height` 交给解码器 —— **0×0 的 format 会让部分厂商解码器拒绝 configure**。登录前收到的
  `TestDelay` 要原样回送，否则有的对端认为链路不健康；2FA/屏幕确认本实现只把错误串报给 UI。
  来源：net/Session.java:254-259,303-322；message.proto:144-150,38-48；../README.md

## 7. 输入

### 7.1 鼠标 mask

`MouseEvent.mask` 一个字段塞两件事：`mask = (buttons << 3) | eventType`；服务器侧
`buttons = mask >> 3`、`type = mask & 0x7`。

| eventType | 值 | 说明 |
|---|---|---|
| `TYPE_MOVE` | 0 | 移动，buttons 通常 0 |
| `TYPE_DOWN` | 1 | 按下 |
| `TYPE_UP` | 2 | 抬起 |
| `TYPE_WHEEL` | 3 | 滚轮，增量放 x/y 字段（**sint32**，可负） |
| `TYPE_TRACKPAD` | 4 | 触控板 |

来源：session/InputMessages.java:20-32,34-61；上游 src/server/input_service.rs:835,1290,1474

`MouseEvent.x/y` 是 `sint32`（zigzag），负坐标/负滚动量按 sint 编码即可；写进 `int32` 会变成大正数。
来源：libs/base/protos/message.proto:192-197

### 7.2 文本与按键

文本输入不是逐键 `chr`，而是一条 `KeyEvent.seq`（字段 6，string）：服务器交给键盘后端做「按字符串
输入」，IME/组合字符也走这条路；五个 union 成员一次只能填一个。
来源：session/InputMessages.java:63-71；message.proto:293-310

| 场景 | 消息 |
|---|---|
| 控制键按下/抬起 | `KeyEvent{control_key, down=true/false, press=false}` |
| 点按（一次 down+up） | `KeyEvent{control_key, press=true}`，`down` **不填** |
| 单字符 | `KeyEvent{unicode=c}` / `KeyEvent{chr=code}`（平台键码） |
| win2win 热键 | `KeyEvent{win2win_hotkey = ((vk & 0xFFFF) << 16) | (unicode & 0xFFFF)}` |

`win2win_hotkey` 高字是虚拟键码、低字是码位，服务器拆开去模拟「带修饰键的字符」。控制键按名字映射
（`Return`/`Tab`/`Escape`/…/`F12`/`Shift`/`Control`/`Alt`/`CapsLock`），映射表只读。
来源：session/InputMessages.java:73-165；message.proto:293-310

> 未验证：`TYPE_TRACKPAD`(=4) 的 x/y 语义 —— 代码只定义常量，未构造也未抓包确认。
> 来源：session/InputMessages.java:24

## 8. 视频

`Message.video_frame` 是 `VideoFrame`，其 `oneof union` 里编码与裸像素并列：

| oneof 成员 | 字段号 | 内容 | MIME |
|---|---|---|---|
| `vp9s` | 6 | `EncodedVideoFrames` | `video/x-vnd.on2.vp9` |
| `rgb` | 7 | `RGB{compress}` 裸像素 | — |
| `yuv` | 8 | `YUV{compress, stride}` | — |
| `h264s` | 10 | `EncodedVideoFrames` | `video/avc` |
| `h265s` | 11 | `EncodedVideoFrames` | `video/hevc` |
| `vp8s` | 12 | `EncodedVideoFrames` | `video/x-vnd.on2.vp8` |
| `av1s` | 13 | `EncodedVideoFrames` | `video/av01` |

字段号是**乱序**的（6,7,8,10,11,12,13），不要按 oneof 列表顺序当 1..7；`VideoFrame.display`(14)
指明第几块屏。每路内是 `EncodedVideoFrames{ repeated {data, key, pts} }`：一条消息可带多帧，
`key=true` 是 IDR/关键帧，`pts` 为微秒。
来源：libs/base/protos/message.proto:4-10,25-36；codec/VideoDecoder.java:24-28

客户端按 `getUnionCase()` 选 MIME，`rgb`/`yuv` 直接丢；第一批帧前 `setMime(mime, displayWidth,
displayHeight)` 带上登录阶段拿到的真实分辨率，再逐帧 `queueFrame(data, key, pts)`。`VideoDecoder`
在 MIME 或尺寸变化时释放并重建 MediaCodec，同时**清空待解队列**（旧配置的帧只会解出花屏）；
`KEY_MAX_WIDTH/HEIGHT` 取 `max(2*宽,1920)`/`max(2*高,1080)` 走自适应播放，尺寸变化不必每次重建。
来源：net/Session.java:325-372；codec/VideoDecoder.java:76-88,123-140

实测与未验证：

- **VP9 1920×1200 的流可被 ffmpeg 正常解码**（实现期间实测：把抓到的 `data` 直接喂
  `ffmpeg -f vp9` 得到 YUV，分辨率与 `displayWidth/Height` 一致）。故「Android 收到的帧就是标准
  VP9 比特流、没有私有封装」可当结论用；**但仓库内没有留下复现脚本或样本**，重跑需重新抓包。
- 实跑时对端发的是 `vp9s`；H.264/H.265/VP8/AV1 四路分支只是照 proto 写的。未验证：多显示器时
  `VideoFrame.display` 取值与选屏（当前只处理 `displays[0]`，其余 display 的帧也解到同一个 Surface）。
  来源：net/Session.java:308-318,329-357；codec/VideoDecoder.java:32-33；../README.md

### 8.1 帧率、传输分辨率与画质（主控端 → 被控端）

用既有消息控制被控端出帧，**不改变被控端桌面分辨率**：

| 参数 | 消息 | 被控端行为 |
| --- | --- | --- |
| 帧率 | 登录 `LoginRequest.option.custom_fps`；运行中 `Misc.option.custom_fps` | Rust host 写入 `VideoQoS`，是上限而非保证值 |
| 传输分辨率 | `OptionMessage.custom_video_scale`（1–100 百分比，0=未设置） | host 把编码器宽高按比例缩小后编码发送；捕获与桌面分辨率不变 |
| 画质 | `OptionMessage.image_quality`（Low/Balanced/Best） | host 用它设定位率/质量比（0.5 / 0.67 / 1.5）；帧率不再由画质决定 |

- 手机端：`session/VideoSettings.java` 构造消息；`Session.setVideoFps` / `Session.setVideoScale` / `Session.setVideoQuality` 发送。重连登录后会自动重放比例与画质（登录包 + `handleLoginResponse`）。
- QoS：请求的 fps 是目标，**任何分辨率、任何画质下，实际帧率不低于该值的 75%**（`fps_floor`）。链路变差时优先降位率/画质，不再把帧率降到 75% 以下；启动即从该下限开始。
- 切换画质或 fps 会清空当前目标，让控制器重新收敛。
- host 端：`VideoQoS` 按 viewer 记录比例并取最小值（多路观看时以最省带宽的一路为准）。只有软件编码器（VP8/VP9/AV1）应用缩放；H.264/H.265 的 HWRAM/VRAM 路径与 I444 保持原生尺寸（缩放输入尺寸会破坏编码器约定）。
- 缩放实现：捕获帧先在原生尺寸转 I420，再用 libyuv `I420Scale` 缩到目标尺寸（`scrap/common/convert.rs`）；只在**下采样**时启用。
- 比例变化通过现有 `SWITCH` 机制重建编码器；帧率与比例都是“请求值”，实际受带宽/QoS/平台限制。
- 桌面分辨率仍由 host 自己的显示设置决定；本功能不再发送 `Misc.change_display_resolution`。
- 静止画面保底：静态刷新的重复帧爆发结束后，被控端**每秒至少重编一帧**（`IDLE_KEEPALIVE_INTERVAL`），避免画面不变时对端把静默当成链路失效。

### 8.2 长连接保活与重连

- 心跳复用 `TestDelay`：主控端每 5 s 发 `from_client=true` 的探测；接收方回送同一条消息。收到 `from_client=true` 只刷新入站时间、不再回送（避免乒乓）；`from_client=false` 的 host 探测仍原样回送。
- 看门狗：登录后启动守护线程，25 s 无任何入站消息即关闭底层 transport，由重连循环接管。
- 重连：首次连接最多 3 次（1.5 s×n）；成功登录过之后无限重连，退避 1/2/4/8/15 s + 0–250 ms 抖动。登录被拒、收到 `Misc.close_reason`、协议错误超限才算终止。
- 发送只有一条线程：`Session.enqueue` 把消息放进容量 256 的 outbox，由绑定当前 transport 的 sender 线程写出。`net.SecureTransport.send` 是 synchronized 的（加密推进 nonce，必须与写帧原子），否则并发写会在对端表现为 MAC 失败并断链。
- WebSocket 隧道：OkHttp `pingInterval=15s`；直连 TCP 开启 `SO_KEEPALIVE`。
- 安卓被控端 `HostSession.receiveLoop` 同样回送 `TestDelay`，避免主控端在静止画面时误判掉线。


## 9. 踩过的坑清单

### 9.1 BouncyCastle `verifySignature()` 只吃 64 字节签名

症状：验签永远失败，换已知正确样本也失败。原因：把 libsodium 风格 `sig(64)||msg` 整块传进
`verifySignature()`；BC 的 Ed25519 是**分离式**验签，消息走 `update()`，它只收 64 字节签名。

```java
verifier.update(Arrays.copyOfRange(signed, 64, signed.length), 0, msgLen);
verifier.verifySignature(Arrays.copyOfRange(signed, 0, 64));   // ← 只传 64 字节签名
```

来源：crypto/Ed25519.java:87-106（:97-98 注释即此坑）

### 9.2 hbbs 先发 `KeyExchange`，不应答直接断连

症状：`RegisterPk` 发出后无响应、连接被关。原因：服务器发完 `KeyExchange` 就等回包，期间收到明文
消息视为违规。处置：`resolve()` 第一步就 `RendezvousKeyExchange.secure()`。
来源：net/RendezvousClient.java:69-75,82-85；net/RendezvousKeyExchange.java:16-23

### 9.3 四个 proto / 命名陷阱

- **`LoginRequest` 没有 `conn_type` 字段**：`.setConnType(...)` 编译不过，连接类型靠 `oneof union`
  子消息表达。来源：libs/base/protos/message.proto:68-87
- **`Hash.challenge` 是 string 不是 bytes**：取字节必须显式 `getBytes(UTF_8)`，否则摘要永远不对。
  来源：message.proto:328-331；net/Session.java:284-285
- **顶层消息名是 `RendezvousMessage` 而非 `Message`**：前者是 rendezvous 通道顶层，后者是**会话
  通道**顶层，两套别混用。来源：net/RendezvousKeyExchange.java:12-14；rendezvous.proto:283-310
- **对端可能 `kx_version=0`**：老对端 `IdPk.kx_version` 缺省 0，按 v1 派生后首帧必然 MAC 失败，须
  落回 v0；`kxVersionFor` 取 `min(advertised,1)` 且先做无符号处理。
  来源：crypto/Encrypt.java:164-170；crypto/SelfTest.java:100-103

### 9.4 两个错误串正好区分身份错与密码错

| 服务器回 | 含义 | 查 |
|---|---|---|
| `"Offline"` | `LoginRequest.username` ≠ 对端 ID | 6.2 |
| `"Wrong Password"` | 摘要算法不对（漏 `pw‖salt` 层最常见） | 6.1 |
| `"Empty Password"` | 对端没设密码而本端发空 | — |

来源：net/Session.java:288-301；上游 src/client.rs:115-120、connection.rs:3014-3019

### 9.5 其它零碎

- `SecureTransport` **只封负载**（连帧头一起加密对端就没法定长，第 1 节）；`Encrypt.dec` 对 ≤1 字节
  帧放行且不推进计数器，漏了它「空 `Message` 表示不加密」的握手会把计数器错开一位。
  来源：crypto/Encrypt.java:132-134
- hbbr 不回确认帧，别等 `RelayResponse`（4）；`PunchHoleResponse.pk` 不是 box 公钥，拿它封密钥对端
  永远解不开（5）；`signed_params` 也不能省 —— 同一把服务器密钥还签 `IdPk`，不校验 `"rdkx-params"`
  域前缀就能把别的签名消息重放进 key exchange。来源：net/RendezvousKeyExchange.java:63-82

### 9.6 本文档的「未验证」条目

| 条目 | 为什么未验证 |
|---|---|
| `TYPE_TRACKPAD` 的 x/y 语义 | 只有常量，未构造/抓包（7.1） |
| H.264 / H.265 / VP8 / AV1 收流 | 只收到过 VP9，其余分支仅按 proto 实现（8） |
| 多显示器 `VideoFrame.display` 选屏 | 只处理 `displays[0]`，未测多屏对端（8） |
| VP9 1920×1200 + ffmpeg 的抓包样本 | 结论来自实现期间实测，仓库内无脚本/样本（8） |

*范围：只覆盖 viewer 主控端；被控端编码/注入路径仍在推进。* 来源：../README.md

## 11. 实现现状

> **快照**：2026-10-26 14:30 的工作树（当时安卓源码目录还没提交，`git status` 里整棵目录仍是 `??`）。
> **方法**：逐条读源码 + 本轮两次实跑：`tools\selftest\run-selftest.ps1`（10 套 / 347 checks）与
> 真实服务器端到端 `HostEndToEnd`。**行号只对这次快照有效**：host 侧在我核实期间一直在改
> （`host/HostAgent.java` 14:16→14:28 从 638 行长到 857 行），引用前先按函数名 grep 一次。
>
> 路径：Java 源码相对 `android/app/src/main/java/com/rustdesk/java/`，套件/脚本相对 `android/`
> （本文档在 `android/docs/`）。文中 `host/HostSession.java:363` 即
> `android/app/src/main/java/com/rustdesk/java/host/HostSession.java` 第 363 行。
>
> 状态：**已实现** = 能从 UI 或 JVM 调用走到协议对面；**部分** = 代码在，但差一段接线或能力有缺口；
> **未实现** = 没有这条路径。证据强度：**强** = 本轮跑过且有输出，或源码直读即可判定；
> **中** = 代码被引用、有历史实测记录，但仓库里没有可复跑产物；**弱** = 代码在但没有引用者或没有真机验证。

### 11.1 传输与身份（两端共用）

| 能力 | 状态 | 代码位置 | 证据强度 | 依据 / 说明 |
|---|---|---|---|---|
| 帧格式 | 已实现 | `net/FrameCodec.java:29,50` | 强 | 所有传输层都走它，套件里每次收发都是帧往返 |
| secretbox + v1 分向子密钥 | 已实现 | `net/SecureTransport.java:11,22-33`、`net/EncryptCipher.java`、`crypto/Encrypt.java:164-170` | 强 | 只封负载；`kxVersionFor` 负责对端 `kx_version=0` 的回落；真实 hbbs 与套件都过 |
| host 注册 hbbs | 已实现 | `host/HostRegistration.java:394-403`（`RegisterPk`）、`:89` 与 `:241-244`（每 10 s 重发续期）、`:331-345`（`PunchHole` → `PunchHoleSent`） | 强 | 本轮真实服务器 1/1 跑通（11.5）；历史「3/3 punch」没有产物 → 未验证 |
| `RegisterPeer` | 未实现（有意不发） | `net/RendezvousClient.java:133-146` 只有定义、**无调用者**；原因见 `host/HostRegistration.java:36-42` | 强 | hbbs 的 `handle_tcp` 没有该分支，TCP 上发它就断连。注意：**本仓库没有 UDP 传输**，所以「只有 UDP 路径用它」在这里不成立——Java 端从不发它；内网地址改由 `FetchLocalAddr`/`LocalAddr` 广告（`HostRegistration.java:346-355`） |
| 打洞 / 内网直连地址 | 已实现 | `net/RendezvousClient.java:152-162`、`host/HostRegistration.java:346-355,465-489` | 强 | 本轮拿到的直连地址 `192.168.66.61:49387` 就是 host 在 `LocalAddr` 里报的端口 |
| 中继 | 已实现（本轮未命中） | `net/RelayClient.java:25-47`、`net/RendezvousClient.java:180-198` | 中 | 代码完整，`HostEndToEnd` 里有 relay 分支；本轮走内网直连，relay 腿没有实跑证据 |
| 两级身份验签 | 已实现 | `session/PeerHandshake.java:23`、`session/PeerIdentity.java`、`host/HostSession.java:838`、`session/Session.java:218-219` | 强 | 服务器签的 pk（109 B）→ 对端签的 box 公钥；`PeerHandshakeInteropTest` 31 checks 全绿，本轮真实 hbbs 的 pk 也验过 |
| 登录摘要 | 已实现 | `session/Session.java:312-353`、`host/HostSession.java:392-429` | 强 | `sha256(sha256(pw‖salt)‖challenge)`；错 ID 回 `Offline`、错密码回 `Wrong Password`（9.4） |

### 11.2 viewer（主控端）

| 能力 | 状态 | 代码位置 | 证据强度 | 依据 / 说明 |
|---|---|---|---|---|
| 视频接收 | 已实现 | `session/Session.java:275-305,357-408` | 中 | 真实对端发来的 VP9 流喂 `ffmpeg` 能解出正确分辨率（§8），但仓库里没有样本/脚本可复跑 |
| 解码 + 渲染 | 部分 | `codec/VideoDecoder.java:22,75-140`、`ui/RemoteActivity.java:277` | 弱 | MediaCodec → `Surface(texture)` 的链路齐全，但**没有真机 logcat / 截图产物**；能不能出画面取决于 vendor decoder → 未验证 |
| 鼠标 / 键盘 / 文本 | 已实现 | `session/InputMessages.java`、`ui/KeyEventMapper.java`、`ui/RemoteActivity.java:238` | 中 | 本轮端到端发了 move/down/up/text，会话存活；真机手测无产物留存 |
| 手势 | 已实现 | `ui/RemoteView.java:28,131,218`、`ui/ClickButtonsOverlay.java` | 中 | 平移/双指缩放（`PINCH_GAIN=1.6`）/双指滚动→滚轮 notch/长按拖选/常驻左右键；无自动化 |
| 剪贴板（上/下） | 已实现 | `ui/RemoteActivity.java:348,465`、`session/Session.java:410-422` | 中 | 轮询本机剪贴板发 `Clipboard{Text}`，下行写回；`multi_clipboards` 取第一项 |
| 剪贴板 zstd 压缩 | 未实现 | `session/Session.java:415-417` | 强 | 收到 `compress=true` 只记一条日志然后丢弃 |

### 11.3 host（被控端）

| 能力 | 状态 | 代码位置 | 证据强度 | 依据 / 说明 |
|---|---|---|---|---|
| SignedId 先行 | 已实现 | `host/HostSession.java:363`（首帧）、`:838`（签名） | 强 | `PeerHandshakeInteropTest` 断言「host 线上第一帧就是 signed_id」且 `IdPk.pk` == box 公钥 |
| 登录（挑战 + 摘要比对） | 已实现 | `host/HostSession.java:392-429,471-498` | 强 | 同上套件断言 `Hash` → `LoginResponse` 无 error，错密码/错 ID 两条负例也断言 |
| 推流（协议层） | 已实现 | `host/HostSession.java:501-525,845-862` | 强 | 套件里 viewer 收到 3 条 `video_frame(h264s)`，`key`/`pts` 也在；`HostSessionTest` 49 checks 覆盖会话生命周期 |
| hbbs 注册 | 已实现 | 见 11.1 | 强 | 本轮真实 1/1 |
| 采集（MediaProjection + ImageReader） | 部分 | `host/ScreenCaptureService.java:51,125,352,413` | 弱 | 代码完整（前台服务类型、紧凑 RGBA_8888）；**未真机验证** |
| 编码（H.264）+ EGL 上传 + 帧源 | 部分 | `host/HostCaptureCodec.java:31`、`host/GlFrameUploader.java:27`、`host/HostFrameSource.java:24,148` | 弱 | 三个类齐全，`HostFrameSource.nextFrame` 真的从编码器 drain；**未真机验证** |
| 视频接线 | 部分（差最后一段） | `host/HostAgent.java:137-143`（转发 `FrameLink.source.nextFrame`）、`:427`（反射加载 `HostFrameSource`）、`:350`（`onCapturedFrame` 入口） | 强 | `HostAgent` 已经用反射接上 `HostFrameSource`（反射是为了让该类在纯 JVM 上仍能加载）。**但** `ScreenCaptureService.setFrameListener`（`:297`）至今没有任何调用者，也没有任何代码调 `agent.onCapturedFrame` → 采集帧到不了编码器，登录后 viewer 仍然收不到视频。实测佐证：本轮 e2e 日志里 host 打完 `streaming to ...` 之后再无帧 |
| 键盘注入 | 部分 | `host/InputInjector.java:133-148`（全局动作）、`:107-126`（`ACTION_SET_TEXT`）、`host/HostSession.java:594-665` | 弱 | AccessibilityService 只能做 4 个全局动作（Back/Home/Recents/Notifications）+ 往聚焦节点塞文本；任意键码、绝对指针都不可达（`InputInjector.java:26-29`）。「只有 Home 能真正落地」这个说法**未验证**——代码给了 4 个动作，真机哪一个生效没有 logcat 产物 |
| 鼠标 / 触摸注入 | 未实现 | `host/HostSession.java:58,558-591` | 强 | 主循环只分发 `key_event`（和剪贴板）；鼠标消息没有 `case`，直接落到 `default` |
| host 剪贴板 | 部分 | `host/HostSession.java:769-830`（2 s 轮询）、`host/HostClipboardSink.java:16`（只有接口） | 弱 | 会话侧双向支持齐了，但**没有任何 Android 类实现 `HostClipboardSink`**，`setClipboardSink` 无调用者 → 未接线 |

### 11.4 文件传输与终端

| 能力 | 状态 | 代码位置 | 证据强度 | 依据 / 说明 |
|---|---|---|---|---|
| 文件传输 | 未实现（仅消息编码） | `session/FileMessages.java`、`session/Session.java:269-303` | 强 | `union` 分发里**没有** `FileResponse` 分支，也没有选文件/分块/落盘/进度 UI；`FileMsgTest` 的 75 checks 只测编解码 |
| 终端·协议层 | 已实现 | `session/TerminalMessages.java`、`session/Session.java:303,424-447` | 强 | 能发 `open`/`data`/`resize`/`close`，能收 `terminal_response`；`TerminalMsgTest` 73 checks |
| 终端·UI | 部分 | `ui/TerminalActivity.java:33`、`app/src/main/AndroidManifest.xml:35-36` | 中 | `TerminalActivity` 现在**存在**（实现了 `Session.Listener`，滚动输出 + 单行输入），也已进清单；但全仓没有任何 `startActivity` 指向它，`RemoteActivity.java:486` 收到数据仍只打日志 → 页面在、入口没接 |

### 11.5 测试与脚本

| 项 | 实测值 | 来源 | 证据强度 |
|---|---|---|---|
| 离线套件 | **347 checks / 10 suites，10/10 PASSED**（本轮连跑两遍一致） | `tools/selftest/run-selftest.ps1` | 强 |
| 套件目录 | 11 个 `.java`：10 个离线 + `HostEndToEnd.java`（要真服务器，不进默认列表）+ `README.md` | `tools\selftest\suites\` | 强 |
| 网络端到端 | `HostEndToEnd` 对 `rs-ny.rustdesk.com:21116` 全过：注册 → 解析 → 直连 → SignedId 验签 → 登录 → 输入后会话存活 → 干净停止 | `tools\selftest\suites\HostEndToEnd.java` | 强 |
| 「362」 | **未复现**。本轮两次都是 347；362 可能来自更早的套件版本 | — | — |

各套件检查数（本轮输出）：`TerminalMsgTest` 73、`ClipSyncTest` 11、`FileMsgTest` 75、`KbSelfTest` 7、
`IdCheckTest` 14、`crypto.SelfTest` 51、`HostSessionTest` 49、`PeerHandshakeInteropTest` 31、
`Ed25519Test` 22、`LoopbackTest` 14。其中与 host 直接相关的是 `HostSessionTest`(49) 和
`PeerHandshakeInteropTest`(31)——后者就是「JVM 互通 31 项」：真实 `PeerHandshake` 驱动真实 `HostSession`。

### 11.6 `AddrMangle.decode` 的短 blob 修复

`net/AddrMangle.java:40-54`：短 blob（如 hbbs 的 11 B）先左填零成 16 B，再按小端 u128 还原。
关键在 `:54` 的 `& 0xFFFFL`：

```java
long port = (number.and(MASK_24).longValue() - tm.and(MASK_16).longValue()) & 0xFFFFL;
```

Rust 侧这个减法是 u32 运算、构造 `SocketAddrV4` 时截断成 u16；Java 不做掩码时减法可能为负，
`InetSocketAddress` 直接抛 `IllegalArgumentException`，于是**内网短地址整条解不出来**。
`>16 B` 的分支（`:26-38`）是 IPv6 裸地址 + 小端端口，严格只收 18 B。
证据强度：**中**——语义已按 `hbb_common` 对齐、注释留了原因（`:51-53`），但仓库内没有针对短 blob 的单测；
本轮 e2e 解析成功走的是 16 B 的 `LocalAddr` 编码，没有直接命中这个分支。

### 11.7 「未验证」清单

| 条目 | 为什么未验证 |
|---|---|
| viewer 解码 + 渲染 | 代码完整，但没有真机 logcat / 截图；vendor decoder 是否有 VP9 未知 |
| host 采集 / 编码 / EGL | 同上，`setFrameListener` 无调用者，从没在设备上跑过 |
| host 键盘注入只有 Home 落地 | 代码支持 4 个全局动作；具体哪个生效没有实测产物 |
| 历史「3/3 punch」 | 只有本轮 1/1 可复跑，没有三次的日志 |
| 中继腿 | 本轮走内网直连，relay 分支没有实跑 |
| H.264/H.265/VP8/AV1 收流 | 只收到过 VP9（§8） |
| 多显示器 `VideoFrame.display` | 只处理 `displays[0]`（§8） |
| `TYPE_TRACKPAD` 的 x/y 语义 | 只有常量，未构造/抓包（§7.1） |
| VP9 抓包样本 | 结论来自实现期实测，仓库无脚本/样本（§8） |

### 11.8 如何用本文档

1. **先看 11，再看 1–8**：11 回答「这块现在能不能用」，1–8 回答「协议为什么长这样」。9 是踩坑记录，遇到
   对端不回包/验签失败先翻 9。
2. **每条事实都带来源**（`文件:行号` 或 `文件`）。行号随代码漂移，引用前用函数名或常量名 grep 一次——
   尤其 host 侧仍在改。
3. **「未验证」是承诺等级，不是免责声明**：它表示没有可复跑证据，别当结论引用，也别在它上面继续叠功能。
4. **想推进 host 端**：先补最后一段接线——`ScreenCaptureService.setFrameListener(...)` 把帧交给
   `HostAgent.onCapturedFrame(...)`（11.3「视频接线」行）；其余零件都已就位。
5. **想复跑本文档的证据**：

   ```powershell
   pwsh -File tools\selftest\run-selftest.ps1                 # 347 checks / 10 suites
   # 真机：装机 + 连指定对端 + 抓 logcat
   pwsh -File tools\verify-device.ps1 -Id <对端ID> -Password <密码> -DurationSec 30
   ```

6. **本文档不管**：UI 外观、Gradle/CI、上游 Rust 端实现细节；这些看 `../README.md`、仓库根
   `.github/workflows/` 和上游 `host/src/`。

