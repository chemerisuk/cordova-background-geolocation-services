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
    private String stillInterval = "0";
    private String activitiesConfidence = "75";
    private String accuracyFilter = "1000";
    private String syncUrl = "";
    private String syncInterval = "30";
    private String deviceToken = "";
    private String preventSleepWhenRecording = "";

    private String distanceFilter = "30";
    private String isDebugging = "false";
    private String notificationTitle = "Location Tracking";
    private String notificationText = "ENABLED";
    private String keepAwake = "false";
    private String keepAlive = "false";

    private CallbackContext locationUpdateCallback = null;
    private CallbackContext detectedActivitiesCallback = null;

    private LocationManager locationManager;
    private LocalBroadcastManager broadcastManager;
    private SharedPreferences sharedPrefs;
    private SharedPreferences.Editor sharedPrefsEditor;
    private StorageHelper storageHelper;

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
                    boolean isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                    boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

                    if (location != null) {
                        JSONObject state = locationToJSON(location, isGPSEnabled, isNetworkEnabled);
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, state);
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
      storageHelper = new StorageHelper(activity.getApplicationContext());

      locationManager = (LocationManager)activity.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean execute(String action, JSONArray data, final CallbackContext callbackContext) {
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
            updateServiceIntent.putExtra("deviceToken", deviceToken);
            updateServiceIntent.putExtra("stillInterval", stillInterval);

            bindServiceToWebview(activity, updateServiceIntent);

            callbackContext.success();
        } else if (ACTION_STOP.equalsIgnoreCase(action)) {
            unbindServiceFromWebview(activity, updateServiceIntent);

            callbackContext.success();
        } else if (ACTION_CONFIGURE.equalsIgnoreCase(action)) {
            try {
                // [distanceFilter, desiredAccuracy,  interval, fastestInterval, aggressiveInterval, debug, notificationTitle, notificationText, keepAlive, keepAwake, activitiesInterval, activitiesConfidence, syncUrl, syncInterval, deviceToken, stillInterval]
                //  0               1                2         3                4                   5      6                   7                8           9          10                  11                    12       13            14           15
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
                this.deviceToken = data.getString(14);
                this.stillInterval = data.getString(15);

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
            try {
                final boolean persistent = data.getBoolean(0);

                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        startTrackRecording(persistent);

                        callbackContext.success();
                    }
                });
            } catch (JSONException e) {
                callbackContext.error("JSON Exception" + e.getMessage());
            }
        } else if ("stopTrackRecording".equalsIgnoreCase(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    stopTrackRecording();

                    callbackContext.success();
                }
            });
        } else if ("serializeTrack".equalsIgnoreCase(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONArray states = storageHelper.serialize(true, -1);

                    callbackContext.success(states);
                }
            });
        } else {
            result = false;
        }

        return result;
    }

    private void startTrackRecording(boolean persistent) {
        if (persistent) {
            sharedPrefsEditor.putBoolean("##", true);
            sharedPrefsEditor.commit();
        }

        broadcastManager.sendBroadcast(new Intent(Constants.START_RECORDING));
    }

    private void stopTrackRecording() {
        if (sharedPrefs.contains("##")) {
            sharedPrefsEditor.remove("##");
        }

        sharedPrefsEditor.commit();

        storageHelper.readyToSync();
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

    private void unbindServiceFromWebview(Context context, Intent intent) {
        if (!isEnabled) return;

        if (!sharedPrefs.contains("##")) {
            context.stopService(intent);
        }

        broadcastManager.unregisterReceiver(locationUpdateReceiver);
        broadcastManager.unregisterReceiver(detectedActivitiesReceiver);

        isEnabled = false;
    }

    private JSONObject locationToJSON(Location location, boolean isGPSEnabled, boolean isNetworkEnabled) {
        JSONObject state = new JSONObject();

        try {
            state.put("latitude", location.getLatitude());
            state.put("longitude", location.getLongitude());
            state.put("accuracy", location.getAccuracy());
            state.put("altitude", location.getAltitude());
            state.put("timestamp", location.getTime());
            state.put("speed", location.getSpeed());
            state.put("heading", location.getBearing());
            state.put("gps_enabled", isGPSEnabled);
            state.put("network_enabled", isNetworkEnabled);
        } catch(JSONException e) {
            Log.d(TAG, "ERROR CREATING JSON" + e);
        }

        lastLocation = location;

        return state;
    }


    /**
     * Override method in CordovaPlugin.
     * Checks to see if it should turn off
     */
    public void onDestroy() {
        if (isEnabled) {
            Activity activity = this.cordova.getActivity();

            unbindServiceFromWebview(activity, updateServiceIntent);
        }
    }

    /**
     * Called when a message is sent to plugin.
     */
    public Object onMessage(String id, Object data) {
        if (id == "exit") {
            if (isEnabled) {
                Activity activity = this.cordova.getActivity();

                unbindServiceFromWebview(activity, updateServiceIntent);
            }
        }

        return null;
    }
}
