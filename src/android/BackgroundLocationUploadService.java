package com.flybuy.cordova.location;

import android.util.Log;
import android.app.IntentService;
import android.content.Intent;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class BackgroundLocationUploadService extends IntentService {
    private static final String TAG = "BackgroundLocationUpdateService";

    static final String URL_EXTRA = "url";
    static final String TOKEN_EXTRA = "token";

    public BackgroundLocationUploadService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        URL syncUrl = (URL)intent.getSerializableExtra(BackgroundLocationUploadService.URL_EXTRA);
        String deviceToken = intent.getStringExtra(BackgroundLocationUploadService.TOKEN_EXTRA);

        JSONArray results = StorageHelper.getInstance(this).serialize(false, 250);
        int resultsCount = results.length();

        if (resultsCount > 0) {
            Log.d(TAG, "- Send " + resultsCount + " records to server");

            HttpURLConnection http = null;

            try {
                http = (HttpURLConnection) syncUrl.openConnection();
                http.setDoOutput(true);
                http.setRequestMethod("POST");
                http.setRequestProperty("Authorization", deviceToken);
                http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                // send data to server
                http.getOutputStream().write(results.toString().getBytes("UTF-8"));

                if (http.getResponseCode() == 200) {
                    JSONObject lastResult = (JSONObject) results.get(results.length() - 1);

                    StorageHelper.getInstance(this).cleanup(lastResult.getLong("timestamp"));
                }
            } catch (IOException ex) {
                Log.e(TAG, "- fail to send records", ex);
            } catch (JSONException ex) {
                Log.e(TAG, "- fail to cleanup records", ex);
            } finally {
                if (http != null) {
                    http.disconnect();
                }
            }
        }
    }
}
