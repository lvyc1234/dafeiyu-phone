#!/usr/bin/env node
/**
 * life.js —— 大肥鱼 Link 的「自主生活循环」
 *
 * 她自己决定这段时间做什么（刷b站 / 看天气 / 整理笔记 / 写日记 / 随机逛个 App / 发呆），
 * 不是只有人说话才动。零依赖（只用 node 内建），能力全部由调用方注入：
 * 不 require server.js，避免循环依赖。
 *
 *   const life = require('./tools/life.js');
 *   life.start({ deps: { llm, shell, broadcast, appendMessage, log, memory, nodes, config } });
 *   life.state();          // {running,lastRoundAt,nextRoundAt,lastAction,roundCount}
 *   await life.decide();   // 手动跑一轮（调试用），返回这轮结果
 *   life.stop();
 *
 * 节奏：每轮结束随机等 minGapMin~maxGapMin 分钟；安静时段跳过本轮但继续计时。
 * 记账：/sdcard/DeepSeekGirl/life/log.jsonl，每行一个 {ts,action,reason,ok,detail}。
 * 边界：动作清单里没有任何支付/银行/验证码/删文件/发消息；开 App 前过包名黑名单。
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------- 路径（可用 config.home 覆盖，便于自测）
const DEFAULT_HOME = '/sdcard/DeepSeekGirl/';

function paths(cfg) {
  const home = (cfg && cfg.home) || DEFAULT_HOME;
  const base = home.endsWith('/') ? home : home + '/';
  return {
    home: base,
    lifeDir: path.join(base, 'life'),
    lifeLog: path.join(base, 'life', 'log.jsonl'),
    diaryDir: path.join(base, 'diary'),
    notesDir: path.join(base, 'notes'),
  };
}

// ---------------------------------------------------------------- 默认参数
const DEFAULT_CONFIG = {
  enabled: true,
  home: DEFAULT_HOME,

  minGapMin: 10,          // 每轮之间最短间隔（分钟）
  maxGapMin: 20,          // 每轮之间最长间隔（分钟）
  quietHours: [0, 8],     // 安静时段 [起, 止)，跨零点也支持；相等表示不静默

  biliPkg: 'tv.danmaku.bili',
  maxMinutes: 15,         // 单个动作最多占用多少分钟（防止模型写个 999）
  appDwellMs: 6000,       // 随机逛 App 停留时长
  weatherCmd: 'curl -s -m 8 "https://wttr.in/?format=%l:+%c+%t"',
  echo: true,             // 是否往聊天流里回一条 [自主] 记录（appendMessage type=life）
  llmOpts: { temperature: 0.85, maxTokens: 240 },

  // 硬黑名单：这些包名一律不碰（比较时同时匹配本身与 "前缀." 子包）
  appBlacklist: [
    'com.eg.android.AlipayGphone',   // 支付宝
    'com.tencent.mm',                // 微信
    'com.tencent.mobileqq',          // QQ
    'com.tencent.mm.plugin',
    'com.ali.user.mobile.securityui',
    'com.android.settings',
    'com.android.systemui',
    'com.android.phone',
    'com.android.shell',
    'com.android.mms',
    'com.android.contacts',
    'com.android.dialer',
    'com.android.incallui',
    'com.android.vending',           // 应用商店（能花钱）
    'com.android.documentsui',      // 文件管理（防误删）
    'com.android.permissioncontroller',
    'com.google.android.packageinstaller',
    'com.google.android.gms',
    'com.android.keyguard',
  ],
  // 关键字兜底：含这些字样的包名也不碰
  appBlacklistPattern: /bank|pay|wallet|payment|alipay|unionpay|wechat|tencent\.mm|mobileqq|qrscanner|authenticator|settings|systemui|packageinstaller|permission|keyguard|inputmethod/i,
};

// 候选动作（也是白名单：模型给出清单外的名字，一律按 nothing 处理）
const ACTIONS = {
  browse_bili: { label: '刷b站', run: actBrowseBili, needNodes: true },
  weather:     { label: '看天气', run: actWeather },
  notes:       { label: '整理笔记', run: actNotes },
  diary:       { label: '写日记', run: actDiary },
  random_app:  { label: '随机逛App', run: actRandomApp, needNodes: true },
  nothing:     { label: '发呆', run: actNothing },
};

// 模型可能给的各种别名 → 正规动作名
const ALIAS = {
  bili: 'browse_bili', bilibili: 'browse_bili', 'b站': 'browse_bili', watch: 'browse_bili',
  '刷b站': 'browse_bili', browse: 'browse_bili', video: 'browse_bili',
  '天气': 'weather', weather_check: 'weather',
  note: 'notes', '笔记': 'notes', notes_review: 'notes', organize: 'notes',
  '日记': 'diary', write_diary: 'diary', journal: 'diary',
  app: 'random_app', '逛app': 'random_app', explore: 'random_app', random: 'random_app',
  idle: 'nothing', none: 'nothing', '发呆': 'nothing', rest: 'nothing', sleep: 'nothing', '': 'nothing',
};

// ---------------------------------------------------------------- 运行状态
const S = {
  deps: null,
  cfg: Object.assign({}, DEFAULT_CONFIG),
  running: false,
  timer: null,
  token: null,
  inRound: false,
  lastRoundAt: 0,
  nextRoundAt: 0,
  lastAction: null,
  lastResult: null,
  roundCount: 0,
  skipCount: 0,
};

// ---------------------------------------------------------------- 小工具
function pad2(n) { return String(n).padStart(2, '0'); }
function dateStamp(d) { d = d || new Date(); return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
function hhmm(d) { d = d || new Date(); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
function clip(s, n) { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) + '…' : s; }
function ensureDir(p) { try { fs.mkdirSync(p, { recursive: true }); } catch (e) { /* 忽略 */ } }

