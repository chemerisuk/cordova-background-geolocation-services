package com.flybuy.cordova.location;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.IBinder;
import android.os.Build;
import android.content.ComponentName;
import android.preference.PreferenceManager;

import android.support.v4.content.LocalBroadcastManager;
import com.google.android.gms.location.DetectedActivity;
import java.util.ArrayList;

public class BackgroundLocationServicesPlugin extends CordovaPlugin {
    private static final String TAG = "BackgroundLocationServicesPlugin";
    private static final String PLUGIN_VERSION = "1.0";

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_CONFIGURE = "configure";
    public static final String ACTION_GET_VERSION = "getVersion";
    public static final String ACTION_REGISTER_FOR_LOCATION_UPDATES = "registerForLocationUpdates";
    public static final String ACTION_REGISTER_FOR_ACTIVITY_UPDATES = "registerForActivityUpdates";

    public static String APP_NAME = "";

    private Boolean isEnabled = false;
    private Boolean inBackground = false;
    private Location lastLocation = null;

    private String desiredAccuracy = "1000";

    private Intent updateServiceIntent;

    private String interval = "300000";
    private String fastestInterval = "60000";
    private String aggressiveInterval = "4000";
    private String activitiesInterval = "0";
    private String activitiesConfidence = "75";
    private String syncUrl = "";
    private String syncInterval = "30";

    private String distanceFilter = "30";
    private String isDebugging = "false";
    private String notificationTitle = "Location Tracking";
    private String notificationText = "ENABLED";
    private String keepAwake = "false";
    private String keepAlive = "false";

    private CallbackContext locationUpdateCallback = null;
    private CallbackContext detectedActivitiesCallback = null;

    private LocalBroadcastManager broadcastManager;
    private SharedPreferences sharedPrefs;
    private SharedPreferences.Editor sharedPrefsEditor;

    private BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            if (detectedActivitiesCallback == null) return;

            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Log.i(TAG, "Received Detected Activities");

                    final ArrayList<DetectedActivity> updatedActivities =
                        intent.getParcelableArrayListExtra(Constants.ACTIVITY_EXTRA);

