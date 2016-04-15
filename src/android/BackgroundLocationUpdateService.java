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

//Detected Activities imports

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

    private Location lastLocation;
    private DetectedActivity lastActivity;
    private StorageHelper storageHelper;
    private IntentFilter batteryStatusFilter;

    private Boolean isDebugging;
    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";
    private WakeLock wakeLock;
    private String syncUrl;
    private int syncInterval;
    private String deviceToken;

    private Boolean isDetectingActivities = false;
    private Boolean isRecording = false;
    private boolean startRecordingOnConnect = true;

    private LocationRequest locationRequest;
    private volatile Looper serviceLooper;
    private volatile Handler serviceHandler;

    private SharedPreferences sharedPrefs;
    private SharedPreferences.Editor sharedPrefsEditor;
    private LocalBroadcastManager broadcastManager;

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

        HandlerThread thread = new HandlerThread("HandlerThread[" + TAG + "]");
        thread.start();

        serviceLooper = thread.getLooper();
        serviceHandler = new Handler(serviceLooper);

        Intent detectedActivitiesIntent = new Intent(Constants.DETECTED_ACTIVITY_UPDATE);
        if (Build.VERSION.SDK_INT >= 16) {
            // http://stackoverflow.com/questions/17768932/service-crashing-and-restarting/18199749#18199749
            detectedActivitiesIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        detectedActivitiesPI = PendingIntent.getBroadcast(this, 9002, detectedActivitiesIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.DETECTED_ACTIVITY_UPDATE), null, serviceHandler);

        broadcastManager = LocalBroadcastManager.getInstance(this);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefsEditor = sharedPrefs.edit();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        batteryStatusFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        lastActivity  = new DetectedActivity(DetectedActivity.UNKNOWN, 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);

        if (intent != null) {
            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));

            interval             = Integer.parseInt(intent.getStringExtra("interval"));
            fastestInterval      = Integer.parseInt(intent.getStringExtra("fastestInterval"));
            aggressiveInterval   = Integer.parseInt(intent.getStringExtra("aggressiveInterval"));
            activitiesInterval   = Integer.parseInt(intent.getStringExtra("activitiesInterval"));
            activitiesConfidence = Integer.parseInt(intent.getStringExtra("activitiesConfidence"));

            isDebugging = Boolean.parseBoolean(intent.getStringExtra("isDebugging"));
            notificationTitle = intent.getStringExtra("notificationTitle");
            notificationText = intent.getStringExtra("notificationText");

            syncUrl = intent.getStringExtra("syncUrl");
            syncInterval = Integer.parseInt(intent.getStringExtra("syncInterval"));
            deviceToken = intent.getStringExtra("deviceToken");

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

            startForeground(startId, notification);

            if (storageHelper != null) {
                storageHelper.shutdown();
            }

            storageHelper = new StorageHelper(this, syncUrl, syncInterval, deviceToken);

            if (activitiesInterval > 0) {
                startDetectingActivities();
            } else {
                stopDetectingActivities();
            }

            startLocationWatching();
        }

        // Log.i(TAG, "- url: " + url);
        // Log.i(TAG, "- params: "  + params.toString());
        Log.i(TAG, "- interval: "             + interval);
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

        //We want this service to continue running until it is explicitly stopped
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
        resId = res.getIdentifier("icon", "drawable", pkgName);

        return resId;
    }

    /**
     * Called when the location has changed.
     *
     * @param location
     */
    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;

        if (isDebugging) {
            Log.d(TAG, "- locationUpdateReceiver: " + lastLocation);
        }

        //This is all for setting the callback for android which currently does not work
        Intent localIntent = new Intent(Constants.CALLBACK_LOCATION_UPDATE);
        localIntent.putExtra(Constants.LOCATION_EXTRA, lastLocation);
        broadcastManager.sendBroadcast(localIntent);

        recordLocations(lastLocation);

        syncState();
    }

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
            if (isRecording) {
                if (isDebugging) {
                    Toast.makeText(context, "Detected Activity was STILL, Stop recording", Toast.LENGTH_SHORT).show();
                }

                syncState();

                stopLocationWatching();
            }
        } else if (!isRecording) {
            if (isDebugging) {
                Toast.makeText(context, "Detected Activity was ACTIVE, Start Recording", Toast.LENGTH_SHORT).show();
            }

            syncState();

            startLocationWatching();
        }
      }
    };

    //Helper function to get the screen scale for our big icon
    public float getImageFactor(Resources r) {
         DisplayMetrics metrics = r.getDisplayMetrics();
         float multiplier=metrics.density/3f;
         return multiplier;
    }

    //retrieves the plugin resource ID from our resources folder for a given drawable name
    public Integer getPluginResource(String resourceName) {
        return getApplication().getResources().getIdentifier(resourceName, "drawable", getApplication().getPackageName());
    }

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
                    .setInterval(interval)
                    .setSmallestDisplacement(distanceFilter);

            this.isRecording = true;

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
        if (this.isRecording && googleClientAPI != null && googleClientAPI.isConnected()) {
            //flush the location updates from the api
            LocationServices.FusedLocationApi.removeLocationUpdates(
                googleClientAPI, this);

            this.isRecording = false;

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

    private void syncState() {
        if (lastLocation == null) return;

        Intent batteryStatus = registerReceiver(null, batteryStatusFilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        storageHelper.append(lastLocation, lastActivity, (100 * level) / scale,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private int recordLocations(Location location) {
        int locationsCount = 0;
        int n = sharedPrefs.getInt("??", -1);

        if (n < 0) return locationsCount;

        // for (Location location : result.getLocations()) {
            int ilat = (int)(location.getLatitude() * 100000);
            int ilng = (int)(location.getLongitude() * 100000);

            sharedPrefsEditor.putInt("?" + n++, ilat);
            sharedPrefsEditor.putInt("?" + n++, ilng);

            long timestamp;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                timestamp = location.getElapsedRealtimeNanos() / 1000000;
            } else {
                timestamp = location.getTime();
            }

            sharedPrefsEditor.putLong("?" + n++, timestamp);

            ++locationsCount;
        // }

        if (locationsCount > 0) {
            sharedPrefsEditor.putInt("??", n);
            sharedPrefsEditor.commit();

            if (isDebugging) {
                Log.w(TAG, "Recorded " + locationsCount + " location(s) into SharedPreferences");
            }
        }

        return locationsCount;
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

        serviceLooper.quit();

        wakeLock.release();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        int n = sharedPrefs.getInt("??", -1);

        if (n == -1) {
            Log.w(TAG, "Application killed from task manager - Cleaning up");

            stopSelf();
        }

        super.onTaskRemoved(rootIntent);
    }

}