function appendText(file, text) {
  ensureDir(path.dirname(file));
  try { fs.appendFileSync(file, text); return true; } catch (e) { return false; }
}

function readIf(file, max) {
  try {
    if (!fs.existsSync(file)) return '';
    const s = fs.readFileSync(file, 'utf8');
    return max ? s.slice(-max) : s;
  } catch (e) { return ''; }
}

function delay(ms) { return new Promise(r => setTimeout(r, Math.max(0, ms | 0))); }

function nlog(msg) {
  const log = S.deps && S.deps.log;
  if (typeof log === 'function') { try { log(msg); return; } catch (e) { /* 忽略 */ } }
  try { console.log(msg); } catch (e) { /* 忽略 */ }
}

/** 解析传进来的东西到底是 {deps} 还是 deps 本身 */
function resolveDeps(input) {
  if (!input || typeof input !== 'object') return null;
  const d = input.deps && typeof input.deps === 'object' ? input.deps : input;
  if (typeof d.llm === 'function' || typeof d.shell === 'function' ||
      typeof d.nodes === 'object' || typeof d.broadcast === 'function') return d;
  return null;
}

function normalizeCfg() {
  const c = S.cfg;
  let qh = c.quietHours;
  if (!Array.isArray(qh) || qh.length < 2) qh = DEFAULT_CONFIG.quietHours;
  c.quietHours = [Number(qh[0]) | 0, Number(qh[1]) | 0];
  c.minGapMin = Math.max(0.05, Number(c.minGapMin) || DEFAULT_CONFIG.minGapMin);
  c.maxGapMin = Math.max(c.minGapMin, Number(c.maxGapMin) || DEFAULT_CONFIG.maxGapMin);
  c.maxMinutes = Math.max(1, Number(c.maxMinutes) || DEFAULT_CONFIG.maxMinutes);
  if (c.appBlacklistPattern && !(c.appBlacklistPattern instanceof RegExp)) {
    try { c.appBlacklistPattern = new RegExp(String(c.appBlacklistPattern), 'i'); } catch (e) { c.appBlacklistPattern = null; }
  }
  if (!Array.isArray(c.appBlacklist)) c.appBlacklist = DEFAULT_CONFIG.appBlacklist.slice();
  return c;
}

function currentCfg(deps) {
  const merged = Object.assign({}, DEFAULT_CONFIG, S.cfg, (deps && deps.config) || {});
  S.cfg = Object.assign({}, S.cfg, merged);
  return normalizeCfg();
}

/** 现在的"几点"——可被 config.now 覆盖（自测要构造安静时段） */
function nowDate(cfg, deps) {
  const n = (cfg && cfg.now) || (deps && deps.now);
  try {
    if (typeof n === 'function') { const v = n(); return v instanceof Date ? v : new Date(v); }
    if (n instanceof Date) return n;
    if (n != null) return new Date(n);
  } catch (e) { /* 落到系统时间 */ }
  return new Date();
}

