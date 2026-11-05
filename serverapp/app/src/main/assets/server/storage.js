/**
 * storage.js —— 存储用量与安全清理（移植自原项目）
 */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const CLEAN_ENV = Object.assign({}, process.env, { LD_LIBRARY_PATH: '' });

function sh(cmd) {
  const r = spawnSync('/system/bin/sh', ['-c', cmd], { env: CLEAN_ENV, timeout: 15000, encoding: 'utf8' });
  return (r.stdout || '').trim();
}

function human(bytes) {
  const b = Number(bytes) || 0;
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b / 1024).toFixed(0) + ' KB';
  if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB';
  return (b / 1073741824).toFixed(2) + ' GB';
}

/** 目录大小（深度限制防卡死） */
function dirSize(p, depth) {
  const d = depth || 0;
  try {
    if (d > 8) return 0;
    const st = fs.statSync(p);
    if (st.isFile()) return st.size;
    let total = 0;
    for (const f of fs.readdirSync(p)) {
      total += dirSize(path.join(p, f), d + 1);
    }
    return total;
  } catch (e) {
    return 0;
  }
}

/** 手机存储 + DeepSeekGirl 占用 */
function info(root) {
  const girl = root || '/sdcard/DeepSeekGirl';
  const df = sh('df -k /sdcard 2>/dev/null | tail -1');
  const parts = df.split(/\s+/);
  const total = parts.length >= 2 ? Number(parts[1]) * 1024 : 0;
  const free = parts.length >= 4 ? Number(parts[3]) * 1024 : 0;
  const used = total - free;
  return {
    total, used, free,
    girl: dirSize(girl, 0),
    text: '手机存储：总 ' + human(total) + '，已用 ' + human(used) +
      '，剩余 ' + human(free) + '；DeepSeekGirl 约 ' + human(dirSize(girl, 0)),
  };
}

/** 各子目录明细 */
function detail(root) {
  const girl = root || '/sdcard/DeepSeekGirl';
  const out = [];
  try {
    for (const name of fs.readdirSync(girl)) {
      const p = path.join(girl, name);
      try {
        if (fs.statSync(p).isDirectory()) out.push({ name, size: dirSize(p, 0) });
        else out.push({ name, size: fs.statSync(p).size });
      } catch (e) { /* 忽略 */ }
    }
  } catch (e) { /* 忽略 */ }
  return out.sort((a, b) => b.size - a.size);
}

/** 安全删除：只允许 GIRL_HOME 及其子目录，且不允许删根 */
function safeDelete(p, root) {
  const girl = path.resolve(root || '/sdcard/DeepSeekGirl');
  const target = path.resolve(p);
  if (!target.startsWith(girl + path.sep)) return { ok: false, reason: '只能删 DeepSeekGirl 里的东西' };
  if (target === girl) return { ok: false, reason: '不能删根目录' };
  if (/memory\/facts\.md$|memory\/self\.md$|persona\.md$/.test(target)) {
    return { ok: false, reason: '人设和记忆不能删' };
  }
  try {
    fs.rmSync(target, { recursive: true, force: true });
    return { ok: true, removed: target };
  } catch (e) {
    return { ok: false, reason: e.message };
  }
}

/** 清理临时文件（保留最近 N 天的每日对话和日记） */
function prune(root, keepDays) {
  const girl = root || '/sdcard/DeepSeekGirl';
  const days = keepDays || 30;
  const cutoff = Date.now() - days * 86400000;
  let removed = 0, freed = 0;
  const scan = (dir, ext) => {
    try {
      for (const f of fs.readdirSync(dir)) {
        const p = path.join(dir, f);
        try {
          const st = fs.statSync(p);
          if (st.isDirectory()) { scan(p, ext); continue; }
          if (!ext.test(f)) continue;
          if (st.mtimeMs < cutoff) { freed += st.size; fs.unlinkSync(p); removed++; }
        } catch (e) { /* 忽略 */ }
      }
    } catch (e) { /* 忽略 */ }
  };
  scan(path.join(girl, 'memory/chat'), /\.jsonl$/);
  scan(path.join(girl, 'summaries'), /\.md$/);
  scan(path.join(girl, 'diary'), /\.md$/);
  return { removed, freed, text: '清理 ' + removed + ' 个文件，释放 ' + human(freed) };
}

module.exports = { human, dirSize, info, detail, safeDelete, prune };
