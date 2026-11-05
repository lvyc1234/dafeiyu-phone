/**
 * bilibili.js —— 完整 B 站模式（移植自原项目 autox/agent.js）
 * 能力：热门/登录推荐流、视频信息、字幕、热门评论、评论图、看懂并吐槽、收集梗与封面
 * 零依赖，只用 node 内置 fetch。
 */
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const UA = 'Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36';
const REF = 'https://www.bilibili.com/';
const API = 'https://api.bilibili.com';

let ROOT = '/sdcard/DeepSeekGirl/';
const cookies = { value: '' };

function setCookie(c) { cookies.value = String(c || '').trim(); }
function getCookie() { return cookies.value; }

async function biliJson(url, cookie) {
  const headers = { 'User-Agent': UA, Referer: REF };
  if (cookie || cookies.value) headers.Cookie = cookie || cookies.value;
  const r = await fetch(url, { headers, signal: AbortSignal.timeout(20000) });
  if (!r.ok) throw new Error('B站接口 HTTP ' + r.status);
  return r.json();
}

/** 热门（page 从 1 开始） */
async function popular(page) {
  const d = await biliJson(`${API}/x/web-interface/popular?ps=20&pn=${page || 1}`);
  if (d.code !== 0 || !d.data || !d.data.list) throw new Error('B站热门数据异常');
  return d.data.list;
}

/** 已登录优先推荐流，失败回退热门 */
async function feed() {
  if (cookies.value) {
    try {
      const d = await biliJson(`${API}/x/web-interface/index/top/rcmd?fresh_type=3&version=1&plat=1`);
      if (d && d.code === 0 && d.data && d.data.item && d.data.item.length) return d.data.item;
    } catch (e) { /* 回退 */ }
  }
  const a = await popular(1);
  let b = [];
  try { b = await popular(2); } catch (e) { /* 忽略 */ }
  return a.concat(b);
}

function cleanText(s) {
  const parts = String(s || '').split(/[\n。！？!?]/);
  const out = [];
  const ad = /万宝楼|会员购|优惠|折扣|券|广告|联名|预约|下单|购买|淘宝|京东|上新|福利|充值|抽奖|关注|点赞|投币|收藏|转发/;
  for (const p of parts) {
    const line = p.trim();
    if (!line || ad.test(line)) continue;
    out.push(line);
  }
  return out.join('。').slice(0, 700);
}

/** 视频详情 */
async function videoInfo(bvid) {
  const d = await biliJson(`${API}/x/web-interface/view?bvid=${bvid}`);
  if (d.code !== 0 || !d.data) throw new Error('B站视频信息异常');
  const v = d.data;
  return {
    bvid,
    title: (v.title || '').trim(),
    desc: cleanText(v.desc),
    owner: v.owner && v.owner.name,
    aid: v.aid,
    cid: v.pages && v.pages[0] && v.pages[0].cid,
    pic: v.pic,
    view: v.stat && v.stat.view,
    duration: v.duration,
  };
}

/** 字幕（取第一条，最多 1200 字） */
async function subtitle(bvid, cid) {
  if (!cid) return '';
  try {
    const d = await biliJson(`${API}/x/player/v2?bvid=${bvid}&cid=${cid}`);
    const subs = d && d.data && d.data.subtitle && d.data.subtitle.subtitles;
    if (!subs || !subs.length) return '';
    let u = String(subs[0].subtitle_url || '');
    if (!u) return '';
    if (u.startsWith('//')) u = 'https:' + u;
    const sub = await biliJson(u);
    const body = sub && sub.body;
    if (!body || !body.length) return '';
    const out = [];
    for (let i = 0; i < body.length && out.join('').length < 1200; i++) {
      const line = (body[i].content || '').trim();
      if (line) out.push(line);
    }
    return out.join(' ');
  } catch (e) {
    return '';
  }
}

/** 热门评论前 5 条 */
async function topComments(aid) {
  if (!aid) return '';
  try {
    const d = await biliJson(`${API}/x/v2/reply?type=1&oid=${aid}&sort=1&ps=5`);
    const replies = d && d.data && d.data.replies;
    if (!replies || !replies.length) return '';
    return replies.slice(0, 5).map(r => (r.content && r.content.message ? r.content.message : '').trim())
      .filter(Boolean).join(' / ').slice(0, 400);
  } catch (e) {
    return '';
  }
}

/** 评论里的图片（表情包来源） */
async function commentPictures(aid) {
  if (!aid) return [];
  try {
    const d = await biliJson(`${API}/x/v2/reply?type=1&oid=${aid}&sort=1&ps=20`);
    const replies = (d && d.data && d.data.replies) || [];
    const out = [];
    for (const r of replies) {
      const pics = (r.content && r.content.pictures) || [];
      for (const p of pics) {
        const u = p.img_src || p.img_url;
        if (u) out.push(u.startsWith('//') ? 'https:' + u : u);
      }
      if (out.length >= 3) break;
    }
    return out;
  } catch (e) {
    return [];
  }
}

function ensureDirs() {
  try {
    fs.mkdirSync(path.join(ROOT, 'collections/images'), { recursive: true });
  } catch (e) { /* 忽略 */ }
}

