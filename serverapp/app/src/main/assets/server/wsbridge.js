'use strict';
/**
 * wsbridge.js —— 大肥鱼 Link 的「网页端」桥
 *
 * 零依赖：只用 node 内置模块（http / crypto / fs / path）。WebSocket 是手写的。
 *
 *   HTTP :9528  GET / | /index.html -> 静态 HTML 壳（不含任何业务数据）
 *               GET /health         -> {"ok":true}
 *               其它路径 404，非 GET 一律 405
 *   WS   :9528/ws  一条二进制消息 = 一条消息（salt 或 一个加密帧）
 *
 * 握手与 App 完全一致（见 docs/WEB-PROTOCOL.md）：
 *   C->S 16B saltC  →  S->C 16B saltS
 *   key = HKDF-SHA256(PSK, saltC‖saltS, "dafeiyu-link-v1", 32)
 *
 * 帧（全部网络序）：
 *   偏移  0  magic   u16 BE  0xD1A9
 *         2  version u8      1
 *         3  type    u8      1=JSON 2=PING 3=PONG 4=BYE
 *         4  length  u32 BE  明文长度
 *         8  counter u32 BE  帧序号（每方向独立，从 0 递增）
 *        12  ciphertext[length + 16]   AES-256-GCM 密文 + 16B tag
 *   AAD   = 前 12 字节头
 *   nonce = direction(1) | 00 00 00 | counter 放在偏移 4..7 的 4 字节 BE（偏移 8..11 为 0）
 *           —— 与 App 端 link/.../Frame.java 的 nonce() 逐字节一致；
 *              WEB-PROTOCOL.md 里写作 "counter(8, BE)"，若前端按 8 字节 BE 实现
 *              （counter 落在偏移 8..11），本模块在首帧解密失败时自动兼容该排布并锁定。
 *
 * 用法：
 *   const createWebBridge = require('./wsbridge');
 *   const br = createWebBridge({
 *     httpPort: 9528,
 *     htmlPath: '/abs/path/index.html',
 *     psk: Buffer.from(token, 'base64'),   // 32 字节
 *     onMessage(obj) { return obj.t === 'text' ? '收到' : null; },
 *     log(m) { console.log(m); },
 *   });
 */

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');

// ------------------------------------------------------------------ 协议常量
const MAGIC = 0xd1a9;          // 偏移 0..1
const VERSION = 1;             // 偏移 2
const HDR = 12;                // 头长
const TAG = 16;                // GCM tag 长度
const MAX_PLAIN = 512 * 1024;  // 单帧明文上限（与 App 的 Frame.MAX_PLAIN 一致）
const MAX_WS_MSG = 1 * 1024 * 1024; // 单条 WS 消息上限，防内存炸

const T_JSON = 1;
const T_PING = 2;
const T_PONG = 3;
const T_BYE = 4;

const OP_CONT = 0x0;
const OP_TEXT = 0x1;
const OP_BIN = 0x2;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const HKDF_INFO = Buffer.from('dafeiyu-link-v1', 'utf8');

// ------------------------------------------------------------------ 加密小工具
/** HKDF-SHA256 → 32 字节会话密钥 */
function deriveKey(psk, saltC, saltS) {
  const salt = Buffer.concat([saltC, saltS]);
  return Buffer.from(crypto.hkdfSync('sha256', psk, salt, HKDF_INFO, 32));
}

/** AES-256-GCM 加密，返回 密文‖tag */
function seal(key, nonce, aad, plain) {
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  c.setAAD(aad);
  return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
}

/** AES-256-GCM 解密，密文末尾 16 字节是 tag；tag 不对会抛异常 */
function open(key, nonce, aad, sealed) {
  const body = sealed.subarray(0, sealed.length - TAG);
  const tag = sealed.subarray(sealed.length - TAG);
  const d = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  d.setAAD(aad);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(body), d.final()]);
}

