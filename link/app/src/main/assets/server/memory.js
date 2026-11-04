#!/usr/bin/env node
/**
 * memory.js —— 「大肥鱼 Link」长期记忆模块
 *
 * 对标原项目 dafeiyu-phone 的 appendMemory / recallMemory / appendFact /
 * mergeFactsOnDisk / dailyReview，零依赖（只用 node 内置模块）。
 *
 * 用法：
 *   const mem = require('./memory');
 *   mem.ROOT = '/tmp/girl-test';                 // 覆盖根目录（默认 /sdcard/DeepSeekGirl/）
 *   mem.appendMemory('user', '在吗', 'app');     // 写当天 chat jsonl
 *   mem.recallMemory('测试');                     // 关键词召回
 *   mem.buildContext(3600);                       // 塞 system prompt 的记忆段
 *
 * 目录布局（全部在 ROOT 下）：
 *   memory/chat/YYYY-MM-DD.jsonl   每天对话原文 {ts, role, content, src}
 *   memory/facts.md                长期事实（人工可编辑，Markdown 列表）
 *   memory/facts_archive.md        被合并/取代/溢出的旧事实归档
 *   memory/self.md                 自我认知（她自己的话）
 *   summaries/YYYY-MM-DD.md        每日回顾
 *   notes/                         她自己的笔记
 *
 * 原则：任何一次磁盘操作都 try/catch；目录不存在就建；不删用户内容。
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------- 配置

const DEFAULT_ROOT = '/sdcard/DeepSeekGirl/';
const _MOD = { ROOT: process.env.DSH_GIRL_ROOT || DEFAULT_ROOT };

const DAY_MS = 86400000;
const RECENT_MAX = 50;          // 内存里保留的最近消息
const FACT_SOFT_LIMIT = 400;    // facts.md 条目软上限，超了搬 100 条到 archive（不删除）
const FACT_ARCHIVE_BATCH = 100;

const SELF_CAP = 1000;          // buildContext 里 self.md 的字符上限
const FACTS_CAP = 1400;         // buildContext 里 facts.md 的字符上限
const OLD_COUNT = 8;            // 旧事条数
const DIARY_LINES = 8;          // 日记行数

// ---------------------------------------------------------------- 底层工具

function root() {
  let r = String(_MOD.ROOT || DEFAULT_ROOT);
  if (!r.endsWith('/')) r += '/';
  return r;
}

/** ROOT 下的路径，参数按段拼 */
function P(...parts) {
  return path.join(root(), ...parts);
}

function ensureDir(dir) {
  try { fs.mkdirSync(dir, { recursive: true }); return true; } catch { return false; }
}

function exists(p) {
  try { return fs.existsSync(p); } catch { return false; }
}

function readText(p) {
  try { return fs.readFileSync(p, 'utf8'); } catch { return ''; }
}

function readBytes(p) {
  try { return fs.statSync(p).size; } catch { return 0; }
}

/** 原子写：同目录 tmp + rename（SELinux 下 link() 被拒，rename 可以） */
function writeAtomic(p, text) {
  try {
    ensureDir(path.dirname(p));
    const tmp = p + '.tmp-' + process.pid;
    fs.writeFileSync(tmp, text, 'utf8');
    fs.renameSync(tmp, p);
    return true;
  } catch {
    try { fs.writeFileSync(p, text, 'utf8'); return true; } catch { return false; }
  }
}

function appendLine(p, line) {
  try {
    ensureDir(path.dirname(p));
    fs.appendFileSync(p, line.endsWith('\n') ? line : line + '\n', 'utf8');
    return true;
  } catch { return false; }
}

// ---------------------------------------------------------------- 时间

function pad(n) { return String(n).padStart(2, '0'); }

