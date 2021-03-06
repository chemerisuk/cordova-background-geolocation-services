package com.flybuy.cordova.location;

import java.util.List;
import java.util.Iterator;
import java.util.Random;

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
import android.content.ContentValues;
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
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderApi;
import com.google.android.gms.location.LocationAvailability;

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
import android.support.v4.app.NotificationCompat;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import java.net.MalformedURLException;
import java.net.URL;


public class BackgroundLocationUpdateService extends Service implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "BackgroundLocationUpdateService";

    private long lastUpdateTime = 0l;
    private Boolean fastestSpeed = false;

    private GoogleApiClient googleClientAPI;
    private PendingIntent locationUpdatePI;
    private PendingIntent detectedActivitiesPI;

    private Integer desiredAccuracy = 100;
    private Integer distanceFilter  = 30;
    private Integer activitiesInterval = 0;
    private Integer activitiesConfidence = 75;

    private static final Integer SECONDS_PER_MINUTE      = 60;
    private static final Integer MILLISECONDS_PER_SECOND = 60;

    private long  interval             = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND * 5;
    private long  fastestInterval      = (long)  SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND;
    private long  sleepInterval        = 0;
    private long  stillInterval        = 0;

    private Location lastLocation;
    private DetectedActivity lastActivity;
    private IntentFilter batteryStatusFilter;

    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";
    private WakeLock wakeLock;
    private URL syncUrl;
    private int syncInterval;
    private String deviceToken;

    private boolean isDebugging = false;
    private boolean isDetectingActivities = false;
    private boolean isWatchingLocation = false;
    private boolean isStillMode = false;
    private boolean startRecordingOnConnect = true;

    private LocationManager locationManager;
    private LocationRequest locationRequest;
    private volatile Looper serviceLooper;

    private PowerManager powerManager;
    private SharedPreferences sharedPrefs;
    private SharedPreferences.Editor sharedPrefsEditor;
    private LocalBroadcastManager broadcastManager;

    private AlarmManager alarmMgr;
    private PendingIntent alarmPI;
    private PendingIntent servicePI;

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

        connectToPlayAPI();

        HandlerThread thread = new HandlerThread("HandlerThread[" + TAG + "#1]");
        thread.start();

        serviceLooper = thread.getLooper();

        Intent locationUpdateIntent = new Intent(Constants.LOCATION_UPDATE);
        if (Build.VERSION.SDK_INT >= 16) {
            // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
            locationUpdateIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        locationUpdatePI = PendingIntent.getBroadcast(this, 9001, locationUpdateIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(locationUpdateReceiver, new IntentFilter(Constants.LOCATION_UPDATE), null, new Handler(serviceLooper));

        Intent detectedActivitiesIntent = new Intent(Constants.DETECTED_ACTIVITY_UPDATE);
        if (Build.VERSION.SDK_INT >= 16) {
            // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
            detectedActivitiesIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        detectedActivitiesPI = PendingIntent.getBroadcast(this, 9002, detectedActivitiesIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.DETECTED_ACTIVITY_UPDATE), null, new Handler(serviceLooper));

        broadcastManager = LocalBroadcastManager.getInstance(this);
        broadcastManager.registerReceiver(changeAggressiveReceiver, new IntentFilter(Constants.CHANGE_AGGRESSIVE));

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefsEditor = sharedPrefs.edit();

        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
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
        registerReceiver(syncAlarmReceiver, new IntentFilter(Constants.SYNC_ALARM_UPDATE));

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);

        if (intent != null) {
            Intent serviceIntent = new Intent(getApplicationContext(), BackgroundLocationUpdateService.class);
            serviceIntent.putExtras(intent.getExtras());
            servicePI = PendingIntent.getService(getApplicationContext(), 0, serviceIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));

            interval             = Integer.parseInt(intent.getStringExtra("interval"));
            fastestInterval      = Integer.parseInt(intent.getStringExtra("fastestInterval"));
            stillInterval        = Integer.parseInt(intent.getStringExtra("stillInterval"));
            sleepInterval        = Integer.parseInt(intent.getStringExtra("sleepInterval"));
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
            Context context     = getApplicationContext();
            String packageName  = context.getPackageName();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PendingIntent contentIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setContentIntent(contentIntent)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setSmallIcon(getIconResId())
                .setColor(0xFFFF7E00);

            Notification notification = builder.build();

            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;
            notification.contentView.setImageViewResource(getIconResId(), getApplicationInfo().icon);

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
        Log.i(TAG, "- fastestInterval: "      + fastestInterval);
        Log.i(TAG, "- interval: "             + interval);
        Log.i(TAG, "- stillInterval: "        + stillInterval);
        Log.i(TAG, "- sleepInterval: "        + sleepInterval);

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

        return res.getIdentifier("ic_stat_notify", "drawable", pkgName);
    }

    private BroadcastReceiver changeAggressiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isStillMode = false;

            if (isDebugging) {
                Toast.makeText(context, "Change Aggressive", Toast.LENGTH_SHORT).show();
            }

            if (activitiesInterval > 0) {
                stopDetectingActivities();
                // sometimes activity detection freezes so restart to wakeup
                startDetectingActivities();
                // reset current activity value
                lastActivity = new DetectedActivity(DetectedActivity.UNKNOWN, 0);
            }

            startLocationWatching();
            // close DB connection to force db to create the file
            getContentResolver()
                .acquireContentProviderClient(LocationsProvider.CONTENT_URI)
                .release();
        }
    };

    private BroadcastReceiver locationUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            restartServicePing();

            LocationResult result = LocationResult.extractResult(intent);

            if (result == null) return;

            Location location = result.getLastLocation();

            if (isDebugging) {
                Log.d(TAG, "- locationUpdateReceiver: " + location);
            }

            if (location == null) {
                return;
            }

            if (!location.hasBearing() && lastLocation != null) {
                location.setBearing(lastLocation.bearingTo(location));
            }

            // if (!location.hasSpeed() && lastLocation != null) {
            //     float delta = (float)(location.getTime() - lastLocation.getTime());

            //     location.setSpeed(1000f * location.distanceTo(lastLocation) / delta);
            // }

            lastLocation = location;

            //This is all for setting the callback for android which currently does not work
            Intent localIntent = new Intent(Constants.CALLBACK_LOCATION_UPDATE);
            localIntent.putExtra(Constants.LOCATION_EXTRA, lastLocation);
            broadcastManager.sendBroadcast(localIntent);

            recordState();
        }
    };

    private BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            restartServicePing();

            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);

            if (result == null) return;

            lastActivity = result.getMostProbableActivity();

            if (isDebugging) {
                Log.d(TAG, "MOST LIKELY ACTIVITY: " + Constants.getActivityString(lastActivity.getType()) + " " + lastActivity.getConfidence());
            }

            Intent mIntent = new Intent(Constants.CALLBACK_ACTIVITY_UPDATE);
            mIntent.putParcelableArrayListExtra(Constants.ACTIVITY_EXTRA,
                (ArrayList<DetectedActivity>) result.getProbableActivities());
            broadcastManager.sendBroadcast(mIntent);

            if (!isDetectingActivities) return;

            if (lastActivity.getType() == DetectedActivity.STILL && lastActivity.getConfidence() >= activitiesConfidence) {
                if (!isStillMode) {
                    isStillMode = true;

                    stopLocationWatching();
                    // restart watching with increased intervals
                    startLocationWatching();
                }
            } else {
                if (isStillMode) {
                    isStillMode = false;

                    stopLocationWatching();
                    // restart watching with decreased intervals
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

            Intent serviceIntent = new Intent(context, BackgroundLocationUploadService.class);

            serviceIntent.putExtra(BackgroundLocationUploadService.URL_EXTRA, syncUrl);
            serviceIntent.putExtra(BackgroundLocationUploadService.TOKEN_EXTRA, deviceToken);

            startService(serviceIntent);
        }
    };

    private void connectToPlayAPI() {
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
        if (googleClientAPI == null) return;

        if (googleClientAPI.isConnected()) {
            long currentInterval = interval;
            long currentFastestInterval = fastestInterval;

            if (!isStillMode) {
                if (isDebugging) {
                    Log.d(TAG, "Start location updates in NORMAL mode");

                    Toast.makeText(getApplicationContext(),
                        "Start location updates in NORMAL mode", Toast.LENGTH_SHORT).show();
                }
            } else if (sharedPrefs.contains(Constants.AGGRESSIVE_FLAG)) {
                if (stillInterval > 0) {
                    currentFastestInterval = stillInterval;
                    currentInterval = stillInterval * interval / fastestInterval;

                    if (isDebugging) {
                        Log.d(TAG, "Start location updates in STILL mode");

                        Toast.makeText(getApplicationContext(),
                            "Start location updates in STILL mode", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                if (sleepInterval > 0) {
                    currentFastestInterval = sleepInterval;
                    currentInterval = sleepInterval * interval / fastestInterval;

                    if (isDebugging) {
                        Log.d(TAG, "Start location updates in SLEEP mode");

                        Toast.makeText(getApplicationContext(),
                            "Start location updates in SLEEP mode", Toast.LENGTH_SHORT).show();
                    }
                }
            }

            locationRequest = LocationRequest.create()
                    .setPriority(translateDesiredAccuracy(desiredAccuracy))
                    .setInterval(currentInterval)
                    .setFastestInterval(currentFastestInterval)
                    .setSmallestDisplacement(distanceFilter);

            LocationServices.FusedLocationApi.requestLocationUpdates(
                googleClientAPI, locationRequest, locationUpdatePI);

            isWatchingLocation = true;
        } else {
            if (this.startRecordingOnConnect) {
                googleClientAPI.connect();
            }
        }
    }

    private void stopLocationWatching() {
        if (googleClientAPI == null) return;

        if (isWatchingLocation && googleClientAPI.isConnected()) {
            //flush the location updates from the api
            LocationServices.FusedLocationApi.removeLocationUpdates(
                googleClientAPI, locationUpdatePI);

            isWatchingLocation = false;

            if (isDebugging) {
                Toast.makeText(getApplicationContext(),
                    "Stop recording location updates", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDetectingActivities() {
        if (googleClientAPI == null) return;

        if (!isDetectingActivities && activitiesInterval > 0) {
            if (!googleClientAPI.isConnected()) {
                googleClientAPI.connect();
            } else {
                ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
                    googleClientAPI, activitiesInterval, detectedActivitiesPI);

                isDetectingActivities = true;

                Log.d(TAG, "- Activity Listener registered with interval " + activitiesInterval);
            }
        }
    }

    private void stopDetectingActivities() {
        if (googleClientAPI == null) return;

        if (isDetectingActivities && googleClientAPI.isConnected()) {
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
        if (lastLocation == null) return;

        if (lastActivity == null) {
            // use OTHER activity by default
            lastActivity = new DetectedActivity(-1, 100);
        }

        Intent batteryStatus = registerReceiver(null, batteryStatusFilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (100 * level) / scale;
        boolean isRecording = sharedPrefs.contains(Constants.PERSISTING_FLAG);
        boolean isAggressive = sharedPrefs.contains(Constants.AGGRESSIVE_FLAG);

        int flags = isStillMode ? 1 : 0;

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            flags |= Constants.GPS_RECORD_FLAG;
        }

        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            flags |= Constants.NETWORK_RECORD_FLAG;
        }

        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
            flags |= Constants.CHARGING_RECORD_FLAG;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (powerManager.isPowerSaveMode()) {
                flags |= Constants.POWERSAVING_RECORD_FLAG;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (lastLocation.isFromMockProvider()) {
                flags |= Constants.MOCKED_RECORD_FLAG;
            }
        }

        if (googleClientAPI != null && googleClientAPI.isConnected()) {
            LocationAvailability locationAvailability = LocationServices
                .FusedLocationApi.getLocationAvailability(googleClientAPI);

            if (locationAvailability != null && !locationAvailability.isLocationAvailable()) {
                flags |= Constants.UNAVAILABLE_RECORD_FLAG;
            }
        }

        ContentValues values = new ContentValues();

        long timestamp;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            timestamp = lastLocation.getElapsedRealtimeNanos() / 1000000;
        } else {
            timestamp = lastLocation.getTime();
        }

        values.put("latitude", lastLocation.getLatitude());
        values.put("longitude", lastLocation.getLongitude());
        values.put("accuracy", lastLocation.getAccuracy());
        values.put("speed", lastLocation.getSpeed());
        values.put("heading", Math.round(lastLocation.getBearing()));
        values.put("flags", flags);
        values.put("activity_type", lastActivity.getType());
        values.put("activity_confidence", lastActivity.getConfidence());
        values.put("battery_level", batteryLevel);
        values.put("elapsed", timestamp);
        values.put("timestamp", System.currentTimeMillis());

        if (isRecording) {
            values.put("status", 1);
        } else if (isAggressive) {
            values.put("status", 2);
        } else {
            values.put("status", 0);
        }

        values.put("recording", isRecording);

        getContentResolver().insert(LocationsProvider.CONTENT_URI, values);
    }

    private void restartServicePing() {
        if (sharedPrefs.contains(Constants.AGGRESSIVE_FLAG)) {
            alarmMgr.cancel(servicePI);

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval * 5, servicePI);
            } else {
                alarmMgr.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval * 5, servicePI);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Destroyed Location Update Service - Cleaning up");

        cleanUp();

        if (!sharedPrefs.contains(Constants.AGGRESSIVE_FLAG)) {
            alarmMgr.cancel(servicePI);
        }
    }

    private void cleanUp() {
        stopLocationWatching();
        stopDetectingActivities();

        if (googleClientAPI != null && googleClientAPI.isConnected()) {
            googleClientAPI.disconnect();

            googleClientAPI = null;
        }

        stopForeground(true);

        if (locationUpdateReceiver != null) {
            unregisterReceiver(locationUpdateReceiver);

            locationUpdateReceiver = null;
        }

        if (detectedActivitiesReceiver != null) {
            unregisterReceiver(detectedActivitiesReceiver);

            detectedActivitiesReceiver = null;
        }

        if (syncAlarmReceiver != null) {
            unregisterReceiver(syncAlarmReceiver);

            syncAlarmReceiver = null;
        }

        if (changeAggressiveReceiver != null) {
            broadcastManager.unregisterReceiver(changeAggressiveReceiver);

            changeAggressiveReceiver = null;
        }

        if (locationManager != null) {
            lastActivity = null;

            recordState();
        }

        serviceLooper.quit();

        wakeLock.release();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!sharedPrefs.contains(Constants.AGGRESSIVE_FLAG)) {
            Log.w(TAG, "Application killed from task manager - Cleaning up");

            stopSelf();
        }

        super.onTaskRemoved(rootIntent);
    }

}
