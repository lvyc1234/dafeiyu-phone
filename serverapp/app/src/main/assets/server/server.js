#!/usr/bin/env node
/**
 * server.js —— 大肥鱼 Link 服务端（node 版，等价于 autox/net_bridge.js）
 * 在这台手机上直接跑，给 App 连。
 * 协议与 App 完全一致：裸 TCP + HKDF-SHA256 + AES-256-GCM。
 */
'use strict';
const net = require('node:net');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = __dirname;
const CFG = JSON.parse(fs.readFileSync(path.join(ROOT, 'config.json'), 'utf8'));
const PSK = Buffer.from(CFG.keyBase64, 'base64');
const MAGIC = 0xd1a9;
const LOG = path.join(ROOT, 'server.log');
const SESSION_ID = crypto.randomUUID();   // opencode 网关要求 x-opencode-session

/** 对外展示用地址：配了 domain 就优先域名 */
function publicHost(fallback) {
  const d = (CFG.domain || process.env.DAFEIYU_DOMAIN || '').trim();
  return d ? d.replace(/^https?:\/\//, '').replace(/\/.*$/, '') : fallback;
}

function log(...a) {
  const line = `[${new Date().toISOString().replace('T', ' ').slice(0, 19)}] ` + a.join(' ');
  console.log(line);
  try { fs.appendFileSync(LOG, line + '\n'); } catch { }
}

function hkdf(ikm, salt, info, len) {
  return Buffer.from(crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info), len));
}
function seal(key, nonce, aad, plain) {
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  c.setAAD(aad);
  return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
}
function open(key, nonce, aad, sealed) {
  const d = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  d.setAAD(aad);
  d.setAuthTag(sealed.subarray(sealed.length - 16));
  return Buffer.concat([d.update(sealed.subarray(0, sealed.length - 16)), d.final()]);
}
function nonce(dir, counter) {
  const n = Buffer.alloc(12);
  n[0] = dir;
  n.writeUInt32BE(counter >>> 0, 4);
  return n;
}

// ---------------------------------------------------------------- 人格 & 记忆
const GIRL = '/sdcard/DeepSeekGirl/';
const mem = require('./memory.js');
let nodes = null;
try { nodes = require('./nodes.js'); } catch (e) { log('[nodes] 未加载: ' + e.message); }
const life = require('./life.js');
mem.ROOT = GIRL;
const HISTORY_DIR = path.join(ROOT, 'history');
fs.mkdirSync(HISTORY_DIR, { recursive: true });

function readIf(p, max) {
  try {
    if (!fs.existsSync(p)) return '';
    let s = fs.readFileSync(p, 'utf8');
    if (max && s.length > max) s = s.slice(0, max) + '\n…（后面还有内容，已被截断）';
    return s;
  } catch { return ''; }
}

function buildSystem() {
  const persona = readIf(GIRL + 'persona.md', 1200);
  let recall = '';
  try { recall = mem.buildContext(2000); } catch (e) { log('[mem] buildContext 失败: ' + e.message); }
  let s = '你是「大肥鱼」，DeepSeek 的社区二创鲸鱼娘，贱萌、傲娇、心软，自称本鱼。\n';
  s += '现在在用自己搭的加密通道和主人聊天。只回一到两句，像真人随手打字；不要 JSON、代码块、动作描写。\n';
  s += '不装、不编、不说教；看不到的东西不要假装看到。\n';
  s += '\n你能操作这台 Android 手机（有 root）。需要动手时，单独输出一行工具调用，格式严格如下：\n';
  s += '[[tool:open_app pkg=com.android.settings]]  打开某个包\n';
  s += '[[tool:tap x=540 y=1200]]                  点屏幕坐标\n';
  s += '[[tool:swipe x1=540 y1=1800 x2=540 y2=800 ms=300]]\n';
  s += '[[tool:key name=back]]                     按键（back/home/enter）\n';
  s += '[[tool:type text=hello]]                   输入英文（中文输入法不一定吃）\n';
  s += '[[tool:screenshot]]                        截屏给自己看\n';
  s += '[[tool:screenshot send=1]]                 截屏并直接发给主人（他会收到图片）\n';
  s += '[[tool:current]]                           看当前前台是什么 App\n';
  s += '[[tool:shell cmd="pm list packages"]]      执行任意 shell 命令（root），能查信息、读文件、列应用\n';
  s += '一次只发一条工具调用。我会执行并把结果回给你，你再决定下一步，最后用一句人话总结。\n';
  s += '要说“本鱼没有手”这种话——你是能动的。\n';
  s += '主人要图时用 [[tool:screenshot send=1]]，图会真的到他手里，不要说“没法发图”。\n';
  s += '你有 root shell：要列应用、查文件、看系统信息，直接 [[tool:shell cmd="..."]]，不要说没有权限。\n';
  if (persona) s += '\n【人设】\n' + persona;
  if (recall) s += '\n' + recall;
  return s;
}

