# 大肥鱼 Link —— 自建加密消息通道

把原项目的 **ntfy 远程聊天** 换掉：手机上那台跑 AutoX.js 的机器当服务端，你手机上的
「大肥鱼 Link」App 当客户端，中间是一条 **裸 TCP + AES-256-GCM** 的长连接。
不走 HTTP/HTTPS，不走 ntfy，不依赖任何中转服务商。

```
┌──────────────────────┐          TCP + AES-256-GCM           ┌───────────────────────┐
│ 大肥鱼 Link (App)     │ ───────────────────────────────────▶ │ net_bridge.js (AutoX) │
│ 原生 Android / Java   │ ◀─────────────────────────────────── │ SocketServer + Cipher │
│ 文字/表情/图片/语音    │            IPv6 直连 或 frp           │ DeepSeek → 回复        │
└──────────────────────┘                                      └───────────────────────┘
```

## 一、跑起来（三步）

### 1. 生成密钥

任意一端生成都行，两端填同一串：

```bash
# App 里点「设置 → 随机生成」，或命令生成：
head -c 32 /dev/urandom | base64
# 例：0m3lY7cQ2f8t9xV1pR6sW4bN5hJ0kL2zM8qT3uY6oI4=
```

两端设置页都会显示 **密钥指纹**（4 字节 hex）。**指纹一致 = 密钥一致**，不用逐字符比对。

### 2. 手机端（服务端，AutoX.js）

```text
/sdcard/PhoneAgent/net_bridge.js      ← 本仓库 autox/net_bridge.js
/sdcard/PhoneAgent/link.json          ← 配置
```

`link.json`：

```json
{
  "port": 9527,
  "key": "0m3lY7cQ2f8t9xV1pR6sW4bN5hJ0kL2zM8qT3uY6oI4=",
  "nick": "大肥鱼"
}
```

在 AutoX.js 里打开 `net_bridge.js` 点运行。日志会打：

```
[2026-11-04T04:12:33.512Z] 监听 [::]:9527（IPv6 双栈）
```

- 绑的是 `::`，双栈，IPv6 直连和 IPv4（frp 常见）都能进；绑不上会自动退回 `0.0.0.0`。
- 模型 Key 复用 `agent.js` 的 `storages("phone_agent_v1")`，不用重填。
- 它读 `/sdcard/DeepSeekGirl/persona.md`、`memory/facts.md`、`memory/self.md`，人格和记忆跟主 Agent 是同一份。
- 聊天记录写 `memory/chat/YYYY-MM-DD.jsonl`，和 `agent.js` 同目录同格式。

### 3. 你手机端（客户端，App）

装 `DafeiyuLink-debug.apk`，打开：

- **设置**：地址、端口、密钥 → 保存
- 或者让对方手机显示二维码（设置 → 显示二维码），你点 **扫码** 直接导入

地址就填对方的 IPv6：

```
2408:8207:1234:5678:abcd:ef01:2345:6789
```

`frp` 用户填映射后的公网地址（v4 也行）和映射端口，App 这边完全不用改。

## 二、公网可达怎么搞

| 方式 | 做法 | 说明 |
|---|---|---|
| IPv6 直连 | 两台手机都有公网 v6 → 直接填地址 | 最省事，不经过任何第三方；手机流量卡一般有 v6，Wi-Fi 看运营商 |
| frp | 在服务器上 `[dafeiyu] local_ip=… local_port=9527 remote_port=19527`，App 填 `服务器IP:19527` | 本项目不关心转发方式，只要求"把 TCP 9527 通了" |
| 内网穿透同理 | 任何 TCP 转发（nps、rathole、socat） | 只要 TCP 通即可，不要求 HTTP |

**注意**：不要用 HTTP 反代（nginx/caddy）——这套协议是自定义二进制帧，反代只会破坏它。

## 三、加密设计

```
握手：客户端 → "DLK1" + ver + 16B saltC
      服务端 → "DLK2" + ver + 16B saltS
      会话密钥 = HKDF-SHA256(PSK, saltC ‖ saltS, "dafeiyu-link-v1", 32)

帧： magic(2) ver(1) type(1) len(4) counter(4) | ciphertext(len) | tag(16)
     AES-256-GCM，AAD = 前 12 字节帧头（改一个 bit 就解不开）
     nonce = 方向字节(1字节) + 000 + counter(8字节)，每方向独立计数
```

| 属性 | 值 |
|---|---|
| 对称加密 | AES-256-GCM（JDK 内置，两端同一套原语） |
| 密钥派生 | HKDF-SHA256，每连接独立 salt → 每次会话密钥都不同 |
| 密钥长度 | 32 字节，Base64 或 64 位 hex 都认 |
| 重放防护 | 帧序号计数器 + AAD 绑定头部 |
| 前向保密 | 每次连接用新 salt，旧连接密钥不可复用 |

> 没有做公钥交换（X25519/ECDH）。原因是 AutoX.js 的 Rhino 环境里做 ECDH 要额外
> BouncyCastle，而 PSK + 每连接 salt 的 HKDF 已经能防重放和被动嗅探；
> 真正的弱点是"PSK 怎么传给你"——用二维码当面扫，不要发微信。

