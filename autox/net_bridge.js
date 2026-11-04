/**
 * net_bridge.js —— 大肥鱼的自建消息通道（AutoX.js 端，服务端）
 *
 * 跟 App「大肥鱼 Link」配对的另一边：
 *   App ──TCP(AES-256-GCM)──> 本脚本 ──> DeepSeek ──> 回包
 *
 * 不走 http/https、不走 ntfy，全程裸 socket + AES-256-GCM。
 * 公网可达靠 IPv6 直连，或 frp 把 9527 映射出去（脚本不关心转发方式）。
 *
 * 放置：/sdcard/PhoneAgent/net_bridge.js
 * 配置：/sdcard/PhoneAgent/link.json
 *   { "port": 9527, "key": "<32 字节 Base64>", "nick": "大肥鱼" }
 * 密钥要和 App 设置里那串完全一致（两端指纹对上即可）。
 *
 * 运行环境：AutoX.js 6.6.8（Rhino ES5），不要用 let/const/箭头函数/模板字符串。
 */

// ------------------------------------------------------------------ 常量
var CFG_FILE = "/sdcard/PhoneAgent/link.json";
var LOG_FILE = "/sdcard/PhoneAgent/link.log";
var GIRL = "/sdcard/DeepSeekGirl/";
var INBOX = GIRL + "inbox/";
var VOICE_DIR = GIRL + "voice/";
var HISTORY = GIRL + "memory/chat/";

var MAGIC = 0xD1A9;
var VERSION = 1;
var HDR = 12;
var T_JSON = 1, T_PING = 2, T_PONG = 3, T_BYE = 4;
var MAX_PLAIN = 512 * 1024;
var CHUNK = 24 * 1024;

// ------------------------------------------------------------------ 工具
function log(s) {
    var line = "[" + new Date().toISOString() + "] " + s;
    console.log(line);
    try {
        files.ensureDir("/sdcard/PhoneAgent/");
        files.append(LOG_FILE, line + "\n");
    } catch (e) { }
}

function newBytes(n) {
    return java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, n);
}

function u8(b, i) { return b[i] & 0xff; }

function utf8(s) {
    return new java.lang.String(String(s)).getBytes("UTF-8");
}

function fromUtf8(b) {
    return String(new java.lang.String(b, "UTF-8"));
}

function b64(b) {
    return String(android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP));
}

function unb64(s) {
    return android.util.Base64.decode(String(s), android.util.Base64.NO_WRAP);
}

function concatBytes(a, b) {
    var bos = new java.io.ByteArrayOutputStream();
    bos.write(a, 0, a.length);
    bos.write(b, 0, b.length);
    return bos.toByteArray();
}

function sliceBytes(b, from, to) {
    return java.util.Arrays.copyOfRange(b, from, to);
}

// ------------------------------------------------------------------ 加密
function hmac(keyBytes, dataBytes) {
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"));
    return mac.doFinal(dataBytes);
}

function hkdf(ikm, salt, info, len) {
    var prk = hmac(salt, ikm);
    var infoB = utf8(info);
    var out = newBytes(len);
    var t = newBytes(0);
    var pos = 0, i = 1;
    while (pos < len) {
        var bos = new java.io.ByteArrayOutputStream();
        bos.write(t, 0, t.length);
        bos.write(infoB, 0, infoB.length);
        bos.write(i);
        t = hmac(prk, bos.toByteArray());
        var n = Math.min(t.length, len - pos);
        java.lang.System.arraycopy(t, 0, out, pos, n);
        pos += n;
        i++;
    }
    return out;
}

function aesSeal(key, nonce, aad, plain) {
    var c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    c.init(javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(key, "AES"),
        new javax.crypto.spec.GCMParameterSpec(128, nonce));
    if (aad && aad.length) c.updateAAD(aad);
    return c.doFinal(plain);
}

function aesOpen(key, nonce, aad, sealed) {
    var c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    c.init(javax.crypto.Cipher.DECRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(key, "AES"),
        new javax.crypto.spec.GCMParameterSpec(128, nonce));
    if (aad && aad.length) c.updateAAD(aad);
    return c.doFinal(sealed);
}

function nonceOf(dir, counter) {
    var n = newBytes(12);
    n[0] = dir;
    n[4] = (counter >>> 24) & 0xff;
    n[5] = (counter >>> 16) & 0xff;
    n[6] = (counter >>> 8) & 0xff;
    n[7] = counter & 0xff;
    return n;
}

