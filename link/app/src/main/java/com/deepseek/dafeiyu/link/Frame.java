package com.deepseek.dafeiyu.link;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 帧格式（全部字段网络序）：
 *   0  magic   u16  0xD1A9
 *   2  version u8   1
 *   3  type    u8   1=JSON 2=PING 3=PONG 4=BYE
 *   4  length  u32  明文长度
 *   8  counter u32  帧序号
 *   12 ciphertext[length + 16]  AES-256-GCM
 *
 * AAD = 前 12 字节头，防篡改头部。
 * nonce = 方向字节 + 3 个 0 + counter(8 字节)，每方向独立计数。
 */
public final class Frame {

    public static final int MAGIC = 0xD1A9;
    public static final int VERSION = 1;
    public static final int HEADER_LEN = 12;
    public static final int MAX_PLAIN = 512 * 1024;

    public static final int T_JSON = 1;
    public static final int T_PING = 2;
    public static final int T_PONG = 3;
    public static final int T_BYE = 4;

    private Frame() {}

    public static byte[] nonce(int dirByte, int counter) {
        byte[] n = new byte[Crypto.NONCE_LEN];
        n[0] = (byte) dirByte;
        n[4] = (byte) (counter >>> 24);
        n[5] = (byte) (counter >>> 16);
        n[6] = (byte) (counter >>> 8);
        n[7] = (byte) counter;
        return n;
    }

    public static byte[] buildHeader(int type, int plainLen, int counter) throws IOException {
        byte[] h = new byte[HEADER_LEN];
        h[0] = (byte) (MAGIC >>> 8);
        h[1] = (byte) MAGIC;
        h[2] = (byte) VERSION;
        h[3] = (byte) type;
        h[4] = (byte) (plainLen >>> 24);
        h[5] = (byte) (plainLen >>> 16);
        h[6] = (byte) (plainLen >>> 8);
        h[7] = (byte) plainLen;
        h[8] = (byte) (counter >>> 24);
        h[9] = (byte) (counter >>> 16);
        h[10] = (byte) (counter >>> 8);
        h[11] = (byte) counter;
        return h;
    }

    public static void write(DataOutputStream out, Session s, int type, byte[] plain) throws IOException {
        if (plain.length > MAX_PLAIN) throw new IOException("帧太大");
        int counter = s.nextOut();
        int dir = s.isClient() ? 1 : 2;
        byte[] header = buildHeader(type, plain.length, counter);
        byte[] nonce = nonce(dir, counter);
        byte[] sealed = Crypto.seal(s.key(), nonce, header, plain);
        synchronized (out) {
            out.write(header);
            out.write(sealed);
            out.flush();
        }
    }

    public static In read(DataInputStream in, Session s) throws IOException {
        byte[] header = new byte[HEADER_LEN];
        in.readFully(header);
        int magic = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        if (magic != MAGIC) throw new IOException("magic 不对，对端不是本协议");
        int ver = header[2] & 0xFF;
        if (ver != VERSION) throw new IOException("协议版本不支持: " + ver);
        int type = header[3] & 0xFF;
        int len = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        int counter = ((header[8] & 0xFF) << 24) | ((header[9] & 0xFF) << 16)
                | ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
        if (len < 0 || len > MAX_PLAIN) throw new IOException("长度非法: " + len);
        int dir = s.isClient() ? 2 : 1;   // 收到的方向与本端发送相反
        byte[] sealed = new byte[len + 16];
        in.readFully(sealed);
        byte[] plain = Crypto.open(s.key(), nonce(dir, counter), header, sealed);
        In r = new In();
        r.type = type;
        r.plain = plain;
        return r;
    }

    public static final class In {
        public int type;
        public byte[] plain;
    }
}