/**
 * nonce（12 字节）= dir(1) | 000000(3) | counter(4 BE @4..7) | 00000000(4)
 * 与 App 端 Frame.nonce() 一致：n[0]=dir, n[4..7]=counter, n[8..11]=0
 */
function nonce(dir, counter) {
  const n = Buffer.alloc(12);
  n[0] = dir;
  n.writeUInt32BE(counter >>> 0, 4);
  return n;
}

/** 兼容排布：counter 落在偏移 8..11（文档 "counter(8, BE)" 的字面读法） */
function nonceAlt(dir, counter) {
  const n = Buffer.alloc(12);
  n[0] = dir;
  n.writeUInt32BE(counter >>> 0, 8);
  return n;
}

/** 组一个加密帧（含 12 字节头）。dir 固定为 2（服务端 → 客户端） */
function buildFrame(key, type, counter, plain) {
  const hdr = Buffer.alloc(HDR);
  hdr.writeUInt16BE(MAGIC, 0);            // 0..1 magic
  hdr[2] = VERSION;                       // 2    version
  hdr[3] = type;                          // 3    type
  hdr.writeUInt32BE(plain.length, 4);     // 4..7 明文长度
  hdr.writeUInt32BE(counter >>> 0, 8);    // 8..11 帧序号
  return Buffer.concat([hdr, seal(key, nonce(2, counter), hdr, plain)]);
}

// ------------------------------------------------------------------ WebSocket 帧编解码
/** 服务端发帧：不掩码，长度按 125 / 126(2B) / 127(8B) 三档编码 */
function encodeWsFrame(opcode, payload) {
  const len = payload.length;
  let head;
  if (len < 126) {
    head = Buffer.allocUnsafe(2);
    head[0] = 0x80 | opcode;              // FIN=1 + opcode
    head[1] = len;                        // 无 mask 位
  } else if (len < 65536) {
    head = Buffer.allocUnsafe(4);
    head[0] = 0x80 | opcode;
    head[1] = 126;                        // 扩展长度：2 字节
    head.writeUInt16BE(len, 2);
  } else {
    head = Buffer.allocUnsafe(10);
    head[0] = 0x80 | opcode;
    head[1] = 127;                        // 扩展长度：8 字节
    head.writeUInt32BE(0, 2);             // 高 32 位
    head.writeUInt32BE(len, 6);           // 低 32 位
  }
  return Buffer.concat([head, payload]);
}

/**
 * 尝试从 buf 头部解出一个客户端帧。
 * 返回 null 表示数据还不够；{ error } 表示协议违规；否则 { fin, opcode, payload, size }。
 * 布局：0 FIN/RSV/opcode | 1 MASK/len7 | [扩展长度] | [4B mask] | payload
 */
function parseWsFrame(buf) {
  if (buf.length < 2) return null;
  const fin = (buf[0] & 0x80) !== 0;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let off = 2;

  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);            // 偏移 2..3
    off = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    const hi = buf.readUInt32BE(2);       // 偏移 2..5
    const lo = buf.readUInt32BE(6);       // 偏移 6..9
    if (hi !== 0) return { error: 'payload 超过 4GB' };
    len = lo;
    off = 10;
  }

  if (len > MAX_WS_MSG) return { error: 'WS 消息过大 ' + len };
  if (!masked) return { error: '客户端帧必须带掩码' };
  if (buf.length < off + 4 + len) return null;

  const mask = buf.subarray(off, off + 4);
  off += 4;
  const payload = Buffer.allocUnsafe(len);
  for (let i = 0; i < len; i++) payload[i] = buf[off + i] ^ mask[i & 3];
  return { fin, opcode, payload, size: off + len };
}

// ------------------------------------------------------------------ 单条 WS 连接
/**
 * 把裸 socket 包成一个 WS 连接：负责收帧、分片拼接、控制帧（ping/pong/close）。
 * onMessage(opcode, payload) 拿到已解掩码的完整消息（opcode 1=text，2=binary）。
 */