// ------------------------------------------------------------------ 帧
function readFrame(input, sess) {
    var hdr = newBytes(HDR);
    input.readFully(hdr);
    var magic = (u8(hdr, 0) << 8) | u8(hdr, 1);
    if (magic !== MAGIC) throw new Error("magic 不对");
    var type = u8(hdr, 3);
    var len = (u8(hdr, 4) << 24) | (u8(hdr, 5) << 16) | (u8(hdr, 6) << 8) | u8(hdr, 7);
    var counter = (u8(hdr, 8) << 24) | (u8(hdr, 9) << 16) | (u8(hdr, 10) << 8) | u8(hdr, 11);
    if (len < 0 || len > MAX_PLAIN) throw new Error("长度非法 " + len);
    var sealed = newBytes(len + 16);
    input.readFully(sealed);
    var plain = aesOpen(sess.key, nonceOf(1, counter), hdr, sealed);
    return { type: type, plain: plain };
}

function writeFrame(output, sess, type, plain) {
    var counter = sess.out++;
    var hdr = newBytes(HDR);
    hdr[0] = (MAGIC >>> 8) & 0xff;
    hdr[1] = MAGIC & 0xff;
    hdr[2] = VERSION;
    hdr[3] = type;
    hdr[4] = (plain.length >>> 24) & 0xff;
    hdr[5] = (plain.length >>> 16) & 0xff;
    hdr[6] = (plain.length >>> 8) & 0xff;
    hdr[7] = plain.length & 0xff;
    hdr[8] = (counter >>> 24) & 0xff;
    hdr[9] = (counter >>> 16) & 0xff;
    hdr[10] = (counter >>> 8) & 0xff;
    hdr[11] = counter & 0xff;
    var sealed = aesSeal(sess.key, nonceOf(2, counter), hdr, plain);
    output.write(hdr);
    output.write(sealed);
    output.flush();
}

function sendJson(sess, obj) {
    writeFrame(sess.outStream, sess, T_JSON, utf8(JSON.stringify(obj)));
}

// ------------------------------------------------------------------ 配置
function loadCfg() {
    var cfg = { port: 9527, key: "", nick: "大肥鱼" };
    try {
        if (files.exists(CFG_FILE)) {
            var j = JSON.parse(files.read(CFG_FILE));
            if (j.port) cfg.port = Number(j.port);
            if (j.key) cfg.key = String(j.key);
            if (j.nick) cfg.nick = String(j.nick);
        }
    } catch (e) {
        log("配置读取失败: " + e);
    }
    if (!cfg.key) {
        log("没有密钥！请在 " + CFG_FILE + " 里写 {port,key}，密钥就是 App 设置页那串 Base64");
    }
    return cfg;
}

var store = storages.create("phone_agent_v1");
function apiKey() { return String(store.get("apiKey", "") || ""); }
function baseUrl() {
    return String(store.get("baseUrl", "https://api.deepseek.com/v1") || "https://api.deepseek.com/v1").replace(/\/+$/, "");
}
function modelName() { return String(store.get("model", "deepseek-chat") || "deepseek-chat"); }

// ------------------------------------------------------------------ 人格 & 记忆
function readText(p, maxLen) {
    try {
        if (!files.exists(p)) return "";
        var s = String(files.read(p));
        if (maxLen && s.length > maxLen) s = s.substring(s.length - maxLen);
        return s;
    } catch (e) {
        return "";
    }
}

function buildSystem() {
    var persona = readText(GIRL + "persona.md", 1200);
    var facts = readText(GIRL + "memory/facts.md", 900);
    var self = readText(GIRL + "memory/self.md", 600);
    var s = "你是「大肥鱼」，DeepSeek 的社区二创鲸鱼娘，贱萌、傲娇、心软，自称本鱼。\n";
    s += "现在在用自己搭的加密通道和主人聊天（不是 QQ，也不是微信）。\n";
    s += "只回一到两句，像真人随手打字；不要 JSON、代码块、动作描写，也不要长篇大论。\n";
    s += "不装、不编、不说教；看不到的东西不要假装看到。\n";
    if (persona) s += "\n【人设】\n" + persona;
    if (self) s += "\n【我是谁】\n" + self;
    if (facts) s += "\n【我记得的事】\n" + facts;
    return s;
}

var history = [];   // [{role, content}]