/** 安静时段判断：[qs,qe) 支持跨零点；qs===qe 视为不静默 */
function isQuiet(dateOrHour, quietHours) {
  const h = typeof dateOrHour === 'number' ? dateOrHour : (dateOrHour || new Date()).getHours();
  const qh = quietHours || DEFAULT_CONFIG.quietHours;
  const qs = Number(qh[0]) | 0, qe = Number(qh[1]) | 0;
  if (qs === qe) return false;
  return qs < qe ? (h >= qs && h < qe) : (h >= qs || h < qe);
}

function nextGapMs(cfg) {
  const min = cfg.minGapMin, max = cfg.maxGapMin;
  const span = Math.max(0, max - min);
  const m = min + Math.random() * span;
  return Math.round(m * 60 * 1000);
}

// ---------------------------------------------------------------- 动作解析（模型输出 → 结构化）
function stripFences(text) {
  const out = [];
  const re = /```[a-zA-Z0-9_-]*\s*([\s\S]*?)```/g;
  let m;
  while ((m = re.exec(text)) !== null) out.push(m[1]);
  return out;
}

/** 从任意文字里抠第一个能解析的 JSON 对象；抠不到返回 null */
function extractJson(text) {
  const s = String(text == null ? '' : text);
  const tries = [];
  tries.push(s.trim());
  for (const f of stripFences(s)) tries.push(f.trim());
  // 平衡括号扫描：从每个 '{' 开始尝试
  for (let i = 0; i < s.length; i++) {
    if (s[i] !== '{') continue;
    let depth = 0, inStr = false, esc = false;
    for (let j = i; j < s.length; j++) {
      const ch = s[j];
      if (inStr) {
        if (esc) esc = false;
        else if (ch === '\\') esc = true;
        else if (ch === '"') inStr = false;
        continue;
      }
      if (ch === '"') { inStr = true; continue; }
      if (ch === '{') depth++;
      else if (ch === '}') { depth--; if (depth === 0) { tries.push(s.slice(i, j + 1)); i = j; break; } }
    }
    if (tries.length > 40) break;
  }
  for (const t of tries) {
    if (!t) continue;
    try {
      const v = JSON.parse(t);
      if (v && typeof v === 'object' && !Array.isArray(v)) return v;
    } catch (e) { /* 换下一个 */ }
  }
  return null;
}

/** 模型回复 → {action, minutes, reason}；任何异常都回落到 nothing，绝不抛 */
function parseAction(text, cfg) {
  const conf = cfg || S.cfg;
  const fallback = { action: 'nothing', minutes: 0, reason: '', raw: clip(text, 160), parsed: false };
  let obj = null;
  try { obj = extractJson(text); } catch (e) { obj = null; }
  if (!obj) return fallback;

  let name = String(obj.action != null ? obj.action : (obj.do || obj.task || '')).trim().toLowerCase();
  if (ALIAS[name]) name = ALIAS[name];
  if (!ACTIONS[name]) {
    // 兜底再试一次：动作名里出现关键词
    if (/bili|b站|视频/.test(name)) name = 'browse_bili';
    else if (/weather|天气/.test(name)) name = 'weather';
    else if (/note|笔记/.test(name)) name = 'notes';
    else if (/diary|日记/.test(name)) name = 'diary';
    else if (/app/.test(name)) name = 'random_app';
    else name = 'nothing';
  }

  let minutes = Number(obj.minutes);
  if (!isFinite(minutes) || minutes <= 0) minutes = name === 'nothing' ? 0 : 5;
  minutes = Math.min(conf.maxMinutes || DEFAULT_CONFIG.maxMinutes, Math.max(0, Math.round(minutes)));

  return {
    action: name,
    minutes: name === 'nothing' ? 0 : minutes,
    reason: clip(obj.reason || obj.thought || obj.why || '', 120),
    parsed: true,
    raw: clip(text, 160),
  };
}

// ---------------------------------------------------------------- 落盘：日记 / 笔记 / 记忆
function appendDiary(cfg, text, tag) {
  const p = paths(cfg);
  const t = String(text || '').trim();
  if (!t) return null;
  const file = path.join(p.diaryDir, dateStamp() + '.md');
  const okWrite = appendText(file, '\n## ' + hhmm() + ' · ' + (tag || '自主') + '\n' + clip(t, 1200) + '\n');
  return okWrite ? file : null;
}