/** 本地日期 YYYY-MM-DD */
function dayStr(d) {
  const t = d instanceof Date ? d : new Date(d === undefined ? Date.now() : d);
  if (isNaN(t.getTime())) return dayStr(new Date());
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`;
}

function dayIndexOf(name) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(name);
  if (!m) return NaN;
  return Date.UTC(+m[1], +m[2] - 1, +m[3]) / DAY_MS;
}

function today() { return dayStr(new Date()); }

/** 从今天往回数 n 天的日期字符串数组（含今天，最新在前） */
function lastDays(n) {
  const out = [];
  const base = Date.now();
  for (let i = 0; i < n; i++) out.push(dayStr(base - i * DAY_MS));
  return out;
}

// ---------------------------------------------------------------- 分词（中文简化版）

const SEP = /[\s\u3000,，。！？、；：:;"'"'「」『』（）()\[\]{}<>《》…—\-_!?.~`@#$%^&*+=|\\\/]+/;

/**
 * 简化分词：按空格/标点切开，长度 >= 2 的词算关键词；
 * 中文长词再补二字组合（"喜欢你笑" → "喜欢","欢你","你笑"），
 * 因为中文没有空格，长句不补双字基本检索不到。
 * 每个词带权重：长词 2、普通词 1、双字补丁 0.5。
 */
function tokenize(query) {
  const out = [];
  const seen = new Set();
  const raw = String(query == null ? '' : query).toLowerCase().split(SEP);
  for (const piece of raw) {
    const w = piece.trim();
    if (w.length < 2) continue;
    const isCJK = /[\u4e00-\u9fa5]/.test(w);
    if (!seen.has(w)) { seen.add(w); out.push({ t: w, w: w.length >= 4 ? 2 : 1 }); }
    if (isCJK && w.length > 2) {
      for (let i = 0; i + 1 < w.length; i++) {
        const g = w.slice(i, i + 2);
        if (seen.has(g)) continue;
        seen.add(g);
        out.push({ t: g, w: 0.5 });
      }
    }
  }
  return out;
}

function matchScore(text, toks) {
  if (!text) return 0;
  const hay = String(text).toLowerCase();
  let sc = 0;
  for (const tk of toks) {
    if (hay.indexOf(tk.t) >= 0) sc += tk.w;
  }
  return sc;
}

// ---------------------------------------------------------------- JSONL

function parseJsonl(text) {
  const rows = [];
  for (const raw of String(text).split('\n')) {
    const line = raw.trim();
    if (!line || line[0] !== '{') continue;
    try {
      const o = JSON.parse(line);
      if (o && typeof o === 'object') rows.push(o);
    } catch { /* 坏行跳过 */ }
  }
  return rows;
}

function readChatDay(day) {
  return parseJsonl(readText(P('memory', 'chat', day + '.jsonl')));
}

function chatDaysDesc(limit) {
  let files = [];
  try {
    files = fs.readdirSync(P('memory', 'chat')).filter(f => /^\d{4}-\d{2}-\d{2}\.jsonl$/.test(f));
  } catch { return []; }
  const days = files.map(f => f.slice(0, 10))
    .filter(d => !isNaN(dayIndexOf(d)))
    .sort((a, b) => dayIndexOf(b) - dayIndexOf(a));
  return limit ? days.slice(0, limit) : days;
}

function summaryDaysDesc(limit) {
  let files = [];
  try {
    files = fs.readdirSync(P('summaries')).filter(f => /^\d{4}-\d{2}-\d{2}\.md$/.test(f));
  } catch { return []; }
  const days = files.map(f => f.slice(0, 10))
    .filter(d => !isNaN(dayIndexOf(d)))
    .sort((a, b) => dayIndexOf(b) - dayIndexOf(a));
  return limit ? days.slice(0, limit) : days;
}

// ---------------------------------------------------------------- facts 解析

const HEADING = /^\s{0,3}#{1,6}\s/;
const BULLET = /^\s{0,3}(?:[-*+]|\d+[.)])\s+/;
const CORE_TAG = /^\[core\]\s*/i;

/** facts.md → { header:[非列表行], items:[{text, core}] } */
function parseFacts(raw) {
  const header = [];
  const items = [];
  for (const line of String(raw || '').split('\n')) {
    const s = line.replace(/\r$/, '');
    if (!s.trim()) { if (!items.length) header.push(''); continue; }
    if (BULLET.test(s)) {
      let body = s.replace(BULLET, '').trim();
      let core = false;
      if (CORE_TAG.test(body)) { core = true; body = body.replace(CORE_TAG, '').trim(); }
      if (body) items.push({ text: body, core });
      continue;
    }
    if (!items.length) header.push(s);
  }
  // 去掉尾部空行
  while (header.length && !header[header.length - 1].trim()) header.pop();
  return { header, items };
}