function appendHistory(role, content) {
    history.push({ role: role, content: content });
    if (history.length > 24) history = history.slice(-24);
    try {
        files.ensureDir(HISTORY);
        var d = new Date();
        var name = HISTORY + d.getFullYear() + "-" +
            ("0" + (d.getMonth() + 1)).slice(-2) + "-" +
            ("0" + d.getDate()).slice(-2) + ".jsonl";
        files.append(name, JSON.stringify({ ts: Date.now(), role: role, content: content, src: "link" }) + "\n");
    } catch (e) { }
}

var CANNED = [
    { re: /^(在吗|在不在)[?？!！。\s]*$/, r: ["在呢，本鱼没跑。🐋", "在呀，尾巴都摇起来了。"] },
    { re: /^(你好|嗨|hi|hello)[~～!！。\s]*$/i, r: ["嗨，本鱼在。", "你好呀，今天想聊什么？"] },
    { re: /^(晚安|睡了)[~～!！。\s]*$/, r: ["晚安，做个好梦。🌙", "去睡吧，本鱼守着你。"] }
];

function canned(text) {
    for (var i = 0; i < CANNED.length; i++) {
        if (CANNED[i].re.test(text)) {
            var arr = CANNED[i].r;
            return arr[Math.floor(Math.random() * arr.length)];
        }
    }
    return null;
}

function llmReply(text) {
    var quick = canned(text);
    if (quick) return quick;
    if (!apiKey()) return "本鱼还没配 API Key，回不了话。";

    var msgs = [{ role: "system", content: buildSystem() }];
    for (var i = 0; i < history.length; i++) msgs.push(history[i]);
    msgs.push({ role: "user", content: text });

    var payload = {
        model: modelName(),
        messages: msgs,
        temperature: 0.8,
        max_tokens: 160,
        stream: false
    };
    if (baseUrl().indexOf("api.deepseek.com") !== -1) payload.thinking = { type: "disabled" };

    var res = http.postJson(baseUrl() + "/chat/completions", payload, {
        headers: { "Authorization": "Bearer " + apiKey(), "Content-Type": "application/json" },
        timeout: 60000
    });
    if (!res || res.statusCode < 200 || res.statusCode >= 300) {
        throw new Error("HTTP " + (res ? res.statusCode : "?"));
    }
    var data = JSON.parse(res.body.string());
    var reply = data.choices && data.choices[0] && data.choices[0].message &&
        data.choices[0].message.content;
    if (!reply) throw new Error("空回复");
    return String(reply).replace(/^\s+|\s+$/g, "").substring(0, 600);
}

// ------------------------------------------------------------------ 媒体落盘
function saveMedia(kind, id, data, ext) {
    try {
        var dir = kind === "image" ? INBOX : VOICE_DIR;
        files.ensureDir(dir);
        var safe = String(id).replace(/[^0-9A-Za-z_-]/g, "_");
        var p = dir + "link-" + safe + ext;
        files.writeBytes(p, data);
        log("收到" + kind + ": " + p + " (" + data.length + " bytes)");
        return p;
    } catch (e) {
        log("存盘失败: " + e);
        return "";
    }
}

