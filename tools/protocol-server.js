#!/usr/bin/env node
/**
 * protocol-server.js —— 协议互通自测用的"假服务端"
 * 用 node 内置 crypto 实现与 net_bridge.js 完全相同的握手/帧格式，
 * 用来验证 App 端 Java 实现能否正确互通（真机上换成 AutoX 的 net_bridge.js）。
 *
 * 用法：node protocol-server.js <port> <psk-hex>
 */
'use strict';
const net = require('node:net');
const crypto = require('node:crypto');

const PORT = Number(process.argv[2] || 19527);
const PSK = Buffer.from(process.argv[3], 'hex');
const MAGIC = 0xd1a9;

function hkdf(ikm, salt, info, len) {
  return Buffer.from(crypto.hkdfSync('sha256', ikm, salt, Buffer.from(info), len));
}
function seal(key, nonce, aad, plain) {
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  c.setAAD(aad);
  return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
}
function open(key, nonce, aad, sealed) {
  const body = sealed.subarray(0, sealed.length - 16);
  const tag = sealed.subarray(sealed.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  d.setAAD(aad);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(body), d.final()]);
}
function nonce(dir, counter) {
  const n = Buffer.alloc(12);
  n[0] = dir;
  n.writeUInt32BE(counter >>> 0, 4);
  return n;
}

const server = net.createServer(sock => {
  let buf = Buffer.alloc(0);
  let key = null;
  let outCounter = 0;
  let stage = 'hello';
  console.log('[srv] 接入', sock.remoteAddress);

  function sendRaw(type, plain) {
    const counter = outCounter++;
    const hdr = Buffer.alloc(12);
    hdr.writeUInt16BE(MAGIC, 0);
    hdr[2] = 1; hdr[3] = type;
    hdr.writeUInt32BE(plain.length, 4);
    hdr.writeUInt32BE(counter, 8);
    sock.write(Buffer.concat([hdr, seal(key, nonce(2, counter), hdr, plain)]));
  }

  function sendJson(obj) {
    const plain = Buffer.from(JSON.stringify(obj), 'utf8');
    const counter = outCounter++;
    const hdr = Buffer.alloc(12);
    hdr.writeUInt16BE(MAGIC, 0);
    hdr[2] = 1; hdr[3] = 1;
    hdr.writeUInt32BE(plain.length, 4);
    hdr.writeUInt32BE(counter, 8);
    const sealed = seal(key, nonce(2, counter), hdr, plain);
    sock.write(Buffer.concat([hdr, sealed]));
  }

  sock.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (stage === 'hello') {
        if (buf.length < 21) return;
        const hello = buf.subarray(0, 21);
        buf = buf.subarray(21);
        if (hello.subarray(0, 4).toString() !== 'DLK1') { sock.destroy(); return; }
        const saltC = hello.subarray(5, 21);
        const saltS = crypto.randomBytes(16);
        key = hkdf(PSK, Buffer.concat([saltC, saltS]), 'dafeiyu-link-v1', 32);
        const reply = Buffer.concat([Buffer.from('DLK2'), Buffer.from([1]), saltS]);
        sock.write(reply);
        console.log('[srv] 握手完成 fp=%s', crypto.createHmac('sha256', key).update('fp').digest('hex').slice(0, 8));
        stage = 'frames';
        continue;
      }
      if (buf.length < 12) return;
      const hdr = buf.subarray(0, 12);
      if (hdr.readUInt16BE(0) !== MAGIC) { sock.destroy(); return; }
      const len = hdr.readUInt32BE(4);
      const counter = hdr.readUInt32BE(8);
      if (buf.length < 12 + len + 16) return;
      const sealed = buf.subarray(12, 12 + len + 16);
      buf = buf.subarray(12 + len + 16);
      let plain;
      try {
        plain = open(key, nonce(1, counter), hdr, sealed);
      } catch (e) {
        console.log('[srv] 解密失败（密钥不一致？）', e.message);
        sock.destroy();
        return;
      }
      if (hdr[3] === 2) { sendRaw(3, Buffer.alloc(0)); continue; }
      let j;
      try { j = JSON.parse(plain.toString('utf8')); } catch { continue; }
      if (j.t === 'ping') { sendRaw(3, Buffer.alloc(0)); continue; }
      if (j.t === 'text' || j.t === 'emoji') {
        console.log('[srv] 收到:', j.body);
        sendJson({ t: 'typing', on: true });
        sendJson({ t: 'text', id: 'r' + Date.now(), ts: Math.floor(Date.now() / 1000),
                   body: '收到「' + j.body + '」，本鱼在。' });
        sendJson({ t: 'typing', on: false });
      } else if (j.t === 'image' || j.t === 'voice') {
        console.log('[srv] 收到媒体头:', j.t, j.size, 'bytes /', j.chunks, '块');
      } else if (j.t === 'chunk') {
        console.log('[srv] 收到分块', j.id, 'seq=' + j.seq, j.data.length, 'base64 字符');
      }
    }
  });
  sock.on('close', () => console.log('[srv] 断开'));
  sock.on('error', () => {});
});
server.listen(PORT, '127.0.0.1', () => console.log('[srv] 监听 127.0.0.1:' + PORT));
