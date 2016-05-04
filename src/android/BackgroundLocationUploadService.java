package com.flybuy.cordova.location;

import android.app.IntentService;
import android.content.Intent;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class BackgroundLocationUploadService extends IntentService {
    static final String URL_EXTRA = "url";
    static final String TOKEN_EXTRA = "token";

    private StorageHelper storageHelper;

    @Override
    public void onCreate() {
        super.onCreate();

        storageHelper = new StorageHelper(this);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        URL syncUrl = (URL)intent.getSerializableExtra(BackgroundLocationUploadService.URL_EXTRA);
        String deviceToken = intent.getStringExtra(BackgroundLocationUploadService.TOKEN_EXTRA);

        JSONArray results = storageHelper.serialize(false, 300);
        int resultsCount = results.length();

        if (resultsCount > 0) {
            if (isDebugging) {
                Log.d(TAG, "- Send " + resultsCount + " records to server");
            }

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

                    storageHelper.cleanup(lastResult.getLong("timestamp"));
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
    }
}