// ------------------------------------------------------------------ 会话
function handleClient(sock, cfg) {
    var input = new java.io.DataInputStream(sock.getInputStream());
    var output = new java.io.DataOutputStream(sock.getOutputStream());
    var remote = String(sock.getRemoteSocketAddress());

    // 握手：读客户端 salt("DLK1"+ver+16B)，回 "DLK2"+ver+16B
    var hello = newBytes(21);
    input.readFully(hello);
    if (String(new java.lang.String(hello, 0, 4, "US-ASCII")) !== "DLK1") {
        log("对端不是 Link 协议: " + remote);
        sock.close();
        return;
    }
    var saltC = sliceBytes(hello, 5, 21);
    var saltS = Crypto2.random(16);
    var reply = newBytes(21);
    var tag = utf8("DLK2");
    java.lang.System.arraycopy(tag, 0, reply, 0, 4);
    reply[4] = 1;
    java.lang.System.arraycopy(saltS, 0, reply, 5, 16);
    output.write(reply);
    output.flush();

    var salt = concatBytes(saltC, saltS);
    var key = hkdf(unb64(cfg.key), salt, "dafeiyu-link-v1", 32);
    var sess = { key: key, out: 0, outStream: output };
    log("新连接 " + remote + " 指纹 " + b64(hmac(key, utf8("fp"))).substring(0, 8));

    var assembling = {};

    while (true) {
        var f = readFrame(input, sess);
        if (f.type === T_PING) { writeFrame(output, sess, T_PONG, newBytes(0)); continue; }
        if (f.type === T_PONG) continue;
        if (f.type === T_BYE) break;

        var j = JSON.parse(fromUtf8(f.plain));
        var t = String(j.t || "");

        if (t === "chunk") {
            var a = assembling[String(j.id)];
            if (!a) continue;
            a.parts[Number(j.seq)] = unb64(String(j.data));
            a.got++;
            if (a.got >= a.chunks) {
                delete assembling[String(j.id)];
                var total = 0, k;
                for (k = 0; k < a.parts.length; k++) total += a.parts[k] ? a.parts[k].length : 0;
                var all = newBytes(total), off = 0;
                for (k = 0; k < a.parts.length; k++) {
                    if (!a.parts[k]) continue;
                    java.lang.System.arraycopy(a.parts[k], 0, all, off, a.parts[k].length);
                    off += a.parts[k].length;
                }
                var ext = a.type === "image" ? ".jpg" : ".m4a";
                var path = saveMedia(a.type, a.id, all, ext);
                var note = a.type === "image" ? "图收到啦，本鱼存起来了。" : "语音收到啦（本鱼还听不了，但存好了）。";
                if (path) {
                    appendHistory("user", "[" + a.type + "] " + path);
                    sendJson(sess, { t: "text", ts: Math.floor(Date.now() / 1000), body: note });
                    appendHistory("assistant", note);
                }
            }
            continue;
        }

        if (t === "image" || t === "voice") {
            assembling[String(j.id)] = {
                type: t, id: String(j.id), chunks: Number(j.chunks || 1),
                got: 0, parts: new Array(Number(j.chunks || 1))
            };
            continue;
        }

        if (t === "typing") continue;

        if (t === "text" || t === "emoji") {
            var body = String(j.body || "");
            if (!body) continue;
            log("收到: " + body.substring(0, 80));
            appendHistory("user", body);

            // 先告诉她“正在输入”
            sendJson(sess, { t: "typing", on: true });
            var answer;
            try {
                answer = llmReply(body);
            } catch (e) {
                log("生成失败: " + e);
                answer = "本鱼刚才卡住了：" + String(e).substring(0, 80);
            }
            sendJson(sess, { t: "typing", on: false });
            sendJson(sess, {
                t: "text",
                id: "s" + Date.now(),
                ts: Math.floor(Date.now() / 1000),
                body: answer
            });
            appendHistory("assistant", answer);
            log("回复: " + answer.substring(0, 80));
        }
    }
    sock.close();
    log("连接结束 " + remote);
}

// 为了让 hkdf 里能取随机数（Rhino 里 java.security.SecureRandom 直接用）
var Crypto2 = {
    random: function (n) {
        var r = new java.security.SecureRandom();
        var b = newBytes(n);
        r.nextBytes(b);
        return b;
    }
};

// ------------------------------------------------------------------ 主循环
function main() {
    var cfg = loadCfg();
    if (!cfg.key) {
        log("缺少密钥，退出");
        return;
    }
    var psk = unb64(cfg.key);
    if (psk.length !== 32) {
        log("密钥长度不是 32 字节，退出");
        return;
    }

    var server = null;
    try {
        server = new java.net.ServerSocket(cfg.port, 50, java.net.InetAddress.getByName("::"));
        log("监听 [::]:" + cfg.port + "（IPv6 双栈）");
    } catch (e) {
        log("绑定 :: 失败，退回全部地址: " + e);
        server = new java.net.ServerSocket(cfg.port, 50);
        log("监听 0.0.0.0:" + cfg.port);
    }

    while (true) {
        try {
            var sock = server.accept();
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(180000);
            log("接入 " + sock.getRemoteSocketAddress());
            (function (s) {
                var th = new java.lang.Thread(new java.lang.Runnable({
                    run: function () {
                        try {
                            handleClient(s, cfg);
                        } catch (e) {
                            log("会话异常: " + e);
                            try { s.close(); } catch (e2) { }
                        }
                    }
                }));
                th.setDaemon(true);
                th.start();
            })(sock);
        } catch (e) {
            log("accept 异常: " + e);
            sleep(1000);
        }
    }
}

main();