function createWsConnection(socket, head, opts) {
  const log = opts.log;
  const onMessage = opts.onMessage;
  const onDead = opts.onDead;

  let buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
  let fragOpcode = 0;
  const fragParts = [];
  let alive = true;
  let closed = false;

  function sendRaw(opcode, payload) {
    if (!alive) return;
    try {
      socket.write(encodeWsFrame(opcode, payload || Buffer.alloc(0)));
    } catch (e) {
      log('写 WS 帧失败: ' + e.message);
      destroy();
    }
  }

  function destroy() {
    if (!alive) return;
    alive = false;
    try { socket.destroy(); } catch (e) { /* 忽略 */ }
    if (!closed) { closed = true; onDead(); }
  }

  /** 正常关闭：先发 close 帧（2 字节状态码 + 原因），再 FIN，最后强断 */
  function close(code, reason) {
    if (!alive) return;
    const body = Buffer.alloc(2 + Buffer.byteLength(reason || ''));
    body.writeUInt16BE(code || 1000, 0);
    if (reason) body.write(reason, 2, 'utf8');
    sendRaw(OP_CLOSE, body);
    alive = false;
    try { socket.end(); } catch (e) { /* 忽略 */ }
    if (!closed) { closed = true; onDead(); }
    setTimeout(() => { try { socket.destroy(); } catch (e) { /* 忽略 */ } }, 100).unref?.();
  }

  function dispatch(opcode, payload) {
    if (opcode !== OP_TEXT && opcode !== OP_BIN) return; // 只关心数据帧
    try {
      onMessage(opcode, payload);
    } catch (e) {
      log('消息处理异常: ' + (e && e.stack ? e.stack : e));
    }
  }

  function handleFrame(f) {
    if (f.opcode === OP_CLOSE) { log('对端 close 帧'); destroy(); return; }
    if (f.opcode === OP_PING) { sendRaw(OP_PONG, f.payload); return; }
    if (f.opcode === OP_PONG) return;

    if (f.opcode === OP_CONT) {
      if (!fragOpcode) { log('收到孤立 continuation 帧'); destroy(); return; }
      fragParts.push(f.payload);
      const total = fragParts.reduce((n, b) => n + b.length, 0);
      if (total > MAX_WS_MSG) { log('分片累计过大'); destroy(); return; }
      if (f.fin) {
        const whole = Buffer.concat(fragParts);
        fragParts.length = 0;
        const op = fragOpcode;
        fragOpcode = 0;
        dispatch(op, whole);
      }
      return;
    }

    if (f.opcode === OP_TEXT || f.opcode === OP_BIN) {
      if (!f.fin) { fragOpcode = f.opcode; fragParts.length = 0; fragParts.push(f.payload); return; }
      dispatch(f.opcode, f.payload);
      return;
    }
    log('未知 opcode ' + f.opcode + '，忽略');
  }

  function pump() {
    for (;;) {
      const f = parseWsFrame(buf);
      if (f === null) return;
      if (f.error) { log('WS 帧非法: ' + f.error); destroy(); return; }
      buf = buf.subarray(f.size);
      handleFrame(f);
      if (!alive) return;
    }
  }

  socket.on('data', (chunk) => {
    if (!alive) return;
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    try { pump(); } catch (e) { log('pump 异常: ' + e.message); destroy(); }
  });
  socket.on('error', (e) => { log('socket 错误: ' + e.message); destroy(); });
  socket.on('close', () => { if (!closed) { closed = true; onDead(); } });
  socket.setNoDelay(true);
  socket.setTimeout(0);

  pump(); // head 里可能已经有数据
  return { sendRaw, close, destroy, get alive() { return alive; } };
}

// ------------------------------------------------------------------ 加密会话（握手 + 帧）
/**
 * 会话状态机：
 *   stage=hello  等客户端 16B saltC → 回 16B saltS → 派生 key
 *   stage=frames 每条二进制消息里按 12B 头 + 密文解帧
 */