function renderFacts(header, items) {
  const head = header && header.length ? header.join('\n') : '# 长期事实（模型每次都会读，越短越好）';
  const body = items.map(it => `- ${it.core ? '[core] ' : ''}${it.text}`).join('\n');
  return head.trimEnd() + '\n\n' + body + (body ? '\n' : '');
}

function factsPath() { return P('memory', 'facts.md'); }
function archivePath() { return P('memory', 'facts_archive.md'); }
function selfPath() { return P('memory', 'self.md'); }

/** 首次运行时铺好骨架文件（不覆盖已有内容） */
function bootstrap() {
  ensureDir(P('memory'));
  ensureDir(P('memory', 'chat'));
  ensureDir(P('summaries'));
  ensureDir(P('notes'));
  if (!exists(factsPath())) writeAtomic(factsPath(), '# 长期事实（模型每次都会读，越短越好）\n\n');
  if (!exists(archivePath())) writeAtomic(archivePath(), '# 事实归档（被合并 / 被取代 / 溢出搬走，只留不删）\n\n');
  if (!exists(selfPath())) writeAtomic(selfPath(), '# 我是谁\n\n');
}

function archiveItems(items, reason) {
  if (!items || !items.length) return 0;
  ensureDir(P('memory'));
  let raw = readText(archivePath());
  if (!raw.trim()) raw = '# 事实归档（被合并 / 被取代 / 溢出搬走，只留不删）\n\n';
  const stamp = new Date().toISOString().slice(0, 19).replace('T', ' ');
  const lines = items.map(it => `- ${it.core ? '[core] ' : ''}${it.text}  <!-- ${reason} ${stamp} -->`);
  writeAtomic(archivePath(), raw.replace(/\s*$/, '') + '\n' + lines.join('\n') + '\n');
  return items.length;
}

// ---------------------------------------------------------------- 1. appendMemory

const _recent = [];   // 内存里最近 RECENT_MAX 条

/**
 * appendMemory(role, content, src) → 追加当天 chat jsonl，同时维护内存最近 50 条
 * @returns {{ts:number, role:string, content:string, src:string, day:string, file:string, ok:boolean}}
 */
function appendMemory(role, content, src) {
  const rec = {
    ts: Date.now(),
    role: String(role || 'user'),
    content: String(content == null ? '' : content),
    src: String(src || 'local'),
  };
  const day = dayStr(rec.ts);
  const file = P('memory', 'chat', day + '.jsonl');
  if (!rec.content) return { ...rec, day, file, ok: false };

  const ok = appendLine(file, JSON.stringify(rec));

  _recent.push({ ...rec });
  if (_recent.length > RECENT_MAX) _recent.splice(0, _recent.length - RECENT_MAX);

  return { ...rec, day, file, ok };
}

/** 内存里的最近消息（最新在后），n 默认全部（最多 50） */
function recent(n) {
  const k = n > 0 ? n : _recent.length;
  return _recent.slice(Math.max(0, _recent.length - k)).map(o => ({ ...o }));
}

// ---------------------------------------------------------------- 2. recallMemory

/**
 * recallMemory(query, opts) → 关键词检索
 * 扫描：最近 14 天 chat jsonl + facts.md + self.md + 最近 10 天 summaries
 * @param {string} query
 * @param {{limit?:number, days?:number, chatDays?:number, summaryDays?:number, minScore?:number, withSelf?:boolean}} opts
 * @returns {Array<{source:string, text:string, score:number, ts:number, role?:string}>}
 */
