var exec = require("cordova/exec");
module.exports = {
    pName : 'BackgroundLocationServices',
    config: {},
     configure: function(config, success, failure) {
        this.config = config;
        var distanceFilter      = (config.distanceFilter   >= 0) ? config.distanceFilter   : 500, // meters
            desiredAccuracy     = (config.desiredAccuracy  >= 0) ? config.desiredAccuracy  : 100, // meters
            interval            = (config.interval         >= 0) ? config.interval        : 900000, // milliseconds
            fastestInterval     = (config.fastestInterval  >= 0) ? config.fastestInterval : 120000, // milliseconds
            aggressiveInterval  = (config.aggressiveInterval > 0) ? config.aggressiveInterval : 4000, //mulliseconds
            debug               = config.debug || false,
            notificationTitle   = config.notificationTitle || "Background tracking",
            notificationText    = config.notificationText  || "ENABLED",
            keepAlive           = config.keepAlive || false,
            activitiesInterval  = config.activitiesInterval || 0,
            activitiesConfidence = config.activitiesConfidence || 75,
            keepAwake           = config.keepAwake || false,
            syncUrl             = config.syncUrl || "",
            syncInterval        = config.syncInterval || 600,
            deviceToken         = config.deviceToken || "";

        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'configure',
             [distanceFilter, desiredAccuracy,  interval, fastestInterval, aggressiveInterval, debug, notificationTitle, notificationText, keepAlive, keepAwake, activitiesInterval, activitiesConfidence, syncUrl, syncInterval, deviceToken]
        );
    },
    registerForLocationUpdates : function(success, failure, config) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'registerForLocationUpdates',
             []
        );
    },
    registerForActivityUpdates : function(success, failure, config) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'registerForActivityUpdates',
             []
        );
    },
    start: function(success, failure, config) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'start',
             []);
    },
    stop: function(success, failure, config) {
        exec(success || function() {},
            failure || function() {},
            'BackgroundLocationServices',
            'stop',
            []);
    },
    getVersion: function (success, failure) {
        exec(success || function() {},
            failure || function() {},
            'BackgroundLocationServices',
            'getVersion',
            []);
    },
    startTrackRecording: function(persistant, success, failure) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'startTrackRecording',
             [persistant == null ? true : persistant]);
    },
    stopTrackRecording: function(success, failure) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'stopTrackRecording',
             []);
    },
    serializeTrack: function(success, failure) {
        exec(success || function() {},
             failure || function() {},
             'BackgroundLocationServices',
             'serializeTrack',
             []);
    }
};
