package com.deepseek.dafeiyu.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MsgAdapter extends BaseAdapter {

    public interface OnMediaClick {
        void onImage(Msg m);
        void onVoice(Msg m);
    }

    private final Context ctx;
    private final List<Msg> data = new ArrayList<>();
    private Msg typingMsg = null;
    private final OnMediaClick mediaClick;

    /** 对方头像：优先 filesDir/avatar_current.jpg（画廊选的），没有就用内置 drawable */
    private Bitmap herAvatar;
    private boolean herLoaded;

    public MsgAdapter(Context ctx, OnMediaClick cb) {
        this.ctx = ctx;
        this.mediaClick = cb;
    }

    /** 画廊换了头像、或从画廊回来时调用，丢掉缓存重新读盘 */
    public void reloadAvatar() {
        herAvatar = null;
        herLoaded = false;
        notifyDataSetChanged();
    }

    private Bitmap herAvatar() {
        if (herLoaded) return herAvatar;
        herLoaded = true;
        try {
            File f = new File(ctx.getFilesDir(), "avatar_current.jpg");
            if (f.exists()) herAvatar = Util.decodeFile(f.getAbsolutePath(), 160);
        } catch (Throwable ignored) {
            herAvatar = null;
        }
        return herAvatar;
    }

    public void setAll(List<Msg> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    /** 对方状态气泡：正在输入 / 正在调用工具… */
    public void setTyping(boolean on, String text) {
        if (on) {
            if (typingMsg == null) {
                typingMsg = new Msg(false, "typing", text == null ? "正在输入…" : text);
                data.add(typingMsg);
            } else {
                typingMsg.body = text == null ? "正在输入…" : text;
            }
        } else {
            if (typingMsg != null) {
                data.remove(typingMsg);
                typingMsg = null;
            }
        }
        notifyDataSetChanged();
    }

    public void add(Msg m) {
        if (typingMsg != null) { data.remove(typingMsg); }
        data.add(m);
        if (typingMsg != null) { data.add(typingMsg); }
        notifyDataSetChanged();
    }

    public void addPlain(Msg m) {
        data.add(m);
        notifyDataSetChanged();
    }

    public List<Msg> all() {
        return data;
    }

    @Override
    public int getCount() { return data.size(); }

    @Override
    public Msg getItem(int i) { return data.get(i); }

    @Override
    public long getItemId(int i) { return data.get(i).rowId; }

    @Override
    public int getViewTypeCount() { return 2; }

    @Override
    public int getItemViewType(int i) { return data.get(i).mine ? 1 : 0; }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        Msg m = data.get(pos);
        View v = convertView;
        if (v == null) {
            int layout = m.mine ? R.layout.item_msg_mine : R.layout.item_msg;
            v = LayoutInflater.from(ctx).inflate(layout, parent, false);
        }
        TextView body = v.findViewById(R.id.body);
        ImageView image = v.findViewById(R.id.image);
        LinearLayout voice = v.findViewById(R.id.voice);
        TextView dur = v.findViewById(R.id.dur);
        TextView time = v.findViewById(R.id.time);
        LinearLayout bubble = v.findViewById(R.id.bubble);

        ImageView av = v.findViewById(R.id.avatar);
        if (av != null) {
            if (m.mine) {
                av.setImageResource(R.drawable.bg_avatar);
            } else {
                Bitmap custom = herAvatar();
                if (custom != null) {
                    av.setImageBitmap(custom);
                } else {
                    av.setImageResource(R.drawable.avatar_her);
                }
                av.setBackgroundResource(0);
            }
        }
        body.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        voice.setVisibility(View.GONE);
        image.setImageDrawable(null);
        bubble.setBackgroundResource(m.mine ? R.drawable.bubble_mine : R.drawable.bubble_theirs);

        if ("typing".equals(m.type)) {
            body.setVisibility(View.VISIBLE);
            body.setTextSize(13);
            String tt = (m.body == null || m.body.trim().isEmpty()) ? "正在输入…" : m.body;
            body.setText("● " + tt);
            body.setTextColor(0xFF888888);
            bubble.setBackgroundResource(R.drawable.bubble_theirs);
            time.setText("");
            return v;
        }
        if (Msg.T_IMAGE.equals(m.type)) {
            image.setVisibility(View.VISIBLE);
            Bitmap bmp = m.path == null || m.path.isEmpty() ? null : Util.decodeFile(m.path, 480);
            if (bmp != null) {
                image.setImageBitmap(bmp);
            } else {
                image.setImageResource(android.R.drawable.ic_menu_gallery);
            }
            image.setOnClickListener(x -> { if (mediaClick != null) mediaClick.onImage(m); });
        } else if (Msg.T_VOICE.equals(m.type)) {
            voice.setVisibility(View.VISIBLE);
            dur.setText(m.dur + "″");
            voice.setOnClickListener(x -> { if (mediaClick != null) mediaClick.onVoice(m); });
        } else if (Msg.T_EMOJI.equals(m.type)) {
            body.setVisibility(View.VISIBLE);
            body.setTextSize(26);
            body.setText(m.body);
        } else {
            body.setVisibility(View.VISIBLE);
            body.setTextSize(15);
            String t = m.body == null ? "" : m.body;
            if (m.mine && m.state == Msg.S_SENDING) t = t + "  ·";
            if (m.mine && m.state == Msg.S_FAILED) t = t + "  !";
            body.setText(t);
        }
        time.setText(Util.fmtTime(m.ts));
        return v;
    }
}