function diaryTail(cfg, days, max) {
  const p = paths(cfg);
  const out = [];
  for (let i = 0; i < (days || 2); i++) {
    const d = new Date(Date.now() - i * 86400000);
    const f = path.join(p.diaryDir, dateStamp(d) + '.md');
    const s = readIf(f, max || 700);
    if (s) out.push('【' + dateStamp(d) + '】\n' + s.trim());
  }
  return out.join('\n\n');
}

function listNotes(cfg, limit) {
  const p = paths(cfg);
  const out = [];
  let names = [];
  try { names = fs.readdirSync(p.notesDir); } catch (e) { return out; }
  for (const n of names) {
    if (!/\.(md|txt)$/i.test(n)) continue;
    const f = path.join(p.notesDir, n);
    let mt = 0;
    try { mt = fs.statSync(f).mtimeMs; } catch (e) { continue; }
    out.push({ name: n, file: f, mtime: mt });
  }
  out.sort((a, b) => b.mtime - a.mtime);
  return out.slice(0, limit || 2);
}

function memAppend(deps, role, text, source) {
  const m = deps && deps.memory;
  if (!m || typeof m.appendMemory !== 'function' || !text) return;
  try { m.appendMemory(role || 'assistant', clip(text, 500), source || 'life'); } catch (e) { /* 忽略 */ }
}

// ---------------------------------------------------------------- App 黑名单
function isBlockedApp(pkg, cfg) {
  const p = String(pkg || '').trim();
  if (!p) return true;
  const list = (cfg && cfg.appBlacklist) || DEFAULT_CONFIG.appBlacklist;
  for (const b of list) if (p === b || p.startsWith(b + '.')) return true;
  const re = (cfg && cfg.appBlacklistPattern) || DEFAULT_CONFIG.appBlacklistPattern;
  if (re && re.test(p)) return true;
  return false;
}

/** listApps() 可能是 [str] / ['pkg\nlabel'] / [{pkg,label}] / {apps:[...]}，统一成 [{pkg,label}] */
function normalizeApps(r) {
  let arr = r;
  if (arr && typeof arr === 'object' && !Array.isArray(arr)) arr = arr.apps || arr.list || arr.items || [];
  if (!Array.isArray(arr)) return [];
  const out = [];
  for (const it of arr) {
    if (!it) continue;
    if (typeof it === 'string') {
      const parts = it.split(/[\t\n|]+/).map(s => s.trim()).filter(Boolean);
      const pkg = (parts.find(x => /^[a-zA-Z][\w.]*\.[\w.]+$/.test(x)) || parts[0] || '').trim();
      const label = parts.find(x => x !== pkg) || pkg;
      if (pkg) out.push({ pkg, label });
    } else if (typeof it === 'object') {
      const pkg = String(it.pkg || it.packageName || it.package || it.id || '').trim();
      if (pkg) out.push({ pkg, label: String(it.label || it.name || it.appName || pkg) });
    }
  }
  return out;
}

// ---------------------------------------------------------------- 屏幕读取（nodes.dump 可能是同步或异步）
async function readScreen(nodes, max) {
  if (!nodes || typeof nodes.dump !== 'function') return '';
  let r;
  try { r = nodes.dump(); } catch (e) { return ''; }
  if (r && typeof r.then === 'function') { try { r = await r; } catch (e) { return ''; } }
  if (r == null) return '';
  if (typeof r === 'string') return clip(r.replace(/\s+/g, ' ').trim(), max || 900);
  try {
    const t = r.text || r.body || r.out || r.content;
    if (typeof t === 'string') return clip(t.replace(/\s+/g, ' ').trim(), max || 900);
    return clip(JSON.stringify(r), max || 900);
  } catch (e) { return ''; }
}

async function safe(fn, fallback) {
  try { return await fn(); } catch (e) { return fallback; }
}