function recallMemory(query, opts) {
  const o = opts || {};
  const limit = o.limit > 0 ? Math.floor(o.limit) : 8;
  const days = o.days > 0 ? Math.floor(o.days) : 14;
  const sumDays = o.summaryDays > 0 ? Math.floor(o.summaryDays) : 10;
  const withSelf = o.withSelf !== false;
  const toks = tokenize(query);
  if (!toks.length) return [];

  const hits = [];
  const nowDay = dayIndexOf(today());

  // --- chat
  for (const day of (o.chatDays === 0 ? [] : chatDaysDesc(days))) {
    const rows = readChatDay(day);
    const age = Math.max(0, nowDay - dayIndexOf(day));
    const recency = 1 + 0.12 * ((days - Math.min(age, days)) / days);   // 新的一天略加分
    for (const r of rows) {
      const sc = matchScore(r.content, toks);
      if (sc <= 0) continue;
      hits.push({
        source: 'chat/' + day,
        text: String(r.content).slice(0, 240),
        score: +(sc * recency).toFixed(3),
        ts: Number(r.ts) || 0,
        role: r.role,
      });
    }
  }

  // --- facts.md（每行一条）
  const factsRaw = readText(factsPath());
  const facts = parseFacts(factsRaw);
  for (const it of facts.items) {
    const sc = matchScore(it.text, toks);
    if (sc <= 0) continue;
    hits.push({
      source: 'facts',
      text: it.text.slice(0, 240),
      score: +(sc * 1.5 + (it.core ? 0.5 : 0)).toFixed(3),   // facts 是提炼过的，权重更高
      ts: 0,
    });
  }
  // 也扫 header 里的说明行（人工可能写在标题下面）
  for (const line of facts.header) {
    const sc = matchScore(line, toks);
    if (sc <= 0) continue;
    hits.push({ source: 'facts', text: line.slice(0, 240), score: +(sc * 0.8).toFixed(3), ts: 0 });
  }

  // --- self.md
  if (withSelf) {
    const lines = String(readText(selfPath())).split('\n');
    for (const line of lines) {
      const s = line.replace(BULLET, '').trim();
      if (s.length < 2 || HEADING.test(line)) continue;
      const sc = matchScore(s, toks);
      if (sc <= 0) continue;
      hits.push({ source: 'self', text: s.slice(0, 240), score: +(sc * 1.2).toFixed(3), ts: 0 });
    }
  }

  // --- summaries
  for (const day of summaryDaysDesc(sumDays)) {
    const lines = String(readText(P('summaries', day + '.md'))).split('\n');
    for (const line of lines) {
      const s = line.replace(BULLET, '').replace(/^>\s*/, '').trim();
      if (s.length < 2 || HEADING.test(line)) continue;
      const sc = matchScore(s, toks);
      if (sc <= 0) continue;
      hits.push({
        source: 'summary/' + day,
        text: s.slice(0, 240),
        score: +(sc * 1.1).toFixed(3),
        ts: (dayIndexOf(day) || 0) * DAY_MS,
      });
    }
  }

  // 去重（同 source + 同 text 只留分高的）
  const seen = new Set();
  const uniq = [];
  for (const h of hits.sort((a, b) => b.score - a.score || b.ts - a.ts)) {
    const k = h.source + '\u0000' + h.text;
    if (seen.has(k)) continue;
    seen.add(k);
    uniq.push(h);
  }
  const minScore = o.minScore > 0 ? o.minScore : 0;
  return uniq.filter(h => h.score >= minScore).slice(0, limit);
}

// ---------------------------------------------------------------- 3. buildContext

function clip(s, max) {
  const t = String(s == null ? '' : s).trim();
  if (max <= 0) return '';
  if (t.length <= max) return t;
  return t.slice(0, Math.max(0, max - 6)) + '\n…（略）';
}

/** facts.md → 纯文本块（去标题行、去 bullet、保留 [core] 顺序在前） */
function factsBlock() {
  const { items } = parseFacts(readText(factsPath()));
  if (!items.length) return '';
  const core = items.filter(i => i.core);
  const rest = items.filter(i => !i.core);
  return core.concat(rest).map(i => (i.core ? '★ ' : '· ') + i.text).join('\n');
}

function selfBlock() {
  const lines = String(readText(selfPath())).split('\n')
    .filter(l => l.trim() && !HEADING.test(l));
  return lines.map(l => l.replace(BULLET, '').trim()).join('\n');
}

/** 旧事：今天以前的对话，取最近 n 条 */
function oldMessages(n) {
  const out = [];
  const days = chatDaysDesc(14).filter(d => d !== today());
  for (const day of days) {
    for (const r of readChatDay(day)) {
      const c = String(r.content || '').replace(/\s+/g, ' ').trim();
      if (c.length < 3) continue;
      out.push({ day, role: r.role, text: c, ts: Number(r.ts) || 0 });
    }
  }
  return out.slice(-n);
}

