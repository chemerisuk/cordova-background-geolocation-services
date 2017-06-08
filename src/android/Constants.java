package com.flybuy.cordova.location;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import android.util.Log;
import java.util.ArrayList;
import android.content.res.Resources;

public class Constants {
  private Constants() {}

  private static final String P_NAME = "com.flybuy.cordova.location.";

  //Receiver Paths for both
  public static final String STOP_RECORDING  = P_NAME + "STOP_RECORDING";
  public static final String START_RECORDING = P_NAME + "START_RECORDING";
  public static final String CHANGE_AGGRESSIVE = P_NAME + "CHANGE_AGGRESSIVE";
  public static final String STOP_GEOFENCES = P_NAME + "STOP_GEOFENCES";
  public static final String CALLBACK_LOCATION_UPDATE = P_NAME + "CALLBACK_LOCATION_UPDATE";
  public static final String CALLBACK_ACTIVITY_UPDATE = P_NAME + "CALLBACK_ACTIVITY_UPDATE";
  public static final String ACTIVITY_EXTRA = P_NAME + ".ACTIVITY_EXTRA";
  public static final String LOCATION_EXTRA = P_NAME + ".LOCATION_EXTRA";

  //Receiver paths for service
  public static String LOCATION_UPDATE = P_NAME + "LOCATION_UPDATE";
  public static String DETECTED_ACTIVITY_UPDATE = P_NAME + "DETECTED_ACTIVITY_UPDATE";
  public static String SYNC_ALARM_UPDATE = P_NAME + "SYNC_ALARM_UPDATE";
  public static String STILL_ACTIVITY_UPDATE = P_NAME + "STILL_ACTIVITY_UPDATE";

  private static final String ConstantsTAG = "Constants";

  public static final String AGGRESSIVE_FLAG = "#AGGRESSIVE_FLAG#";
  public static final String PERSISTING_FLAG = "#PERSISTING_FLAG#";

  public static final int STILL_RECORD_FLAG = 1;
  public static final int GPS_RECORD_FLAG = 2;
  public static final int NETWORK_RECORD_FLAG = 4;
  public static final int CHARGING_RECORD_FLAG = 8;
  public static final int POWERSAVING_RECORD_FLAG = 16;
  public static final int MOCKED_RECORD_FLAG = 32;
  public static final int UNAVAILABLE_RECORD_FLAG = 64;


  public static String getActivityString(int detectedActivityType) {
      switch(detectedActivityType) {
            case DetectedActivity.IN_VEHICLE:
                return "IN_VEHICLE";
            case DetectedActivity.ON_BICYCLE:
                return "ON_BICYCLE";
            case DetectedActivity.ON_FOOT:
                return "ON_FOOT";
            case DetectedActivity.RUNNING:
                return "RUNNING";
            case DetectedActivity.STILL:
                return "STILL";
            case DetectedActivity.TILTING:
                return "TILTING";
            case DetectedActivity.UNKNOWN:
                return "UNKNOWN";
            case DetectedActivity.WALKING:
                return "WALKING";
            default:
                return "OTHER";
        }
    }
}