// ---------------------------------------------------------------- 各种动作
async function actBrowseBili(ctx) {
  const { deps, cfg, minutes, nap } = ctx;
  const nodes = deps.nodes;
  if (!nodes || typeof nodes.openApp !== 'function') return { ok: false, detail: '没有 nodes.openApp，开不了b站' };

  const open = await safe(() => nodes.openApp(cfg.biliPkg), { err: 'openApp 抛错' });
  nlog('[life] 打开b站 -> ' + JSON.stringify(open).slice(0, 120));
  await nap(1500);

  const swipes = Math.max(2, Math.min(6, Math.round(minutes / 2) || 2));
  const perSlice = Math.floor((minutes * 60000) / swipes);
  const seen = [];
  for (let i = 0; i < swipes; i++) {
    await nap(Math.max(800, perSlice - 700));
    // 滚一下（nodes 没有 scroll 就用 shell 注入手势）
    const dy = 700 + Math.floor(Math.random() * 300);
    if (typeof nodes.scroll === 'function') await safe(() => nodes.scroll('down'), null);
    else if (typeof deps.shell === 'function') await safe(() => deps.shell('input swipe 540 1600 540 ' + (1600 - dy) + ' ' + (260 + Math.floor(Math.random() * 160))), null);
    await nap(1200);
    const t = await readScreen(nodes, 600);
    if (t) seen.push(t);
  }

  const saw = seen.join('\n').slice(0, 1200) || '（没读到文字，可能还没加载出来）';
  let quip = '';
  if (typeof deps.llm === 'function') {
    quip = String(await safe(() => deps.llm(
      '你刚在b站刷了一会儿，屏幕上看到这些：\n' + saw +
      '\n\n用一句话吐槽一下你看到的（第一人称，别超过30字，不要 JSON、不要引号）。',
      Object.assign({ kind: 'life' }, cfg.llmOpts)
    ), '') || '').trim();
  }
  if (!quip) quip = '看了半天，好像也没记住什么。';

  const what = '刷了会儿b站，看到：' + clip(saw.replace(/\n/g, ' / '), 300) + '\n我：' + clip(quip, 120);
  const diaryFile = appendDiary(cfg, what, 'bili');
  memAppend(deps, 'assistant', what, 'bili');
  return { ok: !!diaryFile, detail: clip('b站 ' + minutes + '分钟；' + quip, 200) };
}

async function actWeather(ctx) {
  const { deps, cfg } = ctx;
  if (typeof deps.shell !== 'function') return { ok: false, detail: '没有 shell，查不了天气' };
  const r = await safe(() => deps.shell(cfg.weatherCmd), null);
  const out = String((r && (r.out || r.stdout)) || '').trim();
  const code = r ? r.code : null;
  if (!out || /unknown location|not found|error|unable|rate limit/i.test(out)) {
    return { ok: false, detail: '天气没拿到：' + clip(out || (r && r.err) || '空返回', 120) };
  }
  if (code != null && code !== 0) return { ok: false, detail: '天气命令退出码 ' + code };
  const line = clip(out.replace(/\s+/g, ' '), 120);
  appendDiary(cfg, '外面的天气：' + line, 'weather');
  memAppend(deps, 'assistant', '看了一眼天气：' + line, 'weather');
  return { ok: true, detail: line };
}

async function actNotes(ctx) {
  const { deps, cfg } = ctx;
  const recent = listNotes(cfg, 2);
  const material = recent.map(n => '【' + n.name + '】\n' + readIf(n.file, 700).trim()).join('\n\n') ||
    '（笔记目录还是空的）';

  let text = '';
  if (typeof deps.llm === 'function') {
    text = String(await safe(() => deps.llm(
      '你在整理自己的笔记。最近的两页是这样：\n' + clip(material, 1400) +
      '\n\n接着这个思路，写一条新的笔记：30~120字，第一人称，可以是想法、待办或一句新学到的东西。' +
      '只输出笔记正文，不要标题、不要 JSON、不要代码块。',
      Object.assign({ kind: 'life' }, cfg.llmOpts)
    ), '') || '').trim();
  }
  if (!text) return { ok: false, detail: recent.length ? '模型没写出笔记' : '没有笔记可翻，模型也没写' };

  const p = paths(cfg);
  ensureDir(p.notesDir);
  const file = path.join(p.notesDir, dateStamp() + '-' + hhmm().replace(':', '') + '.md');
  const head = '# 自主笔记 ' + dateStamp() + ' ' + hhmm() + '\n\n';
  const okWrite = appendText(file, head + clip(text, 1000) + '\n');
  if (okWrite) memAppend(deps, 'assistant', '整理笔记：' + clip(text, 200), 'notes');
  return { ok: okWrite, detail: okWrite ? ('新笔记 ' + path.basename(file) + '（翻了 ' + recent.length + ' 页）') : '笔记写不进去' };
}