/** 日记：最近 10 天 summaries 的统计行（跳过摘录块），取最后 n 行 */
function diaryLines(n) {
  const out = [];
  const days = summaryDaysDesc(10).reverse();  // 时间正序
  for (const day of days) {
    const lines = String(readText(P('summaries', day + '.md'))).split('\n');
    for (const line of lines) {
      if (HEADING.test(line)) continue;
      if (/^\s*>/.test(line)) continue;                    // 摘录正文不进日记
      if (/^\s*[-*+]\s*摘录/.test(line)) continue;          // "摘录：" 这个标签也不要
      if (/^\s*[-*+]\s*$/.test(line)) continue;
      const s = line.replace(BULLET, '').trim();
      if (!s) continue;
      out.push(day.slice(5) + ' · ' + s);
    }
  }
  return out.slice(-n);
}

/**
 * buildContext(budget) → 系统提示里的记忆段，总长保证 <= budget
 * 优先级：self > facts > 旧事 > 日记（超预算先砍日记，再砍旧事，再砍 facts，最后 self）
 */
function buildContext(budget) {
  let total = Math.floor(Number(budget));
  if (!isFinite(total) || total <= 0) total = 3600;
  total = Math.max(120, total);

  const self = selfBlock();
  const facts = factsBlock();
  const olds = oldMessages(OLD_COUNT);
  const diary = diaryLines(DIARY_LINES);

  const allow = { self: SELF_CAP, facts: FACTS_CAP, oldCount: OLD_COUNT, oldItem: 150, diaryCount: DIARY_LINES, diaryItem: 100 };

  const render = () => {
    const parts = [];
    const s = clip(self, allow.self);
    if (s) parts.push('【我是谁】\n' + s);
    const f = clip(facts, allow.facts);
    if (f) parts.push('【我记得的事】\n' + f);
    const oc = Math.max(0, Math.min(allow.oldCount, olds.length));
    if (oc > 0) {
      const lines = olds.slice(-oc).map(m => {
        const who = m.role === 'user' ? '主人' : (m.role === 'assistant' || m.role === 'bot' ? '本鱼' : '导入');
        return `[${m.day.slice(5)}] ${who}：${clip(m.text, allow.oldItem)}`;
      });
      parts.push('【旧事】\n' + lines.join('\n'));
    }
    const dc = Math.max(0, Math.min(allow.diaryCount, diary.length));
    if (dc > 0) {
      const lines = diary.slice(-dc).map(l => clip(l, allow.diaryItem));
      parts.push('【最近日记】\n' + lines.join('\n'));
    }
    return parts.join('\n\n');
  };

  let ctx = render();
  let guard = 0;
  while (ctx.length > total && guard++ < 60) {
    const over = ctx.length - total;
    if (allow.diaryCount > 0) {
      allow.diaryCount -= 1;
      if (allow.diaryCount <= 0) allow.diaryItem = 0;
    } else if (allow.diaryItem > 0) {
      allow.diaryItem = Math.max(0, allow.diaryItem - over);
    } else if (allow.oldCount > 0) {
      allow.oldCount -= 1;
      if (allow.oldCount <= 0) allow.oldItem = 0;
    } else if (allow.oldItem > 0) {
      allow.oldItem = Math.max(0, allow.oldItem - over);
    } else if (allow.facts > 0) {
      allow.facts = Math.max(0, allow.facts - over);
    } else if (allow.self > 0) {
      allow.self = Math.max(0, allow.self - over);
    } else {
      break;
    }
    ctx = render();
  }
  if (ctx.length > total) ctx = ctx.slice(0, total);   // 硬保证
  return ctx;
}

// ---------------------------------------------------------------- 4. appendFact

/**
 * appendFact(text, opts) → 追加一条长期事实
 * @param {string} text
 * @param {{core?:boolean, dedupe?:boolean, keepAll?:boolean}} opts
 *   core=true 写 `- [core] text`（buildContext 里排最前、永不溢出归档）
 *   keepAll=true 即使超过 400 条也不往 archive 搬
 * @returns {{added:boolean, reason?:string, count:number, core:boolean, archived?:number}}
 */
