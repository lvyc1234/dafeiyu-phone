package com.deepseek.dafeiyu.link;

/** 一条聊天消息（也是落库的单位） */
public class Msg {
    public static final String T_TEXT = "text";
    public static final String T_EMOJI = "emoji";
    public static final String T_IMAGE = "image";
    public static final String T_VOICE = "voice";

    public static final int S_SENDING = 0;
    public static final int S_SENT = 1;
    public static final int S_FAILED = 2;

    public long rowId;
    public long ts;
    public boolean mine;
    public String type = T_TEXT;
    public String body = "";
    public String path = "";
    public int dur;
    public int state = S_SENT;

    public Msg() { this.ts = System.currentTimeMillis() / 1000; }

    public Msg(boolean mine, String type, String body) {
        this();
        this.mine = mine;
        this.type = type;
        this.body = body;
        this.state = mine ? S_SENDING : S_SENT;
    }
}