async function actDiary(ctx) {
  const { deps, cfg } = ctx;
  const tail = diaryTail(cfg, 1, 600);
  let text = '';
  if (typeof deps.llm === 'function') {
    text = String(await safe(() => deps.llm(
      '现在 ' + dateStamp() + ' ' + hhmm() + '，没人跟你说话，你自己想写两句日记。' +
      (tail ? '\n今天已经写过的：\n' + clip(tail, 700) : '') +
      '\n\n写一两句话，第一人称，像随手记的。只输出正文，不要标题、不要 JSON、不要代码块。',
      Object.assign({ kind: 'life' }, cfg.llmOpts)
    ), '') || '').trim();
  }
  if (!text) return { ok: false, detail: '模型没给出日记正文' };
  const file = appendDiary(cfg, text, '自主');
  memAppend(deps, 'assistant', '日记：' + clip(text, 260), 'life');
  return { ok: !!file, detail: clip(text, 160) };
}

async function actRandomApp(ctx) {
  const { deps, cfg, nap } = ctx;
  const nodes = deps.nodes;
  if (!nodes || typeof nodes.listApps !== 'function') return { ok: false, detail: '没有 nodes.listApps，逛不了' };

  const raw = await safe(() => nodes.listApps(), []);
  const all = normalizeApps(raw);
  const pickable = all.filter(a => !isBlockedApp(a.pkg, cfg));
  if (!pickable.length) return { ok: false, detail: '列表里没有可以逛的 App（共 ' + all.length + ' 个，全被黑名单挡了）' };

  const pick = pickable[Math.floor(Math.random() * pickable.length)];
  const opened = await safe(() => nodes.openApp(pick.pkg), { err: 'openApp 抛错' });
  await nap(1500);
  const first = await readScreen(nodes, 500);
  await nap(Math.max(1500, Math.min(cfg.appDwellMs, 12000)));
  const second = await readScreen(nodes, 500);
  // 退出：有 nodes.back 就用，否则 shell 按 BACK
  if (typeof nodes.back === 'function') await safe(() => nodes.back(), null);
  else if (typeof deps.shell === 'function') await safe(() => deps.shell('input keyevent KEYCODE_BACK'), null);

  const glance = clip([first, second].filter(Boolean).join(' / '), 400) || '（页面很空）';
  const what = '逛了一下「' + pick.label + '」，看到：' + glance;
  appendDiary(cfg, what, 'app');
  memAppend(deps, 'assistant', what, 'app');
  return { ok: true, detail: clip('逛了 ' + pick.label + '（' + pick.pkg + '）', 160) };
}

async function actNothing(ctx) {
  const { deps, cfg, reason } = ctx;
  appendDiary(cfg, '……发呆' + (reason ? '（' + clip(reason, 60) + '）' : ''), '发呆');
  memAppend(deps, 'assistant', '发呆了一会儿' + (reason ? '：' + reason : ''), 'idle');
  return { ok: true, detail: '发呆' + (reason ? '：' + clip(reason, 80) : '') };
}

// ---------------------------------------------------------------- 提示词
function buildPrompt(cfg) {
  const tail = diaryTail(cfg, 2, 400);
  let s = '现在是 ' + dateStamp() + ' ' + hhmm() + '，没人叫你，你自己在过日子。\n';
  s += '想想这一会儿要干什么。能做的事只有下面几个，选一个：\n';
  s += '1. 刷b站 5分钟          -> {"action":"browse_bili","minutes":5}\n';
  s += '2. 看天气               -> {"action":"weather","minutes":1}\n';
  s += '3. 整理笔记             -> {"action":"notes","minutes":5}\n';
  s += '4. 写日记               -> {"action":"diary","minutes":3}\n';
  s += '5. 随机逛一个 App       -> {"action":"random_app","minutes":2}\n';
  s += '6. 什么都不做           -> {"action":"nothing"}\n';
  if (tail) s += '\n你最近写下的东西：\n' + clip(tail, 700) + '\n';
  s += '\n只输出一个 JSON 对象，形如 {"action":"diary","minutes":3,"reason":"一句心里话"}。' +
       '不要输出 JSON 以外的任何字，不要代码块。';
  return s;
}

