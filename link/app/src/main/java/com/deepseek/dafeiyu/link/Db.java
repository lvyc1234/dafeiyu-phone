package com.deepseek.dafeiyu.link;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class Db extends SQLiteOpenHelper {

    private static final String NAME = "link.db";
    private static final int VERSION = 1;

    public Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE msg(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "mine INTEGER NOT NULL," +
                "type TEXT NOT NULL," +
                "body TEXT," +
                "path TEXT," +
                "dur INTEGER DEFAULT 0," +
                "state INTEGER DEFAULT 1)");
        db.execSQL("CREATE INDEX idx_msg_ts ON msg(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) db.execSQL("ALTER TABLE msg ADD COLUMN dur INTEGER DEFAULT 0");
    }

    public long insert(Msg m) {
        ContentValues v = new ContentValues();
        v.put("ts", m.ts);
        v.put("mine", m.mine ? 1 : 0);
        v.put("type", m.type);
        v.put("body", m.body);
        v.put("path", m.path);
        v.put("dur", m.dur);
        v.put("state", m.state);
        long id = getWritableDatabase().insert("msg", null, v);
        m.rowId = id;
        return id;
    }

    public void updateState(long id, int state) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        getWritableDatabase().update("msg", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public List<Msg> recent(int limit) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id,ts,mine,type,body,path,dur,state FROM msg ORDER BY _id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) {
            Msg m = new Msg();
            m.rowId = c.getLong(0);
            m.ts = c.getLong(1);
            m.mine = c.getInt(2) == 1;
            m.type = c.getString(3);
            m.body = c.getString(4);
            m.path = c.getString(5);
            m.dur = c.getInt(6);
            m.state = c.getInt(7);
            out.add(0, m);   // 反转成时间正序
        }
        c.close();
        return out;
    }

    public void clear() {
        getWritableDatabase().delete("msg", null, null);
    }
}
