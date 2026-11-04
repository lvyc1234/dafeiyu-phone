package com.deepseek.dafeiyu.link;

/** 一次连接的会话密钥与帧计数 */
public final class Session {

    private final byte[] key;
    private final boolean client;
    private int out = 0;

    private Session(byte[] key, boolean client) {
        this.key = key;
        this.client = client;
    }

    /** 客户端：先发自己的 salt，收到服务端 salt 后派生 */
    public static Session derive(byte[] psk, byte[] saltC, byte[] saltS, boolean client) {
        byte[] salt = new byte[saltC.length + saltS.length];
        System.arraycopy(saltC, 0, salt, 0, saltC.length);
        System.arraycopy(saltS, 0, salt, saltC.length, saltS.length);
        byte[] k = Crypto.hkdf(psk, salt, "dafeiyu-link-v1", 32);
        return new Session(k, client);
    }

    public byte[] key() { return key; }
    public boolean isClient() { return client; }
    public int nextOut() { return out++; }
}
