/**
 * nodes.js —— 节点树 UI 自动化
 * 数据源：App 的无障碍服务（DafeiyuA11y）通过本机调试口 127.0.0.1:19528 暴露，
 * 比 uiautomator dump（Android 14 已移除）可靠，也不用截图走视觉模型。
 */
'use strict';
const net = require('node:net');
const { spawnSync } = require('node:child_process');

const DEBUG_PORT = 19528;
const CLEAN_ENV = Object.assign({}, process.env, { LD_LIBRARY_PATH: '' });

function sh(cmd, timeout = 15000) {
  const r = spawnSync('/system/bin/sh', ['-c', cmd], { env: CLEAN_ENV, timeout, encoding: 'utf8' });
  return { code: r.status, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}

/** 跟 App 调试口说一句话，拿一行回复 */
function ask(cmd, timeout = 4000) {
  return new Promise((resolve) => {
    let done = false;
    const sock = net.connect(DEBUG_PORT, '127.0.0.1');
    const buf = [];
    const finish = (v) => { if (!done) { done = true; try { sock.destroy(); } catch (e) {} resolve(v); } };
    sock.setTimeout(timeout);
    sock.on('connect', () => sock.write(cmd + '\n'));
    sock.on('data', d => buf.push(d));
    sock.on('end', () => finish(buf.join('').trim()));
    sock.on('timeout', () => finish(buf.join('').trim()));
    sock.on('error', () => finish(''));
  });
}

function askSync(cmd, timeout = 4000) {
  const script = `
const net=require('node:net');
const s=net.connect(${DEBUG_PORT},'127.0.0.1');
let out='';let done=false;
const fin=()=>{if(!done){done=true;try{s.destroy()}catch(e){};process.stdout.write(out)}};
s.setTimeout(${timeout});s.on('connect',()=>s.write(${JSON.stringify(cmd + '\n')}));
s.on('data',d=>out+=d);s.on('end',fin);s.on('timeout',fin);s.on('error',fin);`;
  const r = spawnSync(process.execPath, ['-e', script], { timeout: timeout + 2000, encoding: 'utf8' });
  return (r.stdout || '').trim();
}

/** 当前屏幕的节点树（App 无障碍服务提供） */
function dump() {
  const raw = askSync('NODES');
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr.map(n => ({
      text: n.text || '', desc: n.desc || '', cls: n.cls || '', pkg: n.pkg || '',
      clickable: !!n.clickable,
      bounds: { x1: n.x1, y1: n.y1, x2: n.x2, y2: n.y2 },
      center: { x: n.cx, y: n.cy },
    })) : [];
  } catch (e) {
    return [];
  }
}

function ready() {
  const raw = askSync('A11Y');
  try { return !!JSON.parse(raw).ready; } catch (e) { return false; }
}

function findByText(text, opts) {
  const t = String(text || '');
  const exact = opts && opts.exact;
  const hits = dump().filter(n => n.text && (exact ? n.text === t : n.text.indexOf(t) >= 0));
  return (opts && typeof opts.index === 'number') ? (hits[opts.index] || null) : (hits[0] || null);
}

function tapNode(n) {
  if (!n) return { ok: false, reason: 'node not found' };
  const r = sh('input tap ' + n.center.x + ' ' + n.center.y);
  return { ok: r.code === 0, tapped: { text: n.text || n.desc, x: n.center.x, y: n.center.y } };
}

/** 优先用无障碍 performAction 直接点，失败再退坐标注入 */
function tapText(text, opts) {
  const n = findByText(text, opts);
  if (!n) return { ok: false, reason: '未找到包含「' + text + '」的节点' };
  const raw = askSync('CLICK ' + text);
  let clicked = false;
  try { clicked = !!JSON.parse(raw).ok; } catch (e) {}
  if (clicked) return { ok: true, tapped: { text: n.text, via: 'a11y' } };
  return tapNode(n);
}

function tapDesc(desc) {
  const n = dump().find(x => x.desc && x.desc.indexOf(desc) >= 0);
  return tapNode(n) && { ok: true };
}

function tapId(id) {
  const n = dump().find(x => (x.cls || '').indexOf(id) >= 0);
  return tapNode(n);
}

function currentApp() {
  const r = sh('dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -3');
  // 形如: mCurrentFocus=Window{6749329 u0 com.android.settings/com.android.settings.SubSettings}
  const m = r.out.match(/Window\{\s*\d+\s+u0\s+([a-zA-Z][\w.]*)\//);
  if (m) return m[1];
  const m2 = r.out.match(/^\s*([a-zA-Z][\w.]*)\/([a-zA-Z][\w.$]*)/m);
  return m2 ? m2[1] : '';
}

function listApps() {
  const r = sh('pm list packages -3 2>/dev/null');
  if (!r.out) return [];
  return r.out.split('\n').map(l => l.replace(/^package:/, '').trim()).filter(Boolean).map(pkg => ({ pkg, label: pkg }));
}

function openApp(pkg) {
  if (!pkg) return { ok: false, reason: 'no pkg' };
  const r = sh('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ' + pkg + ' | tail -1');
  const comp = (r.out || '').trim();
  if (!comp || comp.indexOf('/') < 0) {
    const m = sh('monkey -p ' + pkg + ' 1');
    return { ok: m.code === 0, via: 'monkey', pkg };
  }
  const s = sh('am start -n ' + comp);
  return { ok: s.code === 0, pkg, component: comp, out: (s.out || s.err).slice(0, 120) };
}

function scroll(dir, times) {
  const n = Math.max(1, Number(times) || 1);
  const y1 = dir === 'up' ? 700 : 1700, y2 = dir === 'up' ? 1700 : 700;
  for (let i = 0; i < n; i++) { sh('input swipe 540 ' + y1 + ' 540 ' + y2 + ' 300'); sh('sleep 0.4'); }
  return { ok: true, times: n, dir: dir || 'down' };
}

module.exports = { dump, findByText, tapText, tapDesc, tapId, listApps, openApp, scroll, currentApp, ready, _sh: sh, _ask: ask };