function appendFact(text, opts) {
  const o = opts || {};
  const body = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
  if (!body) return { added: false, reason: 'empty', count: factsCount(), core: false };

  bootstrap();
  const raw = readText(factsPath());
  const { header, items } = parseFacts(raw);
  const core = !!(o.core || o.pin);
  const norm = normLoose(body);

  if (o.dedupe !== false) {
    for (const it of items) {
      if (normStrict(it.text) === normStrict(body) || normLoose(it.text) === norm) {
        if (core && !it.core) { it.core = true; writeAtomic(factsPath(), renderFacts(header, items)); }
        return { added: false, reason: 'duplicate', count: items.length, core: it.core };
      }
    }
  }

  const it = { text: body, core };
  if (core) {
    const lastCore = items.reduce((acc, x, i) => (x.core ? i : acc), -1);
    items.splice(lastCore + 1, 0, it);   // [core] 排在一起，插在最后一个 core 后面
  } else {
    items.push(it);
  }

  // 超软上限：把最旧的 100 条**非 core** 搬到 archive（不删除，facts 不自动清理内容）
  let archived = 0;
  if (!o.keepAll && items.length > FACT_SOFT_LIMIT) {
    const movable = items.filter(x => !x.core);
    const take = movable.slice(0, FACT_ARCHIVE_BATCH);
    if (take.length) {
      archived = archiveItems(take, 'overflow');
      const drop = new Set(take);
      const kept = items.filter(x => !drop.has(x));
      writeAtomic(factsPath(), renderFacts(header, kept));
      return { added: true, count: kept.length, core, archived };
    }
  }

  writeAtomic(factsPath(), renderFacts(header, items));
  return { added: true, count: items.length, core, archived };
}

function factsCount() {
  return parseFacts(readText(factsPath())).items.length;
}

// ---------------------------------------------------------------- 5. mergeFacts

const PUNCT_ALL = /[\s\u3000,，。！？、；：:;"'"'「」『』（）()\[\]{}<>《》…—\-_!?.~`@#$%^&*+=|\\\/·•]+/g;

function normStrict(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }
function normLoose(s) { return String(s == null ? '' : s).replace(PUNCT_ALL, '').toLowerCase(); }

/**
 * 包含关系判定：命中返回应保留的那条（长的），否则 null
 * 条件：A 归一化后被 B 包含，且长度差 < 短者长度的 50%
 */
function containment(a, b) {
  const A = normLoose(a), B = normLoose(b);
  if (!A || !B) return null;
  if (A === B) return null;
  const short = A.length <= B.length ? A : B;
  const long = A.length <= B.length ? B : A;
  if (long.length === short.length) return null;
  if (long.indexOf(short) < 0) return null;
  const diff = long.length - short.length;
  if (diff >= short.length * 0.5) return null;           // 长度差 >=50% 不算同一件事
  return A.length >= B.length ? a : b;                    // 保留长的
}

/**
 * mergeFacts() → 去重合并 facts.md
 *   1) 完全相同 → 合并
 *   2) 去标点/空格/大小写后相同 → 合并
 *   3) 包含关系且长度差 <50% → 保留长的
 * 合并后被淘汰的条目进 facts_archive.md（带原因和时间戳），[core] 标记 OR 合并。
 * @returns {{before:number, after:number, merged:number, archived:number}}
 */
function mergeFacts() {
  bootstrap();
  const raw = readText(factsPath());
  const { header, items } = parseFacts(raw);
  const before = items.length;
  if (before < 2) return { before, after: before, merged: 0, archived: 0 };

  const kept = [];
  const dropped = [];

  for (const it of items) {
    let replaced = false;
    for (let i = 0; i < kept.length; i++) {
      const k = kept[i];
      const strict = normStrict(k.text) === normStrict(it.text);
      const loose = !strict && normLoose(k.text) === normLoose(it.text);
      if (strict || loose) {
        // 合并：core 取或，文本保留更长的那条
        const longer = k.text.length >= it.text.length ? k.text : it.text;
        dropped.push({ text: k.text.length >= it.text.length ? it.text : k.text, core: it.core && k.core });
        kept[i] = { text: longer, core: !!(k.core || it.core) };
        replaced = true;
        break;
      }
      const win = containment(k.text, it.text);
      if (win) {
        const loser = win === k.text ? it.text : k.text;
        dropped.push({ text: loser, core: (win === k.text ? k.core : it.core) });
        kept[i] = { text: win, core: !!(k.core || it.core) };
        replaced = true;
        break;
      }
    }
    if (!replaced) kept.push({ text: it.text, core: it.core });
  }

  const after = kept.length;
  const merged = before - after;
  const archived = dropped.length ? archiveItems(dropped, 'merged') : 0;
  writeAtomic(factsPath(), renderFacts(header, kept));
  return { before, after, merged, archived };
}

// ---------------------------------------------------------------- 6. dailyReview

const STOP = new Set(['什么', '一个', '这个', '那个', '就是', '不是', '没有', '可以', '我们', '你们',
  '他们', '自己', '现在', '还是', '怎么', '因为', '所以', '但是', '然后', '已经', '知道', '觉得',
  '真的', '一下', '一样', '这样', '那样', '时候', '如果', '还有', '应该', '可能', '一直', '不是',
  '主人', '本鱼', '哈哈', '呵呵', '嗯嗯', '好的', '知道']);

function topWords(texts, n) {
  const freq = new Map();
  const bad = (s) => /^[a-z0-9]+$/.test(s) && s.length < 3;   // 丢掉 hi / ok 这种
  for (const t of texts) {
    const seen = new Set();
    for (const piece of String(t).toLowerCase().split(SEP)) {
      const w = piece.trim();
      if (w.length < 2) continue;
      if (/^\d+$/.test(w)) continue;
      if (/[\u4e00-\u9fa5]/.test(w) && w.length > 2) {
        for (let i = 0; i + 1 < w.length; i++) {
          const g = w.slice(i, i + 2);
          if (STOP.has(g) || seen.has(g) || /\d/.test(g)) continue;
          seen.add(g); freq.set(g, (freq.get(g) || 0) + 1);
        }
      } else if (!STOP.has(w) && !seen.has(w) && !bad(w)) {
        seen.add(w); freq.set(w, (freq.get(w) || 0) + 1);
      }
    }
  }
  return [...freq.entries()]
    .filter(([, c]) => c >= 2)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, n);
}

