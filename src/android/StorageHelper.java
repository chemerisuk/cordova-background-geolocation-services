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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.OutputStream;
import java.io.IOException;


public class StorageHelper extends SQLiteOpenHelper {
    private static final String TAG = "BackgroundLocationUpdateService";
    private static final int BATCH_SIZE = 300;

    private static ScheduledExecutorService scheduler = null;
    private String deviceToken = "";
    private URL syncUrl = null;
    private int syncInterval = 0;

    public StorageHelper(Context applicationcontext, URL syncUrl, int syncInterval, String deviceToken) {
        super(applicationcontext, "androidsqlite.db", null, 2);

        this.syncUrl = syncUrl;
        this.deviceToken = deviceToken;
        this.syncInterval = syncInterval;
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE states (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, latitude REAL, longitude REAL, accuracy INTEGER, speed REAL, heading REAL, activity_type TEXT, activity_confidence INTEGER, activity_moving BOOLEAN, battery_level INTEGER, battery_charging BOOLEAN, recorded_at DATETIME, created_at DATETIME)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int version_old, int current_version) {
        database.execSQL("DROP TABLE IF EXISTS states");
        onCreate(database);
    }

    public void append(Location location, DetectedActivity activity, boolean moving, int batteryLevel, boolean isCharging) {
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
        values.put("heading", location.getBearing());
        values.put("activity_type", Constants.getActivityString(activity.getType()));
        values.put("activity_confidence", activity.getConfidence());
        values.put("activity_moving", moving);
        values.put("battery_level", batteryLevel);
        values.put("battery_charging", isCharging);
        values.put("recorded_at", timestamp);
        values.put("created_at", System.currentTimeMillis());

        database.insert("states", null, values);
    }

    private JSONArray serialize() {
        String selectQuery = "SELECT * FROM states ORDER BY created_at ASC LIMIT " + BATCH_SIZE;
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
                        state.put("recorded_at", cursor.getLong(11));
                        state.put("created_at", cursor.getLong(12));

                        results.put(state);
                    } catch (JSONException err) {
                        Log.d(TAG, "- fail to serialize record", err);
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

    public void startSync() {
        if (this.syncUrl == null || this.syncInterval == 0) return;

        stopSync(); // make sure to stop previous scheduler

        StorageHelper.scheduler = Executors.newSingleThreadScheduledExecutor();

        StorageHelper.scheduler.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                Log.d(TAG, "- Sync local db with server started");

                JSONArray results = serialize();
                int resultsCount = results.length();

                if (resultsCount > 0) {
                    Log.d(TAG, "- Send " + resultsCount + " records to server");

                    sendStatesToServer(results);
                }
            }
        }, 30, this.syncInterval, TimeUnit.SECONDS);
    }

    public void stopSync() {
        if (StorageHelper.scheduler != null) {
            Log.d(TAG, "- Sync local db with server stopped");

            StorageHelper.scheduler.shutdownNow();
            StorageHelper.scheduler = null;
        }
    }

    private void sendStatesToServer(JSONArray results) {
        HttpURLConnection http = null;

        try {
            http = (HttpURLConnection) this.syncUrl.openConnection();
            http.setDoOutput(true);
            http.setRequestMethod("POST");
            http.setRequestProperty("Authorization", this.deviceToken);
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            // send data to server
            http.getOutputStream().write(results.toString().getBytes("UTF-8"));

            if (http.getResponseCode() == 200) {
                JSONObject lastResult = (JSONObject) results.get(results.length() - 1);

                cleanup(lastResult.getLong("created_at"));
            }
        } catch (IOException ex) {
            Log.d(TAG, "- fail to send records", ex);
        } catch (JSONException ex) {
            Log.d(TAG, "- fail to cleanup records", ex);
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }

    private void cleanup(long ttl) {
        SQLiteDatabase database = this.getWritableDatabase();

        database.delete("states", "created_at <= ?", new String[] { String.valueOf(ttl) });
    }
}
