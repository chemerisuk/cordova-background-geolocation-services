package com.flybuy.cordova.location;

import android.content.Context;
import android.content.ContentValues;
import android.location.Location;
import android.util.Log;
import android.os.Build;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.google.android.gms.location.DetectedActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;


public class StorageHelper extends SQLiteOpenHelper {
    private static final String TAG = "BackgroundLocationUpdateService";

    public StorageHelper(Context applicationcontext) {
        super(applicationcontext, "locationstates.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE states (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, latitude REAL, longitude REAL, accuracy INTEGER, speed REAL, heading INTEGER, activity_type TEXT, activity_confidence INTEGER, activity_moving BOOLEAN, battery_level INTEGER, battery_charging BOOLEAN, elapsed DATETIME, timestamp DATETIME, recording BOOLEAN)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int version_old, int current_version) {
        database.execSQL("DROP TABLE IF EXISTS states");
        onCreate(database);
    }

    public void append(Location location, DetectedActivity activity, int batteryLevel, boolean isCharging, boolean isMoving, boolean isRecording) {
        SQLiteDatabase database = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        long timestamp;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            timestamp = location.getElapsedRealtimeNanos() / 1000000;
        } else {
            timestamp = location.getTime();
        }

        values.put("latitude", location.getLatitude());
        values.put("longitude", location.getLongitude());
        values.put("accuracy", location.getAccuracy());
        values.put("speed", location.getSpeed());
        values.put("heading", Math.round(location.getBearing()));
        values.put("activity_type", Constants.getActivityString(activity.getType()));
        values.put("activity_confidence", activity.getConfidence());
        values.put("activity_moving", isMoving);
        values.put("battery_level", batteryLevel);
        values.put("battery_charging", isCharging);
        values.put("elapsed", timestamp);
        values.put("timestamp", System.currentTimeMillis());
        values.put("recording", isRecording);

        database.insert("states", null, values);
    }

    public void readyToSync() {
        SQLiteDatabase database = this.getWritableDatabase();

        database.execSQL("UPDATE states SET recording = 0");
    }

    public JSONArray serialize(boolean recording, int limit) {
        String selectQuery = "SELECT * FROM states WHERE recording = " + (recording ? 1 : 0) + " ORDER BY timestamp ASC";

        if (limit > 0) {
            selectQuery += " LIMIT " + limit;
        }

        SQLiteDatabase database = this.getReadableDatabase();
        Cursor cursor = database.rawQuery(selectQuery, null);
        JSONArray results = new JSONArray();

        try {
            if (cursor.moveToFirst()) {
                do {
                    JSONObject state = new JSONObject();

                    try {
                        state.put("latitude", cursor.getFloat(1));
                        state.put("longitude", cursor.getFloat(2));
                        state.put("accuracy", cursor.getInt(3));
                        state.put("speed", cursor.getFloat(4));
                        state.put("heading", cursor.getFloat(5));
                        state.put("activity_type", cursor.getString(6));
                        state.put("activity_confidence", cursor.getInt(7));
                        state.put("activity_moving", cursor.getInt(8));
                        state.put("battery_level", cursor.getInt(9));
                        state.put("battery_charging", cursor.getInt(10));
                        state.put("elapsed", cursor.getLong(11));
                        state.put("timestamp", cursor.getLong(12));

                        results.put(state);
                    } catch (JSONException ex) {
                        Log.d(TAG, "- Fail to serialize record", ex);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception ex) {
            Log.d(TAG, "- error during serialization", ex);
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }

        return results;
    }

    public void cleanup(long timestamp) {
        SQLiteDatabase database = this.getWritableDatabase();

        database.delete("states", "recording = 0 AND timestamp <= ?", new String[] { String.valueOf(timestamp) });
    }
}
