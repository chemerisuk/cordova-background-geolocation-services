package com.flybuy.cordova.location;

import java.util.List;
import java.util.Iterator;
import java.util.Random;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;

import android.media.AudioManager;
import android.media.ToneGenerator;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import static android.telephony.PhoneStateListener.*;
import android.telephony.CellLocation;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import android.location.Location;
import android.location.Criteria;
import android.location.LocationManager;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderApi;

import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;

import static java.lang.Math.*;

import android.app.AlarmManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.content.res.Resources;

import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.ActivityRecognitionResult;
import java.util.ArrayList;
import com.google.android.gms.common.ConnectionResult;

import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;


public class BackgroundLocationUpdateService extends Service implements
        LocationListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "BackgroundLocationUpdateService";

    private long lastUpdateTime = 0l;
    private Boolean fastestSpeed = false;

    // private PendingIntent locationUpdatePI;
    private GoogleApiClient googleClientAPI;
    private PendingIntent detectedActivitiesPI;

    private Integer desiredAccuracy = 100;
    private Integer distanceFilter  = 30;
    private Integer activitiesInterval = 0;
    private Integer activitiesConfidence = 75;

    private static final Integer SECONDS_PER_MINUTE      = 60;
    private static final Integer MILLISECONDS_PER_SECOND = 60;

    private long  interval             = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND * 5;
    private long  fastestInterval      = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND;
    private long  aggressiveInterval   = (long) MILLISECONDS_PER_SECOND * 4;
    private long  stillInterval        = 0;

    private Location lastLocation;
    private DetectedActivity lastActivity;
    private StorageHelper storageHelper;
    private IntentFilter batteryStatusFilter;

    private Boolean isDebugging;
    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";
    private WakeLock wakeLock;
    private URL syncUrl;
    private int syncInterval;
    private String deviceToken;
    private boolean preventSleepWhenRecording = true;

    private Boolean isDetectingActivities = false;
    private Boolean isWatchingLocation = false;
    private boolean isStillMode = false;
    private boolean startRecordingOnConnect = true;

    private LocationManager locationManager;
    private LocationRequest locationRequest;
    private volatile Looper serviceLooper;
    private volatile Looper syncLooper;

    private SharedPreferences sharedPrefs;
    private SharedPreferences.Editor sharedPrefsEditor;
    private LocalBroadcastManager broadcastManager;

    private AlarmManager alarmMgr;
    private PendingIntent alarmPI;

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Auto-generated method stub
        Log.i(TAG, "OnBind" + intent);

        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");

        HandlerThread thread1 = new HandlerThread("HandlerThread[" + TAG + "#1]");
        thread1.start();

        serviceLooper = thread1.getLooper();

        HandlerThread thread2 = new HandlerThread("HandlerThread[" + TAG + "#2]");
        thread2.start();

        syncLooper = thread2.getLooper();

        Intent detectedActivitiesIntent = new Intent(Constants.DETECTED_ACTIVITY_UPDATE);
        if (Build.VERSION.SDK_INT >= 16) {
            // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
            detectedActivitiesIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        detectedActivitiesPI = PendingIntent.getBroadcast(this, 9002, detectedActivitiesIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.DETECTED_ACTIVITY_UPDATE), null, new Handler(serviceLooper));

        broadcastManager = LocalBroadcastManager.getInstance(this);
        broadcastManager.registerReceiver(startRecordingReceiver, new IntentFilter(Constants.START_RECORDING));

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefsEditor = sharedPrefs.edit();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        batteryStatusFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        lastActivity = new DetectedActivity(DetectedActivity.UNKNOWN, 0);

        alarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntent = new Intent(Constants.SYNC_ALARM_UPDATE);
        if (Build.VERSION.SDK_INT >= 16) {
            // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
            alarmIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        alarmPI = PendingIntent.getBroadcast(this, 9004, alarmIntent, 0);
        registerReceiver(syncAlarmReceiver, new IntentFilter(Constants.SYNC_ALARM_UPDATE), null, new Handler(syncLooper));

        storageHelper = new StorageHelper(this);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);

        if (intent != null) {
            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));

            interval             = Integer.parseInt(intent.getStringExtra("interval"));
            stillInterval        = Integer.parseInt(intent.getStringExtra("stillInterval"));
            fastestInterval      = Integer.parseInt(intent.getStringExtra("fastestInterval"));
            aggressiveInterval   = Integer.parseInt(intent.getStringExtra("aggressiveInterval"));
            activitiesInterval   = Integer.parseInt(intent.getStringExtra("activitiesInterval"));
            activitiesConfidence = Integer.parseInt(intent.getStringExtra("activitiesConfidence"));

            isDebugging = Boolean.parseBoolean(intent.getStringExtra("isDebugging"));
            notificationTitle = intent.getStringExtra("notificationTitle");
            notificationText = intent.getStringExtra("notificationText");

            syncInterval = Integer.parseInt(intent.getStringExtra("syncInterval"));
            deviceToken = intent.getStringExtra("deviceToken");

            try {
                syncUrl = new URL(intent.getStringExtra("syncUrl"));
            } catch (MalformedURLException ex) {
                Log.d(TAG, "- Invalid sync url specified", ex);
            }

            // Build the notification
            Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setSmallIcon(getIconResId());

            // Make clicking the event link back to the main cordova activity
            setClickEvent(builder);

            Notification notification;

            if (Build.VERSION.SDK_INT < 16) {
                // Build notification for HoneyComb to ICS
                notification = builder.getNotification();
            } else {
                // Notification for Jellybean and above
                notification = builder.build();
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;
            notification.contentView.setImageViewResource(android.R.id.icon, getApplicationInfo().icon);

            startForeground(startId, notification);

            if (activitiesInterval > 0) {
                startDetectingActivities();
            } else {
                stopDetectingActivities();
            }

            startLocationWatching();

            if (syncUrl != null && syncInterval > 0) {
                // schedule syncing data with server
                alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    30 * 1000, syncInterval * 1000, alarmPI);
            }
        }

        // Log.i(TAG, "- url: " + url);
        // Log.i(TAG, "- params: "  + params.toString());
        Log.i(TAG, "- interval: "             + interval);
        Log.i(TAG, "- stillInterval: "        + stillInterval);
        Log.i(TAG, "- fastestInterval: "      + fastestInterval);

        Log.i(TAG, "- distanceFilter: "     + distanceFilter);
        Log.i(TAG, "- desiredAccuracy: "    + desiredAccuracy);
        Log.i(TAG, "- isDebugging: "        + isDebugging);
        Log.i(TAG, "- notificationTitle: "  + notificationTitle);
        Log.i(TAG, "- notificationText: "   + notificationText);
        Log.i(TAG, "- activitiesInterval: "   + activitiesInterval);
        Log.i(TAG, "- activitiesConfidence: "   + activitiesConfidence);
        Log.i(TAG, "- syncUrl: "  + syncUrl);
        Log.i(TAG, "- syncInterval: "   + syncInterval);
        Log.i(TAG, "- deviceToken: "   + deviceToken);
        // We want this service to continue running until it is explicitly stopped
        return START_REDELIVER_INTENT;
    }

    /**
     * Retrieves the resource ID of the app icon.
     *
     * @return
     *      The resource ID of the app icon
     */
    private int getIconResId() {
        Context context = getApplicationContext();
        Resources res   = context.getResources();
        String pkgName  = context.getPackageName();

        int resId;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            resId = res.getIdentifier("ic_stat_notify", "drawable", pkgName);
        } else {
            resId = res.getIdentifier("icon", "drawable", pkgName);
        }

        return resId;
    }

    /**
     * Called when the location has changed.
     *
     * @param location
     */
    @Override
    public void onLocationChanged(Location location) {
        if (isDebugging) {
            Log.d(TAG, "- locationUpdateReceiver: " + location);
        }

        if (location == null) {
            return;
        }

        if (!location.hasBearing() && lastLocation != null) {
            location.setBearing(lastLocation.bearingTo(location));
        }

        lastLocation = location;

        //This is all for setting the callback for android which currently does not work
        Intent localIntent = new Intent(Constants.CALLBACK_LOCATION_UPDATE);
        localIntent.putExtra(Constants.LOCATION_EXTRA, lastLocation);
        broadcastManager.sendBroadcast(localIntent);

        recordState();
    }

    private BroadcastReceiver startRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isWatchingLocation) {
                if (isDebugging) {
                    Toast.makeText(context, "Start Recording", Toast.LENGTH_SHORT).show();
                }

                startLocationWatching();
            }
        }
    };

    private BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);

            lastActivity = result.getMostProbableActivity();

            if (isDebugging) {
                Log.w(TAG, "MOST LIKELY ACTIVITY: " + Constants.getActivityString(lastActivity.getType()) + " " + lastActivity.getConfidence());
            }

            Intent mIntent = new Intent(Constants.CALLBACK_ACTIVITY_UPDATE);
            mIntent.putParcelableArrayListExtra(Constants.ACTIVITY_EXTRA,
                (ArrayList<DetectedActivity>) result.getProbableActivities());
            broadcastManager.sendBroadcast(mIntent);

            if (!isDetectingActivities) return;

            if (lastActivity.getType() == DetectedActivity.STILL && lastActivity.getConfidence() >= activitiesConfidence) {
                if (!isStillMode) {
                    isStillMode = true;

                    if (isDebugging) {
                        Toast.makeText(context, "Detected Activity was STILL, Stop recording", Toast.LENGTH_SHORT).show();
                    }

                    stopLocationWatching();

                    if (stillInterval > 0 && sharedPrefs.contains("##")) {
                        startLocationWatching();
                    }
                }
            } else {
                if (isStillMode) {
                    isStillMode = false;

                    if (isDebugging) {
                        Toast.makeText(context, "Detected Activity was ACTIVE, Start Recording", Toast.LENGTH_SHORT).show();
                    }

                    startLocationWatching();
                }
            }
        }
    };

    private BroadcastReceiver syncAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isDebugging) {
                Log.d(TAG, "- Sync local db started by alarm");
            }

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
    };

    /**
     * Adds an onclick handler to the notification
     */
    private Notification.Builder setClickEvent (Notification.Builder notification) {
        Context context     = getApplicationContext();
        String packageName  = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode = new Random().nextInt();

        PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        return notification.setContentIntent(contentIntent);
    }

    protected synchronized void connectToPlayAPI() {
        if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            googleClientAPI = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addApi(ActivityRecognition.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

            if (!googleClientAPI.isConnected() || !googleClientAPI.isConnecting()) {
                googleClientAPI.connect();
            }
        }
    }

    private void startLocationWatching() {
        if (googleClientAPI == null) {
            connectToPlayAPI();
        } else if (googleClientAPI.isConnected()) {
            locationRequest = LocationRequest.create()
                    .setPriority(translateDesiredAccuracy(desiredAccuracy))
                    .setFastestInterval(fastestInterval)
                    .setInterval(isStillMode && stillInterval > 0 ? stillInterval : interval)
                    .setSmallestDisplacement(distanceFilter);

            this.isWatchingLocation = true;

            LocationServices.FusedLocationApi.requestLocationUpdates(
                googleClientAPI, locationRequest, this, serviceLooper);

            if(isDebugging) {
                Log.d(TAG, "- Recorder attached - start recording location updates");
            }
        } else {
            if (this.startRecordingOnConnect) {
                googleClientAPI.connect();
            }
        }
    }

    private void stopLocationWatching() {
        if (this.isWatchingLocation && googleClientAPI != null && googleClientAPI.isConnected()) {
            //flush the location updates from the api
            LocationServices.FusedLocationApi.removeLocationUpdates(
                googleClientAPI, this);

            this.isWatchingLocation = false;

            if (isDebugging) {
                Log.w(TAG, "- Recorder detached - stop recording location updates");
            }
        }
    }

    private void startDetectingActivities() {
        if (!isDetectingActivities && googleClientAPI != null) {
            if (!googleClientAPI.isConnected()) {
                if (activitiesInterval > 0) {
                    googleClientAPI.connect();
                }
            } else if (activitiesInterval > 0) {
                isDetectingActivities = true;

                ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
                    googleClientAPI, activitiesInterval, detectedActivitiesPI);

                Log.d(TAG, "- Activity Listener registered with interval " + activitiesInterval);
            }
        }
    }

    private void stopDetectingActivities() {
        if (isDetectingActivities && googleClientAPI != null && googleClientAPI.isConnected()) {
            ActivityRecognition.ActivityRecognitionApi.removeActivityUpdates(
                googleClientAPI, detectedActivitiesPI);

            isDetectingActivities = false;

            if (isDebugging) {
                Log.d(TAG, "- Activity Listener unregistered");
            }
        }
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "- Connected to Play API -- All ready to record");

        if (this.startRecordingOnConnect) {
            this.startRecordingOnConnect = false;

            startLocationWatching();
        } else {
            stopLocationWatching();
        }

        if (activitiesInterval > 0) {
            startDetectingActivities();
        }
    }

    @Override
    public void onConnectionFailed(com.google.android.gms.common.ConnectionResult result) {
        Log.e(TAG, "We failed to connect to the Google API! Possibly API is not installed on target.");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.e(TAG, "GoogleApiClient connection has been suspend");
    }

    /**
     * Translates a number representing desired accuracy of GeoLocation system from set [0, 10, 100, 1000].
     * 0:  most aggressive, most accurate, worst battery drain
     * 1000:  least aggressive, least accurate, best for battery.
     */
    private Integer translateDesiredAccuracy(Integer accuracy) {
        if(accuracy <= 0) {
            accuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
        } else if(accuracy <= 100) {
            accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        } else if(accuracy  <= 1000) {
            accuracy = LocationRequest.PRIORITY_LOW_POWER;
        } else if(accuracy <= 10000) {
            accuracy = LocationRequest.PRIORITY_NO_POWER;
        } else {
            accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        }

        return accuracy;
    }

    private void recordState() {
        if (lastLocation == null || lastActivity == null || lastActivity.getConfidence() == 0) return;

        Intent batteryStatus = registerReceiver(null, batteryStatusFilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (100 * level) / scale;
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        boolean isRecording = sharedPrefs.contains("##");
        boolean isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isWifiEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        storageHelper.append(lastLocation, lastActivity, batteryLevel,
            isCharging, isGPSEnabled, isWifiEnabled, !isStillMode, isRecording);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Destroyed Location Update Service - Cleaning up");

        cleanUp();
    }

    private void cleanUp() {
        stopLocationWatching();
        stopDetectingActivities();

        if (googleClientAPI != null && googleClientAPI.isConnected()) {
            googleClientAPI.disconnect();

            googleClientAPI = null;
        }

        stopForeground(true);

        if (detectedActivitiesReceiver != null) {
            unregisterReceiver(detectedActivitiesReceiver);

            detectedActivitiesReceiver = null;
        }

        if (syncAlarmReceiver != null) {
            unregisterReceiver(syncAlarmReceiver);

            syncAlarmReceiver = null;
        }

        if (startRecordingReceiver != null) {
            broadcastManager.unregisterReceiver(startRecordingReceiver);

            startRecordingReceiver = null;
        }

        if (storageHelper != null) {
            // use OTHER activity to remember when service stops
            lastActivity = new DetectedActivity(-1, 100);

            recordState();
        }

        serviceLooper.quit();
        syncLooper.quit();

        wakeLock.release();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!sharedPrefs.contains("##")) {
            Log.w(TAG, "Application killed from task manager - Cleaning up");

            stopSelf();
        }

        super.onTaskRemoved(rootIntent);
    }

}
