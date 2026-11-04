package com.deepseek.dafeiyu.link;

import android.util.Base64;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 纯 JDK 实现：HKDF-SHA256 + AES-256-GCM。
 * 不引任何第三方库，AutoX.js(Rhino) 与 Android 两端用同一套原语。
 */
public final class Crypto {

    public static final int PSK_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;

    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** HKDF-Extract + HKDF-Expand（RFC 5869），输出 len 字节 */
    public static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len) {
        byte[] realSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        byte[] prk = hmac(realSalt, ikm);
        byte[] infoBytes = info.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            byte[] input = new byte[t.length + infoBytes.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(infoBytes, 0, input, t.length, infoBytes.length);
            input[input.length - 1] = (byte) i;
            t = hmac(prk, input);
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("hmac", e);
        }
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plain);
        } catch (Exception e) {
            throw new RuntimeException("aes-gcm seal", e);
        }
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(sealed);
        } catch (Exception e) {
            throw new RuntimeException("aes-gcm open", e);
        }
    }

    public static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    /** 密钥指纹：两端设置页对一下这 8 位，就能确认密钥一致 */
    public static String fingerprint(byte[] psk) {
        byte[] h = hmac(psk, "dafeiyu-fp".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(String.format("%02X", h[i]));
        return sb.toString();
    }

    public static byte[] fromHexOrB64(String s) {
        s = s == null ? "" : s.trim().replaceAll("\\s+", "");
        if (s.isEmpty()) return null;
        if (s.matches("^[0-9a-fA-F]+$") && s.length() == PSK_LEN * 2) {
            byte[] out = new byte[PSK_LEN];
            for (int i = 0; i < PSK_LEN; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        try {
            byte[] b = unb64(s);
            return b.length == PSK_LEN ? b : null;
        } catch (Exception e) {
            return null;
        }
    }
}