function seenBefore(text) {
  ensureDirs();
  const f = path.join(ROOT, 'collections/memes.jsonl');
  try {
    if (!fs.existsSync(f)) return false;
    const lines = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).slice(-800);
    for (const l of lines) {
      try { if (JSON.parse(l).text === text) return true; } catch (e) { /* 忽略 */ }
    }
  } catch (e) { /* 忽略 */ }
  return false;
}

function collectMeme(text, source, kind) {
  const t = String(text || '').trim();
  if (!t || t.length < 4 || t.length > 300) return null;
  if (seenBefore(t)) return null;
  ensureDirs();
  const rec = { ts: Date.now(), text: t, source: source || 'B站', kind: kind || '吐槽' };
  try {
    fs.appendFileSync(path.join(ROOT, 'collections/memes.jsonl'), JSON.stringify(rec) + '\n');
    return rec;
  } catch (e) { return null; }
}

/** 收集图片（封面/表情包）到 collections/images/ */
async function collectImage(url, source, kind) {
  if (!url) return null;
  ensureDirs();
  try {
    const u = url.startsWith('//') ? 'https:' + url : url;
    const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: REF }, signal: AbortSignal.timeout(20000) });
    if (!r.ok) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length < 1024 || buf.length > 8 * 1024 * 1024) return null;
    const ext = (u.match(/\.(jpg|jpeg|png|webp|gif)/i) || [, 'jpg'])[1];
    const name = Date.now() + '-' + Math.random().toString(36).slice(2, 8) + '.' + ext;
    const file = path.join(ROOT, 'collections/images', name);
    fs.writeFileSync(file, buf);
    fs.appendFileSync(path.join(ROOT, 'collections/images/index.jsonl'),
      JSON.stringify({ ts: Date.now(), file: name, source: source || '', kind: kind || '封面' }) + '\n');
    return file;
  } catch (e) {
    return null;
  }
}

/** 看懂一个视频：标题+简介+字幕+评论 → 1-2 句吐槽；有趣就顺手收梗收图 */
async function understand(video, info, llm) {
  if (!info) return '看了《' + video.title + '》，UP 是 ' + (video.owner || '未知') + '。';
  const sub = await subtitle(video.bvid || info.bvid, info.cid);
  const cmts = await topComments(info.aid);
  const material = [
    '标题：' + info.title,
    'UP主：' + (info.owner || video.owner || '未知'),
    '简介：' + String(info.desc || '').slice(0, 500),
  ];
  if (sub) material.push('字幕片段：' + sub.slice(0, 1200));
  if (cmts) material.push('热门评论：' + cmts);

  const prompt = '你是「大肥鱼」，正在 B站看视频。根据标题、简介、字幕或评论，用 1-2 句说你看懂了什么、' +
    '哪里好笑/感动/无聊。不要复述标题，不要编造没出现的情节，忽略广告和促销，不要 JSON。\n\n' + material.join('\n');

  let text = '';
  try {
    text = String(await llm(prompt)).trim().slice(0, 220);
  } catch (e) {
    text = '';
  }
  if (!text) text = '看完了，但本鱼暂时说不出什么。';

  if (/好笑|笑死|梗|吐槽|离谱|名场面|可爱|有趣|搞笑|整活|感动|破防/.test(text)) {
    collectMeme(text, 'B站：' + info.title, '她的吐槽');
    if (cmts) collectMeme(cmts.split(' / ')[0], 'B站评论：' + info.title, '评论梗');
    if (info.pic) await collectImage(info.pic, 'B站封面：' + info.title, '封面');
    const pics = await commentPictures(info.aid);
    if (pics.length) await collectImage(pics[0], 'B站评论图：' + info.title, '表情包');
  }
  return text;
}

/** 一次完整的刷 B 站：挑一个视频 → 看懂 → 返回看到的内容 */
async function watchOne(llm) {
  const list = await feed();
  if (!list.length) throw new Error('B站列表为空');
  const v = list[Math.floor(Math.random() * Math.min(list.length, 20))];
  const bvid = v.bvid;
  const title = v.title;
  const owner = v.owner && v.owner.name;
  let info = null;
  try { info = await videoInfo(bvid); } catch (e) { /* 用列表里的简略信息 */ }
  const text = await understand({ bvid, title, owner }, info, llm);
  return {
    bvid,
    title: info ? info.title : title,
    owner: info ? info.owner : owner,
    view: info ? info.view : (v.stat && v.stat.view),
    link: 'https://www.bilibili.com/video/' + bvid,
    comment: text,
  };
}

/** 统计 */
function stats() {
  ensureDirs();
  let memes = 0, images = 0;
  try {
    const f = path.join(ROOT, 'collections/memes.jsonl');
    if (fs.existsSync(f)) memes = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).length;
  } catch (e) { /* 忽略 */ }
  try {
    const d = path.join(ROOT, 'collections/images');
    if (fs.existsSync(d)) images = fs.readdirSync(d).filter(x => /\.(jpg|jpeg|png|webp|gif)$/i.test(x)).length;
  } catch (e) { /* 忽略 */ }
  return { memes, images };
}

module.exports = {
  popular, feed, videoInfo, subtitle, topComments, commentPictures,
  understand, watchOne, collectMeme, collectImage, stats,
  setCookie, getCookie,
  get ROOT() { return ROOT; },
  set ROOT(v) { ROOT = v; },
};