// ---------------------------------------------------------------- 一轮
function makeNapper(token) {
  return async function nap(ms) {
    let left = Math.max(0, ms | 0);
    while (left > 0) {
      if (token && token.cancelled) break;
      const step = Math.min(1000, left);
      await delay(step);
      left -= step;
    }
  };
}

async function decide(arg, options) {
  const opts = options || {};
  const deps = resolveDeps(arg) || S.deps || {};
  const cfg = currentCfg(deps);

  if (arg && arg.force) opts.force = true;
  if (cfg.enabled === false && !opts.force) {
    return { ts: Date.now(), action: 'nothing', reason: 'disabled', ok: true, skipped: true, detail: 'config.enabled=false' };
  }
  if (S.inRound && !opts.force) {
    return { ts: Date.now(), action: 'nothing', reason: 'busy', ok: true, skipped: true, detail: '上一轮还在跑' };
  }

  const now = nowDate(cfg, deps);

  // 安静时段：跳过本轮，但计时照走
  if (!opts.force && isQuiet(now, cfg.quietHours)) {
    S.skipCount++;
    S.lastRoundAt = Date.now();
    S.lastAction = 'quiet';
    const rec = { ts: Date.now(), action: 'quiet', reason: '安静时段 ' + cfg.quietHours.join('-') + ' 点', ok: true, detail: '跳过本轮（' + now.getHours() + ' 点）' };
    writeLog(cfg, rec);
    S.lastResult = rec;
    nlog('[life] ' + rec.reason + '，本轮跳过，继续等下一轮');
    return Object.assign({ skipped: true, minutes: 0 }, rec);
  }

  // 循环在跑就沿用循环的 token（stop() 能中断动作里的等待）；手动调试给个新 token
  const token = (S.running && S.token && !S.token.cancelled) ? S.token : { cancelled: false };
  const startedAt = Date.now();
  S.inRound = true;
  let parsed = { action: 'nothing', minutes: 0, reason: '', parsed: false, raw: '' };
  let outcome = { ok: true, detail: '' };
  let llmError = '';

  try {
    if (typeof deps.llm !== 'function') {
      llmError = '没有注入 deps.llm';
    } else {
      nlog('[life] 想一想现在要干嘛…');
      let raw = '';
      try {
        raw = await deps.llm(buildPrompt(cfg), Object.assign({ kind: 'life' }, cfg.llmOpts));
      } catch (e) {
        nlog('[life] 模型调用失败: ' + e.message);
        raw = '';
        llmError = '模型调用失败: ' + clip(e.message, 100);
      }
      parsed = parseAction(raw, cfg);
    }

    if (llmError) {
      outcome = { ok: false, detail: llmError };
    } else {
      const spec = ACTIONS[parsed.action] || ACTIONS.nothing;
      nlog('[life] 决定：' + spec.label + (parsed.reason ? '（' + parsed.reason + '）' : ''));
      if (spec.needNodes && (!deps.nodes || typeof deps.nodes !== 'object')) {
        outcome = { ok: false, detail: spec.label + ' 需要 nodes，但没注入' };
      } else {
        const ctx = {
          deps, cfg, now, minutes: parsed.minutes, reason: parsed.reason,
          nap: makeNapper(token), token,
        };
        try {
          outcome = (await spec.run(ctx)) || { ok: true, detail: '' };
        } catch (e) {
          outcome = { ok: false, detail: '执行出错: ' + clip(e.message, 100) };
        }
        if (!parsed.parsed) outcome.detail = clip('模型没给出可用 JSON → 发呆；' + outcome.detail, 200);
      }
    }
  } catch (e) {
    outcome = { ok: false, detail: '轮次异常: ' + clip(e.message, 100) };
  } finally {
    S.inRound = false;
  }

  const rec = {
    ts: Date.now(),
    action: parsed.action,
    reason: parsed.reason || '',
    ok: !!(outcome && outcome.ok),
    detail: String((outcome && outcome.detail) || '').slice(0, 500),
  };
  writeLog(cfg, rec);

  S.lastRoundAt = Date.now();
  S.lastAction = parsed.action;
  S.roundCount++;
  S.lastResult = rec;

  // 推给所有客户端（未知类型 App/Web 会忽略，不会污染聊天流）
  if (typeof deps.broadcast === 'function') {
    try {
      deps.broadcast({ t: 'life', ts: rec.ts, action: rec.action, reason: rec.reason, ok: rec.ok, detail: rec.detail });
    } catch (e) { /* 忽略 */ }
  }
  // 在聊天记录里留一条"她自己动过"的痕迹
  if (cfg.echo !== false && typeof deps.appendMessage === 'function') {
    const label = (ACTIONS[rec.action] || ACTIONS.nothing).label;
    try {
      deps.appendMessage({ from: 'bot', type: 'life', body: '[自主] ' + label + (rec.reason ? '：' + rec.reason : '') + (rec.ok ? '' : '（失败：' + rec.detail + '）') });
    } catch (e) { /* 忽略 */ }
  }

  nlog('[life] 第 ' + S.roundCount + ' 轮完成：' + rec.action + ' ok=' + rec.ok + ' 用时 ' + Math.round((Date.now() - startedAt) / 1000) + 's');
  return Object.assign({ minutes: parsed.minutes, ms: Date.now() - startedAt }, rec);
}