/**
 * dailyReview(date) → 生成当日回顾（纯数据整理，不调 LLM）
 * @param {string|Date} [date] 'YYYY-MM-DD' / Date / 缺省今天
 * @returns {string} 写出的文件路径；失败返回 ''
 */
function dailyReview(date) {
  let day;
  if (date instanceof Date) day = dayStr(date);
  else if (typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)) day = date;
  else day = today();

  bootstrap();
  const rows = readChatDay(day);
  const users = rows.filter(r => r.role === 'user');
  const bots = rows.filter(r => r.role === 'assistant' || r.role === 'bot');
  const others = rows.filter(r => r.role !== 'user' && r.role !== 'assistant' && r.role !== 'bot');

  const texts = rows.map(r => String(r.content || ''));
  const top = topWords(texts, 10);

  // 摘录：先按长度取前 40%（长的通常更有信息量），再随机抽 3 条
  const pool = users.filter(r => String(r.content || '').trim().length >= 6);
  const byLen = (pool.length ? pool : users).slice()
    .sort((a, b) => String(b.content).length - String(a.content).length);
  const cand = byLen.slice(0, Math.max(3, Math.ceil(byLen.length * 0.4)));
  const picks = [];
  const bag = cand.slice();
  while (picks.length < 3 && bag.length) {
    const i = Math.floor(Math.random() * bag.length);
    picks.push(String(bag.splice(i, 1)[0].content || '').replace(/\s+/g, ' ').trim());
  }

  const lines = [];
  lines.push('# ' + day);
  lines.push(`- 对话 ${rows.length} 条（主人 ${users.length} / 本鱼 ${bots.length}${others.length ? ` / 其它 ${others.length}` : ''}）`);
  lines.push('- 高频：' + (top.length ? top.map(([w, c]) => `${w}(${c})`).join('、') : '（今天没聊出高频词）'));
  if (picks.length) {
    lines.push('- 摘录：');
    for (const p of picks) lines.push('  > ' + clip(p, 90));
  } else {
    lines.push('- 摘录：（今天没有可摘录的对话）');
  }

  const file = P('summaries', day + '.md');
  const ok = writeAtomic(file, lines.join('\n') + '\n');
  return ok ? file : '';
}

// ---------------------------------------------------------------- 7. importFrom

