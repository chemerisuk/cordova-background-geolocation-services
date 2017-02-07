package com.flybuy.cordova.location;

import java.util.HashMap;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.util.Log;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class LocationsProvider extends ContentProvider {
    private static final String TAG = "BackgroundLocationUpdateService";

    static final String PROVIDER_NAME = "com.flybuy.cordova.location.LocationsProvider";
    static final String URL = "content://" + PROVIDER_NAME + "/cte";
    static final Uri CONTENT_URI = Uri.parse(URL);

    static final int uriCode = 1;
    static final UriMatcher uriMatcher;
    private static HashMap<String, String> values;

    private DatabaseHelper dbHelper;
    static final String DATABASE_NAME = "states";
    static final String TABLE_NAME = "states";
    static final int DATABASE_VERSION = 3;

    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(PROVIDER_NAME, "cte", uriCode);
        uriMatcher.addURI(PROVIDER_NAME, "cte/*", uriCode);
    }

    @Override
    public boolean onCreate() {
        dbHelper = new DatabaseHelper(getContext());

        return true;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count = 0;

        switch (uriMatcher.match(uri)) {
        case uriCode:
            count = db.delete(TABLE_NAME, selection, selectionArgs);
            break;
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);

        return count;
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
        case uriCode:
            return "vnd.android.cursor.dir/cte";
        default:
            throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long rowID = db.insert(TABLE_NAME, "", values);

        if (rowID > 0) {
            Uri _uri = ContentUris.withAppendedId(CONTENT_URI, rowID);
            getContext().getContentResolver().notifyChange(_uri, null);
            return _uri;
        }

        throw new SQLException("Failed to add a record into " + uri);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
        String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_NAME);

        switch (uriMatcher.match(uri)) {
        case uriCode:
            qb.setProjectionMap(values);
            break;
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (sortOrder == null || sortOrder == "") {
            sortOrder = "timestamp";
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder, "5000");
        c.setNotificationUri(getContext().getContentResolver(), uri);

        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
        String[] selectionArgs) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int count = 0;
        switch (uriMatcher.match(uri)) {
        case uriCode:
            count = db.update(TABLE_NAME, values, selection, selectionArgs);
            break;
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);

        return count;
    }

    public static JSONArray serialize(Cursor cursor, int limit) {
        JSONArray results = new JSONArray();

        try {
            if (cursor.moveToFirst()) {
                do {
                    JSONObject state = new JSONObject();

                    try {
                        state.put("latitude", cursor.getFloat(1));
                        state.put("longitude", cursor.getFloat(2));
                        state.put("accuracy", cursor.getInt(3));
                        state.put("speed", cursor.getShort(4));
                        state.put("heading", cursor.getShort(5));
                        state.put("activity_type", cursor.getShort(6));
                        state.put("activity_confidence", cursor.getShort(7));
                        state.put("activity_moving", cursor.getShort(8) > 0);
                        state.put("gps_enabled", cursor.getShort(9) > 0);
                        state.put("wifi_enabled", cursor.getShort(10) > 0);
                        state.put("battery_level", cursor.getShort(11));
                        state.put("battery_charging", cursor.getShort(12) > 0);
                        state.put("elapsed", cursor.getLong(13));
                        state.put("timestamp", cursor.getLong(14));
                        state.put("status", cursor.getShort(15));

                        results.put(state);
                    } catch (JSONException ex) {
                        Log.d(TAG, "- Fail to serialize record", ex);
                    }

                    if (limit > 0 && results.length() >= limit) break;
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }

        return results;
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_NAME +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, latitude REAL, longitude REAL, accuracy TINYINT, speed REAL, heading SMALLINT, activity_type TINYINT, activity_confidence TINYINT, activity_moving BOOLEAN, gps_enabled BOOLEAN, wifi_enabled BOOLEAN, battery_level TINYINT, battery_charging BOOLEAN, elapsed DATETIME, timestamp DATETIME, status TINYINT, recording BOOLEAN)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }
}