function createSession(conn, opts, onTalk) {
  const log = opts.log;
  const psk = opts.psk;
  const st = {
    stage: 'hello',
    key: null,
    out: 0,          // 服务端发帧计数（dir=2）
    inLast: -1,      // 客户端来帧计数（dir=1）
    altNonce: null,  // null=未定；true=用 8 字节 BE 排布；false=用 App 排布
  };

  function sendFrame(type, plain) {
    if (!st.key) return;
    const counter = st.out++;
    try {
      conn.sendRaw(OP_BIN, buildFrame(st.key, type, counter, plain));
    } catch (e) {
      log('发帧失败: ' + e.message);
    }
  }

  function sendJson(obj) {
    sendFrame(T_JSON, Buffer.from(JSON.stringify(obj), 'utf8'));
  }

  function kill(msg) {
    log(msg);
    conn.close(1008, 'crypto');
  }

  // ---- 握手阶段：偏移 0..15 = saltC
  function onHello(payload) {
    if (payload.length !== 16) return kill('握手失败：saltC 必须是 16 字节，实际 ' + payload.length);
    const saltC = payload;
    const saltS = crypto.randomBytes(16);
    st.key = deriveKey(psk, saltC, saltS);
    st.stage = 'frames';
    const fp = crypto.createHmac('sha256', st.key).update('fp').digest('hex').slice(0, 8);
    conn.sendRaw(OP_BIN, saltS);          // 服务端先发 saltS
    log('WS 握手完成 key 指纹 ' + fp);
  }

  // ---- 解一条加密帧，返回 { type, plain } 或 { error }
  function openFrame(msg, offset) {
    if (msg.length - offset < HDR + TAG) return { error: '帧太短（需要 ≥ ' + (HDR + TAG) + ' 字节）' };
    const hdr = msg.subarray(offset, offset + HDR);
    const magic = hdr.readUInt16BE(0);    // 0..1
    const ver = hdr[2];                   // 2
    const type = hdr[3];                  // 3
    const len = hdr.readUInt32BE(4);       // 4..7
    const counter = hdr.readUInt32BE(8);   // 8..11

    if (magic !== MAGIC) return { error: 'magic 不对 0x' + magic.toString(16) };
    if (ver !== VERSION) return { error: '版本不支持 ' + ver };
    if (type < 1 || type > 4) return { error: 'type 非法 ' + type };
    if (len > MAX_PLAIN) return { error: '明文长度非法 ' + len };
    if (msg.length - offset !== HDR + len + TAG) {
      return { error: '长度不匹配：头 ' + len + '，实收 ' + (msg.length - offset - HDR - TAG) };
    }
    const sealed = msg.subarray(offset + HDR);
    const aad = hdr;                       // AAD = 前 12 字节

    // 先用 App 排布，失败再试文档字面排布（只在首帧自动探测，之后锁定）
    const tries = st.altNonce === null ? [false, true] : [st.altNonce];
    for (const alt of tries) {
      try {
        const plain = open(st.key, alt ? nonceAlt(1, counter) : nonce(1, counter), aad, sealed);
        if (st.altNonce === null) {
          st.altNonce = alt;
          if (alt) log('提示：对端使用 8 字节 BE counter 的 nonce 排布，已自动兼容');
        }
        return { type, counter, plain, size: HDR + len + TAG };
      } catch (e) { /* 换下一种排布 */ }
    }
    return { error: '解密失败（PSK/令牌不对，或 nonce 排布不匹配）' };
  }

  function handleJson(plain) {
    let obj;
    try {
      obj = JSON.parse(plain.toString('utf8'));
    } catch (e) {
      log('JSON 解析失败，丢弃该帧');
      return;
    }
    if (obj && obj.t === 'ping') { sendJson({ t: 'pong' }); return; }  // 消息级 ping → pong
    Promise.resolve()
      .then(() => onTalk(obj))
      .then((reply) => {
        if (typeof reply === 'string' && reply.length > 0) {
          sendJson({ t: 'text', body: reply, ts: Math.floor(Date.now() / 1000) });
        }
      })
      .catch((e) => log('onMessage 处理异常: ' + (e && e.message ? e.message : e)));
  }

  function onPayload(payload) {
    if (st.stage === 'hello') return onHello(payload);

    // 一条 WS 消息里可能有多个帧，按 size 往后走
    let off = 0;
    while (off < payload.length) {
      const r = openFrame(payload, off);
      if (r.error) return kill('解密失败，关闭连接：' + r.error);
      off += r.size;

      if (r.counter < st.inLast) return kill('counter 回退（' + r.counter + ' < ' + st.inLast + '），可能重放');
      st.inLast = r.counter;

      if (r.type === T_PING) { sendFrame(T_PONG, Buffer.alloc(0)); continue; }
      if (r.type === T_PONG) continue;
      if (r.type === T_BYE) { log('对端 BYE'); conn.close(1000, 'bye'); return; }
      if (r.type === T_JSON) { handleJson(r.plain); continue; }
      log('未知帧类型 ' + r.type + '，忽略');
    }
  }

  return { onPayload, sendJson, sendFrame };
}