function toRecords(obj) {
  const out = [];
  if (!obj) return out;
  if (Array.isArray(obj)) {
    for (const it of obj) out.push(...toRecords(it));
    return out;
  }
  if (typeof obj === 'string') {
    const s = normStrict(obj);
    if (s) out.push({ role: 'import', content: s });
    return out;
  }
  if (typeof obj !== 'object') return out;

  // Mem0: {memory: "..."} / {data: {memory}} / {text}
  const memText = obj.memory || obj.fact || obj.text || (obj.data && obj.data.memory);
  if (typeof memText === 'string' && memText.trim()) {
    out.push({ role: obj.role === 'assistant' ? 'assistant' : (obj.role || 'import'), content: memText.trim() });
    return out;
  }
  // 标准 message
  if (typeof obj.content === 'string' && obj.content.trim()) {
    out.push({ role: obj.role || 'import', content: obj.content.trim() });
    return out;
  }
  // 容器：messages / results / memories / history
  for (const key of ['messages', 'results', 'memories', 'history', 'items', 'data']) {
    if (obj[key] && obj[key] !== obj) out.push(...toRecords(obj[key]));
  }
  return out;
}

/**
 * importFrom(path) → 从别的记忆系统导入
 * 支持：jsonl（{role,content}）、纯文本/Markdown、Mem0 导出 json 数组
 * 解析后全部写进**当天** chat jsonl。
 * @returns {number} 导入条数
 */
function importFrom(file) {
  const raw = readText(file);
  if (!raw.trim()) return 0;
  bootstrap();

  let recs = [];
  const ext = String(path.extname(file || '')).toLowerCase();
  const looksJson = /^\s*[[{]/.test(raw);

  if (ext === '.jsonl' || (!looksJson && raw.includes('\n{'))) {
    for (const o of parseJsonl(raw)) recs.push(...toRecords(o));
  }
  if (!recs.length && looksJson) {
    try { recs.push(...toRecords(JSON.parse(raw))); } catch { /* 往下走纯文本 */ }
  }
  if (!recs.length) {
    // 纯文本 / Markdown：每行一条，去掉标题、列表符、引用符
    for (const line of raw.split('\n')) {
      const s = line.replace(/\r$/, '');
      if (!s.trim()) continue;
      if (HEADING.test(s)) continue;
      if (/^\s*```/.test(s)) continue;
      const body = s.replace(BULLET, '').replace(/^>\s*/, '').trim();
      if (body.length < 2) continue;
      recs.push({ role: 'import', content: body });
    }
  }

  let n = 0;
  for (const r of recs) {
    const content = normStrict(r.content);
    if (!content) continue;
    const res = appendMemory(r.role || 'import', content, 'import:' + path.basename(String(file)));
    if (res.ok) n++;
  }
  return n;
}

// ---------------------------------------------------------------- 8. stats

function listFiles(dir, re) {
  try { return fs.readdirSync(dir).filter(f => re.test(f)); } catch { return []; }
}

/**
 * stats() → {chatDays, factsCount, selfBytes, summariesCount}
 */
function stats() {
  return {
    chatDays: listFiles(P('memory', 'chat'), /^\d{4}-\d{2}-\d{2}\.jsonl$/).length,
    factsCount: factsCount(),
    selfBytes: readBytes(selfPath()),
    summariesCount: listFiles(P('summaries'), /^\d{4}-\d{2}-\d{2}\.md$/).length,
  };
}

// ---------------------------------------------------------------- 附：notes

/** 她自己的笔记（notes/YYYY-MM-DD.md，追加式） */
function appendNote(text, date) {
  const body = String(text == null ? '' : text).trim();
  if (!body) return '';
  const day = (date instanceof Date ? dayStr(date) : (typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : today()));
  const file = P('notes', day + '.md');
  if (!exists(file)) writeAtomic(file, '# ' + day + ' 笔记\n\n');
  const ts = new Date().toTimeString().slice(0, 5);
  return appendLine(file, `- ${ts} ${body}`) ? file : '';
}

// ---------------------------------------------------------------- 导出

Object.assign(_MOD, {
  // 常量
  ROOT: _MOD.ROOT,
  DEFAULT_ROOT,
  RECENT_MAX,
  FACT_SOFT_LIMIT,

  // 主接口
  appendMemory,
  recallMemory,
  buildContext,
  appendFact,
  mergeFacts,
  mergeFactsOnDisk: mergeFacts,   // 兼容 dafeiyu-phone 的老名字
  dailyReview,
  importFrom,
  stats,

  // 辅助
  recent,
  appendNote,
  tokenize,
  bootstrap,
  paths: () => ({
    root: root(),
    chatDir: P('memory', 'chat'),
    facts: factsPath(),
    archive: archivePath(),
    self: selfPath(),
    summaries: P('summaries'),
    notes: P('notes'),
  }),
});

module.exports = _MOD;