## 四、消息格式（JSON，加密后放进帧）

| t | 方向 | 字段 |
|---|---|---|
| `text` | 双向 | `body` |
| `emoji` | 双向 | `body`（emoji 字符） |
| `image` | 双向 | `name` `size` `chunks`，随后 N 个 `chunk` |
| `voice` | 双向 | `dur` `size` `chunks`，随后 N 个 `chunk` |
| `chunk` | 双向 | `id` `seq` `data`(base64) |
| `typing` | 双向 | `on` |
| `ping` / `pong` | 双向 | 空 |

分块 24KB，单帧上限 512KB（明文），超了协议直接拒绝。

## 五、App 功能

- 聊天气泡（左右分栏、时间戳、发送状态点）
- 表情面板：80 个 emoji，点一下直发
- 图片：相册选图 → 压到 ≤700KB JPEG → 分块发
- 语音：按住麦克风录音（AAC 22.05k）→ 松开发；点气泡播放
- 扫码：Camera2 取 Y 平面直接喂 zxing，不转整帧 Bitmap
- 二维码：设置页生成 `dafeiyu://connect?h=…&p=…&k=…`
- 本地历史：SQLite（`link.db`），断线重进不丢
- 前台服务常驻，通知栏显示连接状态；断线 3 秒自动重连

## 六、编译

仓库根有 `.github/workflows/build-link.yml`，点 **Actions → build-dafeiyu-link → Run workflow**，
产物在 `dafeiyu-link-apk` artifact 里。

本地编译：

```bash
cd link
gradle assembleDebug
# 产物 link/app/build/outputs/apk/debug/app-debug.apk
```

依赖只有一个纯 Java jar：`link/app/libs/core-3.5.3.jar`（zxing core，扫码/生成二维码）。
没有 androidx，UI 全用 framework 的 View，所以不依赖 Maven 仓库里的任何 AAR。

## 七、排错

| 现象 | 原因 |
|---|---|
| 一直"连接中" | IPv6 地址不对 / 运营商没给 v6 / 防火墙没放行 9527 |
| 连上马上断，日志 `magic 不对` | 对端不是这套协议，或中间有 HTTP 反代 |
| `tag 校验失败`（AEADBadTag） | 两端密钥不一致，看指纹 |
| 扫码后连不上 | 二维码里的地址是对方自己填的，扫完仍要网络可达 |
| 手机端日志 `监听 [::] 失败` | 端口被占，换 19527 之类 |
| 图片发不出 | 相册权限没给，或图超过 512KB 单帧（会自动压到 700KB，正常不会） |

## 八、跟原项目的关系

| 原来 | 现在 |
|---|---|
| `ntfy.envs.net` 公共服务器 | 自建 TCP，不过第三方 |
| 明文（ntfy 不加密） | AES-256-GCM |
| 通知监听 / 无障碍 | 完全不碰 UI |
| `DafeiyuChat` APK（WebView 套壳） | 原生 Java App，无 WebView |
| `agent.js` 里 `pollRemote()` | `net_bridge.js` 独立进程，读写同一份记忆目录 |

`agent.js` 保持原样就能跑，两套通道可以并存：
`agent.js` 管"她自己在手机上生活"，`net_bridge.js` 管"和主人的加密聊天"。

## 九、自测（不用真机也能验）

协议层做了端到端互通验证：node 版服务端（`tools/protocol-server.js`）+ Java 版客户端
（`tools/WireClient.java`，直接用 App 里同一份 `Crypto.java` / `Frame.java` / `Session.java`）。

```bash
# 1. 编译 App 的协议层（需要 android.jar 与 JDK）
javac -encoding UTF-8 -source 11 -target 11 \
      -classpath "$HOME/android.jar:link/app/libs/core-3.5.3.jar" \
      -d /tmp/out link/app/src/main/java/com/deepseek/dafeiyu/link/{Crypto,Frame,Session}.java

# 2. 编译自测客户端
javac -encoding UTF-8 -classpath /tmp/out -d /tmp/out tools/WireClient.java

# 3. 起假服务端
PSK=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
node tools/protocol-server.js 19527 "$PSK" &

# 4. 跑客户端
java -cp /tmp/out WireClient 127.0.0.1 19527 "$PSK"
```

预期输出（已实测）：

```
[cli] 握手完成 key=7ca3088ad0babd0b…
[cli] 收到帧 type=1 {"t":"typing","on":true}
[cli] 收到帧 type=1 {"t":"text",...,"body":"收到「你好，本鱼」，本鱼在。"}
[cli] 收到帧 type=1 {"t":"typing","on":false}
[cli] 心跳返回 type=3（3=PONG）
[cli] 全部通过
```

服务端侧同期日志：

```
[srv] 握手完成 fp=ede657b3
[srv] 收到: 你好，本鱼
[srv] 收到媒体头: image 30000 bytes / 2 块
[srv] 收到分块 img1 seq=0 32000 base64 字符
[srv] 收到分块 img1 seq=1 8000 base64 字符
```

这组用例覆盖了：握手派生、文本双向、图片分块（2 块）、心跳 PONG。
`net_bridge.js` 用同一套 HKDF/AES-GCM 参数，真机上只需填对 key。