// ------------------------------------------------------------------ 主入口
/**
 * @param {object} opts
 * @param {number} [opts.httpPort=9528]
 * @param {string} opts.htmlPath        要原样返回的 HTML 绝对路径
 * @param {Buffer} opts.psk             32 字节 PSK
 * @param {(obj:object)=>(string|Promise<string|null>|null)} [opts.onMessage]
 * @param {(msg:string)=>void} [opts.log]
 * @param {string} [opts.wsPath='/ws']  WS 路径（默认 /ws，另兼容 /）
 * @param {number} [opts.portAttempts=20] 端口被占用时向上顺延的尝试次数
 */
module.exports = function createWebBridge(opts = {}) {
  const o = opts || {};
  const log = typeof o.log === 'function' ? o.log : () => {};
  const onMessage = typeof o.onMessage === 'function' ? o.onMessage : () => null;
  const httpPort = Number.isFinite(o.httpPort) ? o.httpPort : 9528;
  const htmlPath = o.htmlPath;
  const wsPath = typeof o.wsPath === 'string' && o.wsPath ? o.wsPath : '/ws';
  const portAttempts = Number.isFinite(o.portAttempts) ? o.portAttempts : 20;

  if (!Buffer.isBuffer(o.psk) || o.psk.length !== 32) {
    throw new Error('opts.psk 必须是 32 字节 Buffer');
  }

  let boundPort = 0;
  const conns = new Set();  // 允许多个浏览器/设备同时在线，各自独立会话
  const sessions = new Set(); // 已握手的会话，用于广播
  let closing = false;

  // ---------------- HTTP 小工具
  function header(res, status, headers) {
    try {
      res.writeHead(status, headers);
    } catch (e) {
      log('写 HTTP 头失败: ' + e.message);
    }
  }

  function sendText(res, status, body, extra) {
    const buf = Buffer.from(String(body), 'utf8');
    header(res, status, Object.assign({
      'Content-Type': 'text/plain; charset=utf-8',
      'Content-Length': buf.length,
      'Cache-Control': 'no-store',
    }, extra || {}));
    try { res.end(buf); } catch (e) { /* 忽略 */ }
  }

  function sendJson(res, status, obj) {
    const buf = Buffer.from(JSON.stringify(obj), 'utf8');
    header(res, status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': buf.length,
      'Cache-Control': 'no-store',
    });
    try { res.end(buf); } catch (e) { /* 忽略 */ }
  }

  function serveHtml(res) {
    if (!htmlPath) return sendText(res, 500, 'htmlPath 未配置');
    let buf;
    try {
      buf = fs.readFileSync(htmlPath);
    } catch (e) {
      log('读 HTML 失败: ' + e.message);
      return sendText(res, 500, '页面不可用: ' + e.message);
    }
    header(res, 200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': buf.length,
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',   // URL 里带令牌，别随外链泄出去
    });
    try { res.end(buf); } catch (e) { /* 忽略 */ }
  }

  // ---------------- 请求分发
  function handleRequest(req, res) {
    try {
      const pathOnly = String(req.url || '/').split('?')[0];
      if (req.method !== 'GET') {
        return sendText(res, 405, 'Method Not Allowed', { Allow: 'GET' });
      }
      if (pathOnly === '/' || pathOnly === '/index.html') return serveHtml(res);
      if (pathOnly === '/health') return sendJson(res, 200, { ok: true });
      return sendText(res, 404, 'Not Found');
    } catch (e) {
      log('HTTP 处理异常: ' + (e && e.stack ? e.stack : e));
      try { res.destroy(); } catch (_) { /* 忽略 */ }
    }
  }

  // ---------------- Upgrade 拒绝（还在 HTTP 语义里）
  function rejectUpgrade(socket, status, text, extra) {
    try {
      const body = Buffer.from(text + '\n', 'utf8');
      let head = 'HTTP/1.1 ' + status + ' ' + http.STATUS_CODES[status] + '\r\n'
        + 'Connection: close\r\n'
        + 'Content-Type: text/plain; charset=utf-8\r\n'
        + 'Content-Length: ' + body.length + '\r\n';
      for (const k of Object.keys(extra || {})) head += k + ': ' + extra[k] + '\r\n';
      socket.write(head + '\r\n');
      socket.write(body);
      socket.end();
    } catch (e) { /* 忽略 */ }
    setTimeout(() => { try { socket.destroy(); } catch (e) { /* 忽略 */ } }, 50).unref?.();
  }

  function isValidWsKey(key) {
    if (typeof key !== 'string' || key.length < 20) return false;
    try { return Buffer.from(key, 'base64').length === 16; } catch (e) { return false; }
  }

  // ---------------- WebSocket 升级
  function handleUpgrade(req, socket, head) {
    socket.on('error', () => { /* 下面会统一处理 */ });
    try {
      const upgrade = String(req.headers.upgrade || '').toLowerCase();
      const key = req.headers['sec-websocket-key'];
      const version = String(req.headers['sec-websocket-version'] || '');
      const pathOnly = String(req.url || '/').split('?')[0];

      if (upgrade !== 'websocket') return rejectUpgrade(socket, 400, 'not a websocket upgrade');
      if (pathOnly !== wsPath && pathOnly !== '/') return rejectUpgrade(socket, 404, 'ws path not found');
      if (version && version !== '13') {
        return rejectUpgrade(socket, 426, 'unsupported websocket version', { 'Sec-WebSocket-Version': '13' });
      }
      if (!isValidWsKey(key)) return rejectUpgrade(socket, 400, 'bad Sec-WebSocket-Key');

      const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n'
        + 'Upgrade: websocket\r\n'
        + 'Connection: Upgrade\r\n'
        + 'Sec-WebSocket-Accept: ' + accept + '\r\n'
        + '\r\n'
      );

      let session = null;
      const early = [];                 // session 就绪前先到的载荷（head 里可能有）
      let ws = null;
      ws = createWsConnection(socket, head, {
        log,
        onDead: () => { if (ws) { conns.delete(ws); if (ws.__sess) sessions.delete(ws.__sess); } },
        onMessage: (opcode, payload) => {
          if (opcode === OP_TEXT) return;            // text 帧按 UTF-8 忽略，本桥只吃二进制
          if (!session) { early.push(payload); return; }
          try { session.onPayload(payload); } catch (e) { log('解帧异常: ' + (e && e.stack ? e.stack : e)); }
        },
      });
      session = createSession(ws, { log, psk: o.psk }, onMessage);
      sessions.add(session);
      ws.__sess = session;
      conns.add(ws);
      while (early.length) {
        try { session.onPayload(early.shift()); } catch (e) { log('解帧异常: ' + (e && e.stack ? e.stack : e)); }
      }
      log('WS 客户端接入 ' + (socket.remoteAddress || '?'));
    } catch (e) {
      log('Upgrade 处理异常: ' + (e && e.stack ? e.stack : e));
      try { socket.destroy(); } catch (_) { /* 忽略 */ }
    }
  }

  // ---------------- 起服务（端口被占则顺延）
  const server = http.createServer(handleRequest);
  server.on('upgrade', handleUpgrade);
  server.on('clientError', (err, sock) => {
    try { sock.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); } catch (e) { /* 忽略 */ }
  });
  server.on('error', (e) => { /* 下面 listen 阶段自己接 */ });

  let attempt = 0;
  let host = '::';        // 双栈：IPv6 直连（文档里的 http://[<ipv6>]:9528）和 IPv4 都能进
  let settled = false;
  let resolveReady;
  let rejectReady;
  const ready = new Promise((res, rej) => { resolveReady = res; rejectReady = rej; });

  function listenOnce() {
    server.removeAllListeners('error');
    server.on('error', (e) => {
      if ((e.code === 'EAFNOSUPPORT' || e.code === 'EADDRNOTAVAIL') && host === '::' && !closing) {
        host = '0.0.0.0';                 // 上不了 IPv6 就退回纯 IPv4
        log('IPv6 监听失败（' + e.code + '），退回 0.0.0.0');
        setTimeout(listenOnce, 20).unref?.();
        return;
      }
      if (e.code === 'EADDRINUSE' && attempt < portAttempts && !closing) {
        attempt += 1;
        log('端口 ' + (httpPort + attempt - 1) + ' 被占用，试 ' + (httpPort + attempt));
        setTimeout(listenOnce, 50).unref?.();
        return;
      }
      log('监听失败: ' + e.message);
      if (!settled) { settled = true; rejectReady(e); }
    });
    server.listen(httpPort + attempt, host);
  }

  server.on('listening', () => {
    boundPort = server.address().port;
    if (!settled) { settled = true; resolveReady({ port: boundPort, host }); }
    log('HTTP/WS 监听 ' + (host === '::' ? '[::]' : host) + ':' + boundPort + '（ws 路径 ' + wsPath + '）');
  });

  listenOnce();
  ready.catch(() => { /* 没人 await 也不炸进程 */ });

  // ---------------- 对外句柄
  return {
    get port() { return boundPort; },
    ready,
    get connected() { return !!(conn && conn.alive); },
    /** 向所有已握手的浏览器广播一条 JSON（各自加密发送） */
    broadcast(obj) {
      let n = 0;
      for (const sess of sessions) {
        try { sess.sendJson(obj); n++; } catch (e) { /* 忽略单条失败 */ }
      }
      return n;
    },
    close() {
      closing = true;
      try { if (conn) conn.close(1000, 'server shutdown'); } catch (e) { /* 忽略 */ }
      for (const c of conns) { try { c.close(1000, 'shutdown'); } catch (e) {} } conns.clear();
      sessions.clear();
      try { server.close(); } catch (e) { /* 忽略 */ }
      try { server.closeAllConnections?.(); } catch (e) { /* 忽略 */ }
      log('桥已关闭');
    },
  };
};

// 任何漏网异常只记日志，绝不让宿主进程崩。
// 注意：这是插进程级 handler，若宿主自己已经装过，会先交给它处理，不吞掉宿主逻辑。
(function installGuard() {
  if (globalThis.__dafeiyuWsBridgeGuard) return;
  globalThis.__dafeiyuWsBridgeGuard = true;
  const prev = process.listeners('uncaughtException');
  process.removeAllListeners('uncaughtException');
  process.on('uncaughtException', (e) => {
    try { console.error('[wsbridge] uncaught: ' + (e && e.stack ? e.stack : e)); } catch (_) { /* 忽略 */ }
    for (const fn of prev) {
      try { fn(e); } catch (_) { /* 忽略 */ }
    }
  });
  process.on('unhandledRejection', (e) => {
    try { console.error('[wsbridge] unhandledRejection: ' + (e && e.stack ? e.stack : e)); } catch (_) { /* 忽略 */ }
  });
})();