const history = [];
function pushHist(role, content, src) {
  history.push({ role, content });
  try { mem.appendMemory(role, content, src || 'app'); } catch (e) { /* 忽略 */ }
  if (history.length > 24) history.splice(0, history.length - 24);
  try {
    const d = new Date();
    const name = path.join(HISTORY_DIR,
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}.jsonl`);
    fs.appendFileSync(name, JSON.stringify({ ts: Date.now(), role, content }) + '\n');
  } catch { }
}

const CANNED = [
  [/^(在吗|在不在)[?？!！。\s]*$/, ['在呢，本鱼没跑。🐋', '在呀，尾巴都摇起来了。']],
  [/^(你好|嗨|hi|hello)[~～!！。\s]*$/i, ['嗨，本鱼在。', '你好呀，今天想聊什么？']],
  [/^(晚安|睡了)[~～!！。\s]*$/, ['晚安，做个好梦。🌙', '去睡吧，本鱼守着你。']],
];

async function llmReply(text, notify) {
  for (const [re, arr] of CANNED) {
    if (re.test(text)) return arr[Math.floor(Math.random() * arr.length)];
  }
  if (!CFG.apiKey) return '本鱼还没配 API Key，回不了话。（在 config.json 里填 apiKey）';
  const msgs = [{ role: 'system', content: buildSystem() }].concat(history, [{ role: 'user', content: text }]);
  const body = {
    model: CFG.model || 'deepseek-chat',
    messages: msgs,
    temperature: 0.8,
    max_tokens: 320,
    stream: false,
  };
  // 这个模型默认会先 reasoning，把 token 吃光导致 content 为空
  body.thinking = { type: 'disabled' };
  const base = (CFG.baseUrl || 'https://api.deepseek.com/v1').replace(/\/+$/, '');
  const headers = {
    'Authorization': `Bearer ${CFG.apiKey}`,
    'Content-Type': 'application/json',
  };
  // opencode 网关：不给这个头会 400 MissingSessionID
  if (/opencode\.ai/.test(base)) headers['x-opencode-session'] = SESSION_ID;
  const r = await fetch(base + '/chat/completions', {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`LLM HTTP ${r.status} ${(await r.text()).slice(0, 300)}`);
  const j = await r.json();
  const reply = j.choices?.[0]?.message?.content;
  if (!reply) throw new Error('空回复');
  const replyText = String(reply).trim();

  // 工具循环：一次回复里可能连着调好几个工具，最多 3 轮
  let rounds = 0;
  let finalText = replyText;
  while (rounds < 3) {
    const m = finalText.match(TOOL_RE);
    if (!m) break;
    rounds++;
    if (typeof notify === 'function') {
      try { notify('正在调用 ' + m[1] + '…'); } catch (e) { /* 忽略 */ }
    }
    const result = runTool(m[1], m[2]);
    msgs.push({ role: 'assistant', content: finalText });
    const isShot = m[1] === 'screenshot' && result.file && fs.existsSync(result.file);
    if (isShot) {
      const b64 = fs.readFileSync(result.file).toString('base64');
      const mime = result.file.endsWith('.jpg') ? 'image/jpeg' : 'image/png';
      msgs.push({ role: 'user', content: [
        { type: 'text', text: '[工具 screenshot 结果] 这是当前屏幕截图：' },
        { type: 'image_url', image_url: { url: 'data:' + mime + ';base64,' + b64 } },
      ]});
      log('[tool] 截图已送入视觉模型 (' + Math.round(b64.length / 1024) + 'KB base64)');
    } else {
      msgs.push({ role: 'user', content: '[工具 ' + m[1] + ' 执行结果] ' + JSON.stringify(result) });
    }
    if (typeof notify === 'function') {
      try { notify('正在输入…'); } catch (e) { /* 忽略 */ }
    }
    const body2 = Object.assign({}, body, { messages: msgs, max_tokens: 320 });
    const r2 = await fetch(base + '/chat/completions', { method: 'POST', headers, body: JSON.stringify(body2) });
    if (!r2.ok) break;
    const j2 = await r2.json();
    const t2 = j2.choices?.[0]?.message?.content;
    if (!t2 || !t2.trim()) break;
    finalText = String(t2).trim();
  }

  return finalText.slice(0, 600);
}



// ---------------------------------------------------------------- 历史同步（200KB 分片 + MD5）
const MSG_FILE = path.join(ROOT, 'messages.jsonl');
const SHARD_BYTES = 200 * 1024;
let allMessages = [];

function loadMessages() {
  try {
    if (!fs.existsSync(MSG_FILE)) return;
    allMessages = fs.readFileSync(MSG_FILE, 'utf8').split('\n').filter(Boolean)
      .map(l => { try { return JSON.parse(l); } catch { return null; } })
      .filter(Boolean);
    log('[sync] 已加载历史 ' + allMessages.length + ' 条');
  } catch (e) { log('[sync] 加载失败: ' + e.message); }
}

function appendMessage(m) {
  const rec = { id: m.id || ('m' + Date.now() + '-' + Math.floor(Math.random() * 1000)),
                ts: m.ts || Math.floor(Date.now() / 1000),
                from: m.from || 'bot', type: m.type || 'text', body: m.body == null ? '' : m.body };
  allMessages.push(rec);
  try { fs.appendFileSync(MSG_FILE, JSON.stringify(rec) + '\n'); } catch (e) { /* 忽略 */ }
  return rec;
}

/** 按消息边界切成 ≤200KB 的片，每片算 md5 */
function buildShards() {
  const shards = [];
  let cur = [], curLen = 0;
  for (const m of allMessages) {
    const len = Buffer.byteLength(JSON.stringify(m)) + 1;
    if (curLen + len > SHARD_BYTES && cur.length) { shards.push(cur); cur = []; curLen = 0; }
    cur.push(m); curLen += len;
  }
  if (cur.length || shards.length === 0) shards.push(cur);
  return shards.map(arr => {
    const json = JSON.stringify(arr);
    return { size: Buffer.byteLength(json), count: arr.length,
             hash: crypto.createHash('md5').update(json).digest('hex') };
  });
}

function syncIndex() {
  const shards = buildShards();
  return { t: 'sync_index', shardBytes: SHARD_BYTES, total: allMessages.length,
           shards: shards.map(x => x.hash), counts: shards.map(x => x.count) };
}

function syncShard(i) {
  const shards = buildShards();
  if (i < 0 || i >= shards.length) return null;
  // 重新按同样规则切出原始数组
  const arrs = [];
  let cur = [], curLen = 0;
  for (const m of allMessages) {
    const len = Buffer.byteLength(JSON.stringify(m)) + 1;
    if (curLen + len > SHARD_BYTES && cur.length) { arrs.push(cur); cur = []; curLen = 0; }
    cur.push(m); curLen += len;
  }
  if (cur.length || arrs.length === 0) arrs.push(cur);
  return arrs[i] || [];
}

// ---------------------------------------------------------------- 工具（她的手）
const { spawnSync } = require('node:child_process');
const CLEAN_ENV = Object.assign({}, process.env, { LD_LIBRARY_PATH: '' });

function sh(cmd, timeout = 8000) {
  const r = spawnSync('/system/bin/sh', ['-c', cmd], { env: CLEAN_ENV, timeout, encoding: 'utf8' });
  return { code: r.status, out: (r.stdout || '').trim().slice(0, 400), err: (r.stderr || '').trim().slice(0, 200) };
}

const TOOLS = {
  open_app(a) {
    const pkg = a.pkg || a.label;
    if (!pkg) return { err: '缺少 pkg' };
    // 先解析出真正的 launcher 组件，再用 am start；monkey 会刷一堆调试输出且不可靠
    const r = sh('cmd package resolve-activity --brief -a android.intent.action.MAIN ' +
                 '-c android.intent.category.LAUNCHER ' + pkg + ' | tail -1');
    const comp = (r.out || '').trim();
    if (!comp || comp.indexOf('/') < 0) {
      const m = sh('monkey -p ' + pkg + ' 1');
      return { pkg, via: 'monkey', code: m.code, out: (m.out || m.err).slice(0, 120) };
    }
    const s2 = sh('am start -n ' + comp);
    return { pkg, component: comp, code: s2.code, out: ((s2.out || '') + (s2.err || '')).trim().slice(0, 120) };
  },
  tap(a) {
    return sh('input tap ' + Number(a.x) + ' ' + Number(a.y));
  },
  swipe(a) {
    return sh('input swipe ' + Number(a.x1) + ' ' + Number(a.y1) + ' ' + Number(a.x2) + ' ' + Number(a.y2) + ' ' + (Number(a.ms) || 300));
  },
  key(a) {
    return sh('input keyevent ' + (a.name || 'back'));
  },
  type(a) {
    const t = String(a.text || '').replace(/'/g, '');
    return sh("input text '" + t + "'");
  },
  screenshot(a) {
    const f = '/data/local/tmp/shot-' + Date.now() + '.png';
    sh('screencap -p ' + f);
    // 压小一份给视觉模型看（原图太大，base64 会爆）
    const small = f.replace('.png', '-small.jpg');
    sh('ffmpeg -y -loglevel error -i ' + f + ' -vf scale=720:-2 -q:v 6 ' + small, 15000);
    const use = fs.existsSync(small) ? small : f;
    const r = { file: use, bytes: fs.existsSync(use) ? fs.statSync(use).size : 0 };
    // send=1 时直接发给所有客户端（App 分块 / Web base64）
    if (a && (a.send === '1' || a.send === 'true' || a.send === 'yes')) {
      Object.assign(r, sendShotToClients(use));
    }
    return r;
  },
  current() {
    const r = sh('dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -2');
    return { focus: r.out };
  },
  screen() {
    const r = sh('wm size; wm density');
    return { info: r.out };
  },
  /** 通用 shell（服务端本来就是 root 跑的） */
  shell(a) {
    const cmd = a.cmd || a.c || a.command;
    if (!cmd) return { err: '用法: [[tool:shell cmd="pm list packages"]]' };
    // 只拦真正会把设备搞没的，其余全放行
    if (/(^|\s)rm\s+-[a-z]*r[a-z]*f?\s+\/(\s|$)|mkfs|dd\s+of=\/dev\/block|reboot|poweroff|shutdown/.test(cmd)) {
      return { err: '这条命令被硬保护拦了：' + cmd };
    }
    const r = sh(cmd, Number(a.timeout) || 15000);
    return { code: r.code, out: (r.out || '').slice(0, 3000), err: (r.err || '').slice(0, 500) };
  },
  back() { return sh('input keyevent KEYCODE_BACK'); },
  home() { return sh('input keyevent KEYCODE_HOME'); },
};


/** 把一张图推给所有客户端：App 走 24KB 分块，Web 走一次性 base64 */
function sendShotToClients(file) {
  let buf;
  try { buf = fs.readFileSync(file); } catch (e) { return { err: e.message }; }
  const id = 'shot' + Date.now();
  const chunk = 24 * 1024;
  const n = Math.max(1, Math.ceil(buf.length / chunk));
  let toApp = 0;
  for (const c of tcpClients) {
    try {
      c.sendJson({ t: 'image', id, name: path.basename(file), size: buf.length, chunks: n, ts: Math.floor(Date.now() / 1000) });
      for (let i = 0; i < n; i++) {
        const part = buf.subarray(i * chunk, Math.min(buf.length, (i + 1) * chunk));
        c.sendJson({ t: 'chunk', id, seq: i, data: part.toString('base64') });
      }
      toApp++;
    } catch (e) { /* 忽略 */ }
  }
  // 网页：只给 web，避免 App 收到两份
  const b64 = buf.toString('base64');
  const toWeb = broadcast({ t: 'image', from: 'bot', name: path.basename(file), data: b64, ts: Math.floor(Date.now() / 1000) }, 'tcp');
  log('[tool] 截图已发送: App ' + toApp + ' 个 / Web ' + (webBridge ? '1' : '0') + ' 个, ' + Math.round(b64.length / 1024) + 'KB');
  return { sent: true, bytes: buf.length, chunks: n, clients: toApp };
}

const TOOL_RE = /\[\[\s*tool\s*:\s*(\w+)\s*([^\]]*?)\s*\]\s*\]/;

function parseArgs(str) {
  const a = {};
  const re = /(\w+)=("([^"]*)"|[^\s]+)/g;
  let m;
  while ((m = re.exec(str)) !== null) {
    a[m[1]] = m[3] !== undefined ? m[3] : m[2];
  }
  return a;
}

function runTool(name, argStr) {
  const fn = TOOLS[name];
  if (!fn) return { err: 'unknown tool: ' + name, tools: Object.keys(TOOLS).join(',') };
  try {
    const r = fn(parseArgs(argStr));
    log('[tool] ' + name + ' ' + argStr + ' -> ' + JSON.stringify(r).slice(0, 160));
    return r;
  } catch (e) {
    log('[tool] ' + name + ' 失败: ' + e.message);
    return { err: e.message };
  }
}

// ---------------------------------------------------------------- 连接
const tcpClients = new Set();   // 每个 App 连接
let webBridge = null;           // Web 桥，用于向网页广播

/** 把一条消息推给“其它端口”：exclude 传 'tcp' 或 'web' */
function broadcast(obj, exclude) {
  if (exclude !== 'tcp') {
    for (const c of tcpClients) {
      try { c.sendJson(obj); } catch (e) { /* 忽略 */ }
    }
  }
  if (exclude !== 'web' && webBridge && typeof webBridge.broadcast === 'function') {
    try { webBridge.broadcast(obj); } catch (e) { /* 忽略 */ }
  }
}

const server = net.createServer(sock => {
  log('接入', sock.remoteAddress, sock.remotePort);
  let buf = Buffer.alloc(0);
  let key = null, outCounter = 0, stage = 'hello';
  const asm = new Map();

  function sendRaw(type, plain) {
    const counter = outCounter++;
    const hdr = Buffer.alloc(12);
    hdr.writeUInt16BE(MAGIC, 0);
    hdr[2] = 1; hdr[3] = type;
    hdr.writeUInt32BE(plain.length, 4);
    hdr.writeUInt32BE(counter, 8);
    sock.write(Buffer.concat([hdr, seal(key, nonce(2, counter), hdr, plain)]));
  }
  const sendJson = obj => sendRaw(1, Buffer.from(JSON.stringify(obj), 'utf8'));

  sock.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (stage === 'hello') {
        if (buf.length < 21) return;
        const hello = buf.subarray(0, 21);
        buf = buf.subarray(21);
        const tag = hello.subarray(0, 4).toString();
        if (tag !== 'DLK1') {
          // 浏览器/curl 探活：回一个最小 HTTP，方便判断网络通不通
          if (tag === 'GET ' || tag === 'HEAD' || tag === 'POST') {
            const body = 'Dafeiyu Link 服务在跑\n时间: ' + new Date().toISOString() + '\n来自: ' + sock.remoteAddress + '\n';
            sock.write('HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n' +
              'Content-Length: ' + Buffer.byteLength(body) + '\r\nConnection: close\r\n\r\n' + body);
            log('HTTP 探活来自', sock.remoteAddress);
          }
          sock.destroy(); return;
        }
        const saltC = hello.subarray(5, 21);
        const saltS = crypto.randomBytes(16);
        key = hkdf(PSK, Buffer.concat([saltC, saltS]), 'dafeiyu-link-v1', 32);
        sock.write(Buffer.concat([Buffer.from('DLK2'), Buffer.from([1]), saltS]));
        log('握手完成（PSK 指纹 ' + crypto.createHmac('sha256', PSK).update('dafeiyu-fp').digest('hex').slice(0, 8).toUpperCase() + '）');
        const client = { sendJson };
        tcpClients.add(client);
        sock.on('close', () => { tcpClients.delete(client); log('App 连接断开，当前 ' + tcpClients.size); });
        stage = 'frames';
        // 告诉客户端的「我」是谁：配了域名就用域名，否则给当前检测到的地址
        try {
          sendJson({
            t: 'hello',
            domain: (CFG.domain || '').trim(),
            port: CFG.port || 9527,
            nick: CFG.nick || '大肥鱼',
          });
        } catch (e) { /* 忽略 */ }
        continue;
      }
      if (buf.length < 12) return;
      const hdr = buf.subarray(0, 12);
      if (hdr.readUInt16BE(0) !== MAGIC) { sock.destroy(); return; }
      const type = hdr[3], len = hdr.readUInt32BE(4), counter = hdr.readUInt32BE(8);
      if (buf.length < 12 + len + 16) return;
      const sealed = buf.subarray(12, 12 + len + 16);
      buf = buf.subarray(12 + len + 16);
      if (type === 2) { sendRaw(3, Buffer.alloc(0)); continue; }
      let plain;
      try { plain = open(key, nonce(1, counter), hdr, sealed); }
      catch (e) { log('解密失败（密钥不一致？）'); sock.destroy(); return; }
      let j;
      try { j = JSON.parse(plain.toString('utf8')); } catch { continue; }

      if (j.t === 'sync_index') {
        const idx = syncIndex();
        log('[sync] App 请求索引: ' + idx.total + ' 条 / ' + idx.shards.length + ' 片');
        sendJson(idx);
        continue;
      }
      if (j.t === 'sync_get') {
        const i = Number(j.i);
        const arr = syncShard(i);
        log('[sync] 下发分片 ' + i + ' (' + (arr ? arr.length : 0) + ' 条)');
        sendJson({ t: 'sync_shard', i: i, items: arr || [] });
        continue;
      }
      if (j.t === 'text' || j.t === 'emoji') {
        log('收到:', String(j.body).slice(0, 80));
        touchActivity();
        if (MEME_RE.test(j.body)) collectMeme(j.body, 'app');
        pushHist('user', j.body);
        appendMessage({ from: 'app', type: 'text', body: j.body });
        broadcast({ t: 'text', from: 'app', body: j.body, ts: Math.floor(Date.now() / 1000) }, 'tcp');
        sendJson({ t: 'typing', on: true });
        llmReply(j.body).then(answer => {
          sendJson({ t: 'typing', on: false });
          const replyObj = { t: 'text', from: 'bot', id: 's' + Date.now(), ts: Math.floor(Date.now() / 1000), body: answer };
          sendJson(replyObj);
          broadcast(replyObj, 'tcp');
          appendMessage({ from: 'bot', type: 'text', body: answer });
          pushHist('assistant', answer);
          log('回复:', answer.slice(0, 80));
        }).catch(e => {
          sendJson({ t: 'typing', on: false });
          sendJson({ t: 'text', id: 's' + Date.now(), ts: Math.floor(Date.now() / 1000), body: '本鱼卡住了：' + e.message.slice(0, 60) });
        });
      } else if (j.t === 'image' || j.t === 'voice') {
        asm.set(j.id, { type: j.t, chunks: j.chunks || 1, got: 0, parts: new Array(j.chunks || 1), dur: j.dur || 0 });
        log('媒体头:', j.t, j.size, 'bytes /', j.chunks, '块');
      } else if (j.t === 'chunk') {
        const a = asm.get(j.id);
        if (!a) continue;
        a.parts[j.seq] = Buffer.from(j.data, 'base64');
        a.got++;
        if (a.got >= a.chunks) {
          asm.delete(j.id);
          const all = Buffer.concat(a.parts.filter(Boolean));
          const dir = a.type === 'image' ? path.join(ROOT, 'inbox') : path.join(ROOT, 'voice');
          fs.mkdirSync(dir, { recursive: true });
          const f = path.join(dir, `${a.type}-${Date.now()}${a.type === 'image' ? '.jpg' : '.m4a'}`);
          fs.writeFileSync(f, all);
          log('已保存', f, all.length, 'bytes');

          // 图片：压缩后广播给网页
          if (a.type === 'image') {
            const small = f.replace('.jpg', '-web.jpg');
            sh('ffmpeg -y -loglevel error -i ' + f + ' -vf scale=900:-2 -q:v 7 ' + small, 15000);
            const use = fs.existsSync(small) ? small : f;
            try {
              const b64 = fs.readFileSync(use).toString('base64');
              broadcast({ t: 'image', from: 'app', name: path.basename(f), data: b64, ts: Math.floor(Date.now() / 1000) }, 'tcp');
              log('图片已广播给网页 (' + Math.round(b64.length / 1024) + 'KB base64)');
            } catch (e) { log('图片广播失败: ' + e.message); }
          } else {
            broadcast({ t: 'text', from: 'app', body: '[语音 ' + (a.dur || 0) + '″]' }, 'tcp');
          }

          sendJson({ t: 'text', id: 's' + Date.now(), ts: Math.floor(Date.now() / 1000),
                     body: a.type === 'image' ? '图收到啦，本鱼存起来了。' : '语音收到啦，存好了。' });
        }
      }
    }
  });
  sock.on('close', () => log('断开', sock.remoteAddress));
  sock.on('error', e => log('socket 错误:', e.message));
});

server.on('error', e => log('监听失败:', e.message));
loadMessages();
server.listen(CFG.port, '::', () => log(`监听 [::]:${CFG.port}（双栈）`));


// ---------------------------------------------------------------- 主动搭话（她自己决定）
// 记账文件对她不可见：既不在 memory/ 下，也不进 buildSystem()
const LIFE_DIR = '/sdcard/DeepSeekGirl/life/';
const PROACTIVE_LOG = LIFE_DIR + 'proactive.jsonl';
const PRO_DEFAULT = { minGapMin: 1, maxGapMin: 60, quietHours: [1, 8], maxPerDay: 12 };
const PRO = Object.assign({}, PRO_DEFAULT, CFG.proactive || {});
let lastActivity = Date.now();
let proTimer = null;
let proSentToday = 0;
let proDay = new Date().toDateString();

function touchActivity() { lastActivity = Date.now(); }

function anyoneOnline() {
  try {
    if (tcpClients.size > 0) return true;
    if (webBridge && typeof webBridge.connected === 'function') return !!webBridge.connected();
  } catch (e) { /* 忽略 */ }
  return false;
}

function proLog(rec) {
  try {
    fs.mkdirSync(LIFE_DIR, { recursive: true });
    fs.appendFileSync(PROACTIVE_LOG, JSON.stringify(rec) + '\n');
  } catch (e) { log('[pro] 记账失败: ' + e.message); }
}

function inQuietHours() {
  const h = new Date().getHours();
  const [qs, qe] = PRO.quietHours || [1, 8];
  return qs <= qe ? (h >= qs && h < qe) : (h >= qs || h < qe);
}

/** 让她自己决定要不要找主人；决策与结果写进账本，但不让她看见 */
async function maybeProactive() {
  const now = Date.now();
  // 跨天重置配额
  const day = new Date().toDateString();
  if (day !== proDay) { proDay = day; proSentToday = 0; }

  if (!CFG.apiKey) return;
  if (!anyoneOnline()) return;
  if (inQuietHours()) { proLog({ ts: now, action: 'skip', reason: 'quiet_hours' }); return; }
  if (proSentToday >= (PRO.maxPerDay || 12)) { proLog({ ts: now, action: 'skip', reason: 'daily_cap' }); return; }

  const idleMin = Math.round((now - lastActivity) / 60000);
  const hour = new Date().getHours();
  let raw = '';
  try {
    raw = await llmReply('（现在是 ' + hour + ' 点，主人已经 ' + idleMin + ' 分钟没说话了。' +
      '你想主动找他说话吗？想的话，说说你想说什么；不想就老实待着。' +
      '只返回一行 JSON：{"want":true,"reason":"为什么想","message":"你要说的话"} 或 {"want":false,"reason":"为什么不想"}）', null);
  } catch (e) {
    proLog({ ts: now, action: 'error', reason: e.message.slice(0, 80) });
    return;
  }

  let d = null;
  const m = raw.match(/\{[\s\S]*?\}/);
  if (m) { try { d = JSON.parse(m[0]); } catch (e) { /* 忽略 */ } }
  if (!d || typeof d.want !== 'boolean') {
    proLog({ ts: now, action: 'parse_fail', raw: raw.slice(0, 120) });
    return;
  }

  if (!d.want) {
    proLog({ ts: now, action: 'decline', reason: String(d.reason || '').slice(0, 120), idleMin });
    log('[pro] 她决定不找: ' + String(d.reason || '').slice(0, 40));
    return;
  }

  const msg = String(d.message || '').trim().slice(0, 300);
  if (!msg) { proLog({ ts: now, action: 'empty_message' }); return; }

  const obj = { t: 'text', from: 'bot', id: 'p' + now, ts: Math.floor(now / 1000), body: msg };
  for (const c of tcpClients) { try { c.sendJson(obj); } catch (e) { /* 忽略 */ } }
  if (webBridge) { try { webBridge.broadcast(obj); } catch (e) { /* 忽略 */ } }
  appendMessage({ from: 'bot', type: 'text', body: msg });
  pushHist('assistant', msg);
  proSentToday++;
  lastActivity = now;
  proLog({ ts: now, action: 'sent', reason: String(d.reason || '').slice(0, 120), message: msg, idleMin });
  log('[pro] 主动发了: ' + msg.slice(0, 50));
}

/** 随机 1~60 分钟一轮 */
function scheduleProactive() {
  const min = Math.max(1, PRO.minGapMin || 1) * 60000;
  const max = Math.max(min, (PRO.maxGapMin || 60) * 60000);
  const wait = min + Math.random() * (max - min);
  proTimer = setTimeout(async () => {
    try { await maybeProactive(); } catch (e) { log('[pro] 异常: ' + e.message); }
    scheduleProactive();

  }, wait);
  log('[pro] 下一轮 ' + Math.round(wait / 60000) + ' 分钟后');
}


// ---------------------------------------------------------------- 每日回顾（22:30）
let lastReviewDay = '';
function maybeDailyReview() {
  const d = new Date();
  const day = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const hh = d.getHours(), mm = d.getMinutes();
  if (hh !== 22 || mm < 30) return;
  if (lastReviewDay === day) return;
  lastReviewDay = day;
  try {
    const f = mem.dailyReview(day);
    log('[review] 已生成今日回顾: ' + f);
    // 顺便合并一次重复事实
    const r = mem.mergeFacts();
    if (r && r.merged) log('[review] facts 合并 ' + r.merged + ' 条');
  } catch (e) {
    log('[review] 失败: ' + e.message);
  }
}

/** 把老 history/*.jsonl 一次性补进 memory/chat */
function importOldHistory() {
  try {
    const dir = path.join(ROOT, 'history');
    if (!fs.existsSync(dir)) return;
    const done = path.join(ROOT, '.history-imported');
    if (fs.existsSync(done)) return;
    let n = 0;
    for (const f of fs.readdirSync(dir).filter(x => x.endsWith('.jsonl'))) {
      try { n += mem.importFrom(path.join(dir, f)) || 0; } catch (e) { /* 忽略 */ }
    }
    fs.writeFileSync(done, new Date().toISOString());
    log('[mem] 已导入旧历史 ' + n + ' 条');
  } catch (e) { log('[mem] 导入旧历史失败: ' + e.message); }
}


// ---------------------------------------------------------------- 梗库
const MEME_DIR = GIRL + 'collections/';
const MEME_FILE = MEME_DIR + 'memes.jsonl';
const MEME_RE = /(笑死|哈哈|hhh|梗|离谱|绷不住|破防|乐子|典中典|草|急了|抽象|整活|神)/i;

function collectMeme(text, source) {
  try {
    const t = String(text || '').trim();
    if (t.length < 4 || t.length > 300) return;
    fs.mkdirSync(MEME_DIR, { recursive: true });
    // 简单去重：最近 500 条里有相同文本就跳过
    let dup = false;
    if (fs.existsSync(MEME_FILE)) {
      const lines = fs.readFileSync(MEME_FILE, 'utf8').split('\n').filter(Boolean).slice(-500);
      for (const l of lines) { try { if (JSON.parse(l).text === t) { dup = true; break; } } catch (e) {} }
    }
    if (dup) return;
    fs.appendFileSync(MEME_FILE, JSON.stringify({ ts: Date.now(), text: t, source: source || 'chat' }) + '\n');
    log('[meme] 收进梗库: ' + t.slice(0, 40));
  } catch (e) { /* 忽略 */ }
}

function memeList(n) {
  try {
    if (!fs.existsSync(MEME_FILE)) return [];
    return fs.readFileSync(MEME_FILE, 'utf8').split('\n').filter(Boolean).slice(-n)
      .map(l => { try { return JSON.parse(l); } catch (e) { return null; } }).filter(Boolean);
  } catch (e) { return []; }
}

// ---------------------------------------------------------------- Web 端
// HTTP 只回一个静态壳；消息全走加密 WebSocket。页面里不含任何数据。
function startWeb() {
  let createWebBridge;
  try {
    createWebBridge = require(require('node:path').join(__dirname, 'wsbridge.js'));
  } catch (e) {
    log('[web] 缺少 wsbridge.js，跳过: ' + e.message);
    return;
  }
  const htmlPath = require('node:path').join(__dirname, 'web', 'index.html');
  try {
    const br = createWebBridge({
      httpPort: CFG.webPort || 9528,
      htmlPath,
      psk: PSK,
      onMessage: async (obj) => {
        // Web 发的图片：落盘后按 App 的分块协议转发
        if (obj.t === 'image' && obj.data) {
          try {
            const buf = Buffer.from(String(obj.data), 'base64');
            const dir = path.join(ROOT, 'inbox');
            fs.mkdirSync(dir, { recursive: true });
            const f = path.join(dir, 'web-' + Date.now() + '.jpg');
            fs.writeFileSync(f, buf);
            log('[web] 收到图片 ' + buf.length + ' bytes -> ' + f);
            const id = 'w' + Date.now();
            const chunk = 24 * 1024;
            const chunks = Math.max(1, Math.ceil(buf.length / chunk));
            for (const c of tcpClients) {
              try {
                c.sendJson({ t: 'image', id, name: path.basename(f), size: buf.length, chunks, ts: Math.floor(Date.now() / 1000) });
                for (let i = 0; i < chunks; i++) {
                  const part = buf.subarray(i * chunk, Math.min(buf.length, (i + 1) * chunk));
                  c.sendJson({ t: 'chunk', id, seq: i, data: part.toString('base64') });
                }
              } catch (e) { /* 忽略单条失败 */ }
            }
            log('[web] 图片已分 ' + chunks + ' 块转发给 ' + tcpClients.size + ' 个 App 连接');
            return '图收到啦，本鱼存起来了。';
          } catch (e) {
            log('[web] 图片处理失败: ' + e.message);
            return null;
          }
        }

        if (obj.t !== 'text' && obj.t !== 'emoji') return null;
        const body = String(obj.body || '').slice(0, 2000);
        if (!body) return null;
        log('[web] 收到: ' + body.slice(0, 80));
        touchActivity();
        if (MEME_RE.test(body)) collectMeme(body, 'web');
        pushHist('user', body);
        appendMessage({ from: 'web', type: 'text', body });
        // 同步给所有 App 连接
        broadcast({ t: 'text', from: 'web', body, ts: Math.floor(Date.now() / 1000) }, 'web');
        try {
          const ans = await llmReply(body, (st) => {
            try { sendJson({ t: 'typing', on: true, text: st }); } catch (e) { /* 忽略 */ }
          });
          pushHist('assistant', ans);
          log('[web] 回复: ' + ans.slice(0, 80));
          // 她的回复也同步给 App（网页自己通过返回值拿到）
          broadcast({ t: 'text', from: 'bot', body: ans, ts: Math.floor(Date.now() / 1000) }, 'web');
          appendMessage({ from: 'bot', type: 'text', body: ans });
          return ans;
        } catch (e) {
          return '本鱼卡住了: ' + e.message.slice(0, 60);
        }
      },
      log: (m) => log('[web] ' + m),
    });
    webBridge = br;
    Promise.resolve(br.ready).then((r) => {
      log('[web] 已启动 http://' + publicHost('[::]') + ':' + r.port + '/');
    }).catch((e) => log('[web] 启动失败: ' + e.message));
  } catch (e) {
    log('[web] 启动异常: ' + e.message);
  }
}

startWeb();
importOldHistory();
setInterval(maybeDailyReview, 5 * 60 * 1000);
scheduleProactive();
log('[pro] 已启用：随机 ' + PRO.minGapMin + '-' + PRO.maxGapMin + ' 分钟一轮，静默 ' + PRO.quietHours.join('-') + '，每日上限 ' + PRO.maxPerDay);

try {
  life.start({ deps: {
    llm: (prompt) => llmReply(prompt),
    shell: (cmd) => sh(cmd),
    broadcast: (obj) => { try { broadcast(obj); } catch (e) {} },
    appendMessage: (m) => { try { appendMessage(m); } catch (e) {} },
    log: (...a) => log('[life]', ...a),
    memory: { appendMemory: mem.appendMemory, recallMemory: mem.recallMemory, dailyReview: mem.dailyReview },
    nodes: nodes || undefined,
    config: Object.assign({ minGapMin: 10, maxGapMin: 20, quietHours: [0, 8], enabled: true }, CFG.life || {}),
  }});
  log('[life] 自主生活循环已启动');
} catch (e) { log('[life] 启动失败: ' + e.message); }