function writeLog(cfg, rec) {
  const p = paths(cfg);
  appendText(p.lifeLog, JSON.stringify(rec) + '\n');
}

// ---------------------------------------------------------------- 定时器
function schedule(ms) {
  if (!S.running) return;
  if (S.timer) { clearTimeout(S.timer); S.timer = null; }
  const wait = Math.max(1000, Math.round(ms));
  S.nextRoundAt = Date.now() + wait;
  S.timer = setTimeout(async () => {
    S.timer = null;
    if (!S.running) return;
    try { await decide(); } catch (e) { nlog('[life] 轮次崩溃: ' + e.message); }
    if (!S.running) return;
    const gap = nextGapMs(S.cfg);
    nlog('[life] 下一轮在 ' + Math.round(gap / 60000) + ' 分钟后');
    schedule(gap);
  }, wait);
  if (S.timer && typeof S.timer.unref === 'function') S.timer.unref();
}

function start(payload) {
  const deps = resolveDeps(payload);
  if (deps) S.deps = deps;
  if (payload && payload.config && !deps) S.cfg = Object.assign({}, S.cfg, payload.config);
  const cfg = currentCfg(deps);

  if (cfg.enabled === false) {
    nlog('[life] config.enabled=false，不启动');
    S.running = false;
    return state();
  }
  if (S.running) {
    nlog('[life] 已经在跑了，改配置直接 stop() 再 start()');
    return state();
  }
  S.running = true;
  S.token = { cancelled: false };
  const boot = Math.max(1000, Number(cfg.bootDelayMs) || 5000);
  nlog('[life] 自主生活已启动：间隔 ' + cfg.minGapMin + '~' + cfg.maxGapMin +
       ' 分钟，安静时段 ' + cfg.quietHours.join('-') + ' 点，首轮 ' + Math.round(boot / 1000) + ' 秒后');
  schedule(boot);
  return state();
}

function stop() {
  S.running = false;
  if (S.token) S.token.cancelled = true;
  if (S.timer) { clearTimeout(S.timer); S.timer = null; }
  S.nextRoundAt = 0;
  nlog('[life] 已停止（共 ' + S.roundCount + ' 轮）');
  return state();
}

function state() {
  return {
    running: S.running,
    lastRoundAt: S.lastRoundAt,
    nextRoundAt: S.nextRoundAt,
    lastAction: S.lastAction,
    roundCount: S.roundCount,
    // 调试用的额外信息
    lastResult: S.lastResult,
    inRound: S.inRound,
    quietSkips: S.skipCount,
    config: {
      minGapMin: S.cfg.minGapMin,
      maxGapMin: S.cfg.maxGapMin,
      quietHours: S.cfg.quietHours,
      enabled: S.cfg.enabled,
      maxMinutes: S.cfg.maxMinutes,
    },
  };
}

module.exports = {
  start,
  stop,
  decide,
  state,
  // 额外导出（调试/自测用，不影响上面的契约）
  _internals: { parseAction, extractJson, isQuiet, nextGapMs, isBlockedApp, normalizeApps, paths, DEFAULT_CONFIG, ACTIONS },
  version: '1.0.0',
};