                    if (updatedActivities != null) {
                        JSONObject daJSON = new JSONObject();

                        for(DetectedActivity da: updatedActivities) {
                            try {
                                daJSON.put(Constants.getActivityString(da.getType()), da.getConfidence());
                            } catch(JSONException e) {
                                Log.e(TAG, "Error putting JSON value" + e);
                            }
                        }

                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, daJSON);
                        pluginResult.setKeepCallback(true);
                        detectedActivitiesCallback.sendPluginResult(pluginResult);
                    }
                }
            });
        }
    };

    private BroadcastReceiver locationUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            if (locationUpdateCallback == null) return;

            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Location location = intent.getParcelableExtra(Constants.LOCATION_EXTRA);

                    if (location != null) {
                        JSONObject data = locationToJSON(location);
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
                        pluginResult.setKeepCallback(true);
                        locationUpdateCallback.sendPluginResult(pluginResult);
                    }
                }
            });
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
      super.initialize(cordova, webView);

      Activity activity = this.cordova.getActivity();

      APP_NAME = getApplicationName(activity);
      //Need To namespace these in case more than one app is running this bg plugin
      Constants.LOCATION_UPDATE = APP_NAME + Constants.LOCATION_UPDATE;
      Constants.DETECTED_ACTIVITY_UPDATE = APP_NAME + Constants.DETECTED_ACTIVITY_UPDATE;

      sharedPrefs = PreferenceManager.getDefaultSharedPreferences(activity);
      sharedPrefsEditor = sharedPrefs.edit();

      broadcastManager = LocalBroadcastManager.getInstance(activity.getApplicationContext());
    }

    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) {
        Activity activity = this.cordova.getActivity();

        Boolean result = true;

        if (ACTION_START.equalsIgnoreCase(action) && !isEnabled) {
          updateServiceIntent = new Intent(activity, BackgroundLocationUpdateService.class);
            if (Build.VERSION.SDK_INT >= 16) {
                // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
                updateServiceIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            }

            updateServiceIntent.putExtra("desiredAccuracy", desiredAccuracy);
            updateServiceIntent.putExtra("distanceFilter", distanceFilter);
            updateServiceIntent.putExtra("desiredAccuracy", desiredAccuracy);
            updateServiceIntent.putExtra("isDebugging", isDebugging);
            updateServiceIntent.putExtra("notificationTitle", notificationTitle);
            updateServiceIntent.putExtra("notificationText", notificationText);
            updateServiceIntent.putExtra("interval", interval);
            updateServiceIntent.putExtra("fastestInterval", fastestInterval);
            updateServiceIntent.putExtra("aggressiveInterval", aggressiveInterval);
            updateServiceIntent.putExtra("activitiesInterval", activitiesInterval);
            updateServiceIntent.putExtra("activitiesConfidence", activitiesConfidence);
            updateServiceIntent.putExtra("syncUrl", syncUrl);
            updateServiceIntent.putExtra("syncInterval", syncInterval);

            bindServiceToWebview(activity, updateServiceIntent);

            callbackContext.success();
        } else if (ACTION_STOP.equalsIgnoreCase(action)) {
            unbindServiceFromWebview(activity, updateServiceIntent, true);

            callbackContext.success();
        } else if (ACTION_CONFIGURE.equalsIgnoreCase(action)) {
            try {
                // [distanceFilter, desiredAccuracy,  interval, fastestInterval, aggressiveInterval, debug, notificationTitle, notificationText, keepAlive, keepAwake, activitiesInterval, activitiesConfidence, syncUrl, syncInterval]
                //  0               1                2         3                4                   5      6                   7                8           9          10                  11                    12       13
                this.distanceFilter = data.getString(0);
                this.desiredAccuracy = data.getString(1);
                this.interval = data.getString(2);
                this.fastestInterval = data.getString(3);
                this.aggressiveInterval = data.getString(4);
                this.isDebugging = data.getString(5);
                this.notificationTitle = data.getString(6);
                this.notificationText = data.getString(7);
                this.keepAlive = data.getString(8);
                this.keepAwake = data.getString(9);
                this.activitiesInterval = data.getString(10);
                this.activitiesConfidence = data.getString(11);
                this.syncUrl = data.getString(12);
                this.syncInterval = data.getString(13);

                callbackContext.success();
            } catch (JSONException e) {
                callbackContext.error("JSON Exception" + e.getMessage());
            }
        } else if(ACTION_GET_VERSION.equalsIgnoreCase(action)) {
            result = true;
            callbackContext.success(PLUGIN_VERSION);
        } else if(ACTION_REGISTER_FOR_LOCATION_UPDATES.equalsIgnoreCase(action)) {
            locationUpdateCallback = callbackContext;
        } else if(ACTION_REGISTER_FOR_ACTIVITY_UPDATES.equalsIgnoreCase(action)) {
            detectedActivitiesCallback = callbackContext;
        } else if ("startTrackRecording".equalsIgnoreCase(action)) {
          startTrackRecording();

          callbackContext.success();
        } else if ("stopTrackRecording".equalsIgnoreCase(action)) {
          stopTrackRecording();

          callbackContext.success();
        } else if ("serializeTrack".equalsIgnoreCase(action)) {
            final CallbackContext cb = callbackContext;

            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    serializeTrack(cb);
                }
            });
        } else {
          result = false;
        }

        return result;
    }

    private void startTrackRecording() {
        if (!sharedPrefs.contains("??")) {
            sharedPrefsEditor.putInt("??", 0);
            sharedPrefsEditor.commit();
        }
    }

    private void stopTrackRecording() {
        int n = sharedPrefs.getInt("??", -1);

        if (n > 0) {
            for (int i = 0; i < n; ++i) {
                sharedPrefsEditor.remove("_" + i);
            }
        }

        sharedPrefsEditor.remove("??");
        sharedPrefsEditor.commit();
    }

    private void serializeTrack(CallbackContext callbackContext) {
        JSONArray track = new JSONArray();

        if (sharedPrefs.contains("??")) {
            int n = sharedPrefs.getInt("??", -1);

            try {
                for (int i = 0; i < n; ++i) {
                    if (sharedPrefs.contains("?" + i)) {
                        JSONObject entry = new JSONObject();

                        entry.put("x", sharedPrefs.getInt("?" + i++, 0));
                        entry.put("y", sharedPrefs.getInt("?" + i++, 0));
                        entry.put("t", sharedPrefs.getLong("?" + i, 0));

                        track.put(entry);
                    }
                }

                callbackContext.success(track);
            } catch (JSONException ex) {
                callbackContext.error(ex.getMessage());
            }
        }
    }

    public String getApplicationName(Context context) {
      return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
    }

    public Boolean debug() {
        if(Boolean.parseBoolean(isDebugging)) {
            return true;
        } else {
            return false;
        }
    }

    private void bindServiceToWebview(Context context, Intent intent) {
        if (isEnabled) return;

        context.startService(intent);

        broadcastManager.registerReceiver(locationUpdateReceiver, new IntentFilter(Constants.CALLBACK_LOCATION_UPDATE));
        broadcastManager.registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.CALLBACK_ACTIVITY_UPDATE));

        isEnabled = true;
    }

    private void unbindServiceFromWebview(Context context, Intent intent, boolean stopService) {
        if (!isEnabled) return;

        if (stopService || keepAlive == "false" && sharedPrefs.getInt("??", -1) < 0) {
            context.stopService(intent);
        }

        broadcastManager.unregisterReceiver(locationUpdateReceiver);
        broadcastManager.unregisterReceiver(detectedActivitiesReceiver);

        isEnabled = false;
    }

    private JSONObject locationToJSON(Location location) {
        float bearing = location.getBearing();

        if (bearing == 0 && lastLocation != null) {
            bearing = lastLocation.bearingTo(location);
        }

        JSONObject data = new JSONObject();

        try {
            data.put("latitude", location.getLatitude());
            data.put("longitude", location.getLongitude());
            data.put("accuracy", location.getAccuracy());
            data.put("altitude", location.getAltitude());
            data.put("timestamp", location.getTime());
            data.put("speed", location.getSpeed());
            data.put("heading", bearing);
        } catch(JSONException e) {
            Log.d(TAG, "ERROR CREATING JSON" + e);
        }

        lastLocation = location;

        return data;
    }


    /**
     * Override method in CordovaPlugin.
     * Checks to see if it should turn off
     */
    public void onDestroy() {
        if (isEnabled) {
            Activity activity = this.cordova.getActivity();

            unbindServiceFromWebview(activity, updateServiceIntent, false);
        }
    }

    /**
     * Called when a message is sent to plugin.
     */
    public Object onMessage(String id, Object data) {
        if (id == "exit") {
            if (isEnabled) {
                Activity activity = this.cordova.getActivity();

                unbindServiceFromWebview(activity, updateServiceIntent, false);
            }
        }

        return null;
    }
}
