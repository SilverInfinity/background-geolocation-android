/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

Differences to original version:

1. location is not persisted to db anymore, but broadcasted using intents instead
*/

package com.tenforwardconsulting.bgloc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import androidx.core.util.Consumer;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.Priority;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.provider.AbstractLocationProvider;
import com.marianhello.utils.ToneGenerator.Tone;

import java.util.List;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static java.lang.Math.abs;
import static java.lang.Math.pow;
import static java.lang.Math.round;


public class DistanceFilterLocationProvider extends AbstractLocationProvider implements LocationListener {

    private static final String TAG = DistanceFilterLocationProvider.class.getSimpleName();
    private static final String P_NAME = "com.tenforwardconsulting.cordova.bgloc";

    private static final String STATIONARY_REGION_ACTION        = P_NAME + ".STATIONARY_REGION_ACTION";
    private static final String STATIONARY_ALARM_ACTION         = P_NAME + ".STATIONARY_ALARM_ACTION";
    private static final String SINGLE_LOCATION_UPDATE_ACTION   = P_NAME + ".SINGLE_LOCATION_UPDATE_ACTION";
    private static final String STATIONARY_LOCATION_MONITOR_ACTION = P_NAME + ".STATIONARY_LOCATION_MONITOR_ACTION";

    private static final long STATIONARY_TIMEOUT                                = 10 * 1000 * 60;    // 10 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_LAZY         = 5 * 1000 * 60;    // 5 minutes.
    private static final long STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE   = 5 * 1000 * 60;    // 5 minutes.
    private static final int MAX_STATIONARY_ACQUISITION_ATTEMPTS = 1;
    private static final int MAX_SPEED_ACQUISITION_ATTEMPTS = 3;

    private Boolean buildVersionSPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

    private Boolean isMoving = false;
    private Boolean isAcquiringStationaryLocation = false;
    private Boolean isAcquiringSpeed = false;
    private Integer locationAcquisitionAttempts = 0;
    private Instant lastStationaryUpdate = null;

    private Location lastLocation;
    private Location stationaryLocation;
    private float stationaryRadius;
    private PendingIntent stationaryAlarmPI;
    private PendingIntent stationaryLocationPollingPI;
    private long stationaryLocationPollingInterval;
    private PendingIntent stationaryRegionPI;
    private PendingIntent singleUpdatePI;
    private Integer scaledDistanceFilter;

    private FusedLocationProviderClient mFusedLocationClient;

    private LocationManager locationManager;
    private AlarmManager alarmManager;

    private boolean isStarted = false;

    public DistanceFilterLocationProvider(Context context) {
        super(context);
        PROVIDER_ID = Config.DISTANCE_FILTER_PROVIDER;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        // Stop-detection PI
        stationaryAlarmPI = PendingIntent.getBroadcast(mContext, 0, new Intent(STATIONARY_ALARM_ACTION), buildVersionSPlus ? PendingIntent.FLAG_IMMUTABLE : 0);
        registerReceiver(stationaryAlarmReceiver, new IntentFilter(STATIONARY_ALARM_ACTION));

        // Stationary region PI
        stationaryRegionPI = PendingIntent.getBroadcast(mContext, 0, new Intent(STATIONARY_REGION_ACTION), buildVersionSPlus ? PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(stationaryRegionReceiver, new IntentFilter(STATIONARY_REGION_ACTION));

        // Stationary location monitor PI
        stationaryLocationPollingPI = PendingIntent.getBroadcast(mContext, 0, new Intent(STATIONARY_LOCATION_MONITOR_ACTION), buildVersionSPlus ? PendingIntent.FLAG_MUTABLE : 0);
        registerReceiver(stationaryLocationMonitorReceiver, new IntentFilter(STATIONARY_LOCATION_MONITOR_ACTION));

        // One-shot PI (TODO currently unused)
        singleUpdatePI = PendingIntent.getBroadcast(mContext, 0, new Intent(SINGLE_LOCATION_UPDATE_ACTION), buildVersionSPlus ? PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_CANCEL_CURRENT);
        registerReceiver(singleUpdateReceiver, new IntentFilter(SINGLE_LOCATION_UPDATE_ACTION));

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);
    }

    @Override
    public void onStart() {
        if (isStarted) {
            return;
        }

        logger.info("Start recording");
        scaledDistanceFilter = mConfig.getDistanceFilter();
        isStarted = true;
        setPace(true);
    }

    @Override
    public void onStop() {
        if (!isStarted) {
            return;
        }

        try {
            mFusedLocationClient.removeLocationUpdates((LocationListener) this);
            locationManager.removeProximityAlert(stationaryRegionPI);
        } catch (SecurityException e) {
            //noop
        } finally {
            isStarted = false;
        }
    }

    @Override
    public void onCommand(int commandId, int arg1) {
        switch(commandId) {
            case CMD_SWITCH_MODE:
                setPace(arg1 == BACKGROUND_MODE ? false : true);
                return;
        }
    }

    @Override
    public void onConfigure(Config config) {
        Config previousConfig = mConfig;
        super.onConfigure(config);
        boolean needsRestart = 
            previousConfig == null || config == null || 
            previousConfig.getDesiredAccuracy() != config.getDesiredAccuracy() ||
            !previousConfig.getFastestInterval().equals(config.getFastestInterval()) ||
            !previousConfig.getInterval().equals(config.getInterval()) ||
            !previousConfig.getStationaryRadius().equals(config.getStationaryRadius()) ||
            previousConfig.getDistanceFilter() != config.getDistanceFilter() ||
            previousConfig.getStationaryUpdateInterval() != config.getStationaryUpdateInterval() ||
            previousConfig.isDebugging() != config.isDebugging();
        if (isStarted && needsRestart) {
            onStop();
            onStart();
        }
    }

    @Override
    protected void handleStationary (Location location, float radius) {
        super.handleStationary(location, radius);
        lastStationaryUpdate = Instant.now();
    }

    @Override
    protected void handleStationary (Location location) {
        super.handleStationary(location);
        lastStationaryUpdate = Instant.now();
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

    /**
     *
     * @param value set true to engage "aggressive", battery-consuming tracking, false for stationary-region tracking
     */
    private void setPace(Boolean value) {
        if (!isStarted) {
            return;
        }

        logger.info("Setting pace: {}", value);

        Boolean wasMoving   = isMoving;
        isMoving            = value;
        isAcquiringStationaryLocation = false;
        isAcquiringSpeed    = false;
        stationaryLocation  = null;

        try {
            mFusedLocationClient.removeLocationUpdates((LocationListener) this);
            if (isMoving) {
                // setPace can be called while moving, after distanceFilter has been recalculated.  We don't want to re-acquire velocity in this case.
                if (!wasMoving) {
                    isAcquiringSpeed = true;
                }
            } else {
                isAcquiringStationaryLocation = true;
            }

            // Temporarily turn on super-aggressive geolocation on all providers when acquiring velocity or stationary location.
            if (isAcquiringSpeed || isAcquiringStationaryLocation) {
                locationAcquisitionAttempts = 0;
                LocationRequest locationRequest = new LocationRequest.Builder(0)
                    .setMinUpdateDistanceMeters(0)
                    .setMinUpdateIntervalMillis(0)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build();
                mFusedLocationClient.requestLocationUpdates(locationRequest, ContextCompat.getMainExecutor(mContext), this);
            } else {
                LocationRequest locationRequest = new LocationRequest.Builder(0)
                    .setMinUpdateDistanceMeters(scaledDistanceFilter)
                    .setIntervalMillis(mConfig.getInterval())
                    .setMinUpdateIntervalMillis(mConfig.getFastestInterval())
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .build();
                mFusedLocationClient.requestLocationUpdates(locationRequest, ContextCompat.getMainExecutor(mContext), this);
            }
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    /**
     * Returns the most accurate and timely previously detected location.
     * Where the last result is beyond the specified maximum distance or
     * latency a one-off location update is returned via the {@link LocationListener}
     * specified in {@link setChangedLocationListener}.
     * @param minTime Minimum time required between location updates.
     * @return The most accurate and / or timely previously detected location.
     */
    public Location getLastBestLocation() {
        Location bestResult = null;
        String bestProvider = null;
        float bestAccuracy = Float.MAX_VALUE;
        long bestTime = Long.MIN_VALUE;
        long minTime = System.currentTimeMillis() - mConfig.getInterval();

        logger.info("Fetching last best location: radius={} minTime={}", mConfig.getStationaryRadius(), minTime);

        try {
            // Iterate through all the providers on the system, keeping
            // note of the most accurate result within the acceptable time limit.
            // If no result is found within maxTime, return the newest Location.
            List<String> matchingProviders = locationManager.getAllProviders();
            for (String provider: matchingProviders) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null) {
                    logger.debug("Test provider={} lat={} lon={} acy={} v={}m/s time={}", provider, location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getSpeed(), location.getTime());
                    float accuracy = location.getAccuracy();
                    long time = location.getTime();
                    if ((time > minTime && accuracy < bestAccuracy)) {
                        bestProvider = provider;
                        bestResult = location;
                        bestAccuracy = accuracy;
                        bestTime = time;
                    }
                }
            }

            if (bestResult != null) {
                logger.debug("Best result found provider={} lat={} lon={} acy={} v={}m/s time={}", bestProvider, bestResult.getLatitude(), bestResult.getLongitude(), bestResult.getAccuracy(), bestResult.getSpeed(), bestResult.getTime());
            }
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }

        return bestResult;
    }

    public void onLocationChanged(Location location) {
        logger.debug("Location change: {} isMoving={}", location.toString(), isMoving);

        if (!isMoving && !isAcquiringStationaryLocation && stationaryLocation==null) {
            // Perhaps our GPS signal was interupted, re-acquire a stationaryLocation now.
            setPace(false);
        }

        showDebugToast( "mv:" + isMoving + ",acy:" + location.getAccuracy() + ",v:" + location.getSpeed() + ",df:" + scaledDistanceFilter);

        if (isAcquiringStationaryLocation) {
            // if no stationary location, or if stationary location is less accurate:
            if (stationaryLocation == null || stationaryLocation.getAccuracy() > location.getAccuracy()) {
                stationaryLocation = location;
            }
            if (++locationAcquisitionAttempts == MAX_STATIONARY_ACQUISITION_ATTEMPTS) {
                isAcquiringStationaryLocation = false;
                startMonitoringStationaryRegion(stationaryLocation);
                handleStationary(stationaryLocation, stationaryRadius);
                return;
            } else {
                // Unacceptable stationary-location: bail-out and wait for another.
                playDebugTone(Tone.BEEP);
                return;
            }
        } else if (isAcquiringSpeed) {
            if (++locationAcquisitionAttempts == MAX_SPEED_ACQUISITION_ATTEMPTS) {
                // Got enough samples, assume we're confident in reported speed now.  Play "woohoo" sound.
                playDebugTone(Tone.DOODLY_DOO);
                isAcquiringSpeed = false;
                scaledDistanceFilter = calculateDistanceFilter(location.getSpeed());
                setPace(true);
            } else {
                playDebugTone(Tone.BEEP);
                return;
            }
        } else if (isMoving) {
            playDebugTone(Tone.BEEP);

            // Only reset stationaryAlarm when accurate speed is detected, prevents spurious locations from resetting when stopped.
            if ( (location.getSpeed() >= 1) && (location.getAccuracy() <= mConfig.getStationaryRadius()) ) {
                resetStationaryAlarm();
            }
            // Calculate latest distanceFilter, if it changed by 5 m/s, we'll reconfigure our pace.
            Integer newDistanceFilter = calculateDistanceFilter(location.getSpeed());
            if (newDistanceFilter != scaledDistanceFilter.intValue()) {
                logger.info("Updating distanceFilter: new={} old={}", newDistanceFilter, scaledDistanceFilter);
                scaledDistanceFilter = newDistanceFilter;
                setPace(true);
            }
            if (lastLocation != null && location.distanceTo(lastLocation) < mConfig.getDistanceFilter()) {
                return;
            }
        } else if (stationaryLocation != null) {
            return;
        }
        // Go ahead and cache, push to server
        lastLocation = location;
        handleLocation(location);
    }

    public void resetStationaryAlarm() {
        alarmManager.cancel(stationaryAlarmPI);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + STATIONARY_TIMEOUT, stationaryAlarmPI); // Millisec * Second * Minute
    }

    private Integer calculateDistanceFilter(Float speed) {
        Double newDistanceFilter = (double) mConfig.getDistanceFilter();
        if (speed < 100) {
            float roundedDistanceFilter = (round(speed / 5) * 5);
            newDistanceFilter = pow(roundedDistanceFilter, 2) + (double) mConfig.getDistanceFilter();
        }
        return (newDistanceFilter.intValue() < 1000) ? newDistanceFilter.intValue() : 1000;
    }

    private void startMonitoringStationaryRegion(Location location) {
        try {
            mFusedLocationClient.removeLocationUpdates((LocationListener) this);

            float stationaryRadius = mConfig.getStationaryRadius();
            float proximityRadius = (location.getAccuracy() < stationaryRadius) ? stationaryRadius : location.getAccuracy();
            stationaryLocation = location;

            logger.info("startMonitoringStationaryRegion: lat={} lon={} acy={}", location.getLatitude(), location.getLongitude(), proximityRadius);

            // Here be the execution of the stationary region monitor
            locationManager.addProximityAlert(
                    location.getLatitude(),
                    location.getLongitude(),
                    proximityRadius,
                    (long)-1,
                    stationaryRegionPI
            );

            this.stationaryRadius = proximityRadius;

            startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    /**
     * User has exit his stationary region!  Initiate aggressive geolocation!
     */
    public void onExitStationaryRegion(Location location) {
        // Filter-out spurious region-exits:  must have at least a little speed to move out of stationary-region
        playDebugTone(Tone.BEEP_BEEP_BEEP);

        logger.info("Exited stationary: lat={} long={} acy={}}'",
                location.getLatitude(), location.getLongitude(), location.getAccuracy());

        try {
            // Cancel the periodic stationary location monitor alarm.
            alarmManager.cancel(stationaryLocationPollingPI);
            // Kill the current region-monitor we just walked out of.
            locationManager.removeProximityAlert(stationaryRegionPI);
            // Engage aggressive tracking.
            this.setPace(true);
        } catch (SecurityException e) {
            logger.error("Security exception: {}", e.getMessage());
            this.handleSecurityException(e);
        }
    }

    public void startPollingStationaryLocation(long interval) {
        // proximity-alerts don't seem to work while suspended in latest Android 4.42 (works in 4.03).  Have to use AlarmManager to sample
        //  location at regular intervals with a one-shot.
        stationaryLocationPollingInterval = interval;
        alarmManager.cancel(stationaryLocationPollingPI);
        long start = System.currentTimeMillis() + (60 * 1000);
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, start, interval, stationaryLocationPollingPI);
    }

    public void onPollStationaryLocation(Location location) {
        float stationaryRadius = mConfig.getStationaryRadius();
        if (isMoving) {
            return;
        }
        playDebugTone(Tone.BEEP);

        float distance = 0.0f;
        if (stationaryLocation != null) {
            distance = abs(location.distanceTo(stationaryLocation) - stationaryLocation.getAccuracy() - location.getAccuracy());
        }

        showDebugToast("Stationary exit in " + (stationaryRadius-distance) + "m");

        // TODO http://www.cse.buffalo.edu/~demirbas/publications/proximity.pdf
        // determine if we're almost out of stationary-distance and increase monitoring-rate.
        logger.info("Distance from stationary location: {}", distance);
        if (distance > stationaryRadius) {
            onExitStationaryRegion(location);
        } else {
            // lastStationaryUpdate should not be null, but if it is go ahead and assign it one.
            if (lastStationaryUpdate == null) lastStationaryUpdate = Instant.now();
            Instant now = Instant.now();
            long timeSpentStationary = ChronoUnit.MINUTES.between(lastStationaryUpdate, now);
            logger.debug("Stationary location Update Check: " + location.toString() + " | Mins Stationary: " + timeSpentStationary);
            if (timeSpentStationary >= mConfig.getStationaryUpdateInterval()) {
                logger.debug("Stationary location Updating!" + location.toString() + " | Mins Stationary: " + timeSpentStationary);
                handleStationary(location);
            }
            // distance is abs so it will always be > 0 except for the small change of being == 0. I am not sure what the reason for this check is.
            // 0 <= distance < stationaryRadius
            // todo:
            // should i compare this to the stationaryRadius ? 
            // if distance is < 1/2 * stationaryRadius go agressive else go lazy?
            if (distance > 0) {
                startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_AGGRESSIVE);
            } else if (stationaryLocationPollingInterval != STATIONARY_LOCATION_POLLING_INTERVAL_LAZY) {
                startPollingStationaryLocation(STATIONARY_LOCATION_POLLING_INTERVAL_LAZY);
            }
        }
    }

    /**
     * Broadcast receiver for receiving a single-update from LocationManager.
     */
    private BroadcastReceiver singleUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_LOCATION_CHANGED;
            Location location = (Location)intent.getExtras().get(key);
            if (location != null) {
                mFusedLocationClient.removeLocationUpdates(singleUpdatePI);
                logger.debug("Single location update: " + location.toString());
                onPollStationaryLocation(location);
            }
        }
    };

    /**
     * Broadcast receiver which detects a user has stopped for a long enough time to be determined as STOPPED
     */
    private BroadcastReceiver stationaryAlarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            logger.info("stationaryAlarm fired");
            setPace(false);
        }
    };

    /**
     * Broadcast receiver to handle stationaryMonitor alarm, fired at low frequency while monitoring stationary-region.
     * This is required because latest Android proximity-alerts don't seem to operate while suspended.  Regularly polling
     * the location seems to trigger the proximity-alerts while suspended.
     */
    private BroadcastReceiver stationaryLocationMonitorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            logger.info("Stationary location monitor fired");
            playDebugTone(Tone.DIALTONE);
            try {
                LocationRequest locationRequest = new LocationRequest.Builder(0)
                    .setMinUpdateDistanceMeters(0)
                    .setMinUpdateIntervalMillis(0)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build();
                mFusedLocationClient.requestLocationUpdates(locationRequest, singleUpdatePI);
            } catch (SecurityException e) {
                logger.error("Security exception: {}", e.getMessage());
            }
        }
    };

    /**
     * Broadcast receiver which detects a user has exit his circular stationary-region determined by the greater of stationaryLocation.getAccuracy() OR stationaryRadius
     */
    private BroadcastReceiver stationaryRegionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = LocationManager.KEY_PROXIMITY_ENTERING;
            Boolean entering = intent.getBooleanExtra(key, false);

            if (entering) {
                logger.debug("Entering stationary region");
                if (isMoving) {
                    setPace(false);
                }
            }
            else {
                logger.debug("Exiting stationary region");
                // There MUST be a valid, recent location if this event-handler was called.
                Location location = getLastBestLocation();
                if (location != null) {
                    onExitStationaryRegion(location);
                }
            }
        }
    };

    public void onProviderDisabled(String provider) {
        // TODO Auto-generated method stub
        logger.debug("Provider {} was disabled", provider);
    }

    public void onProviderEnabled(String provider) {
        // TODO Auto-generated method stub
        logger.debug("Provider {} was enabled", provider);
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
        // TODO Auto-generated method stub
        logger.debug("Provider {} status changed: {}", provider, status);
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying DistanceFilterLocationProvider");

        this.onStop();
        alarmManager.cancel(stationaryAlarmPI);
        alarmManager.cancel(stationaryLocationPollingPI);

        unregisterReceiver(stationaryAlarmReceiver);
        unregisterReceiver(singleUpdateReceiver);
        unregisterReceiver(stationaryRegionReceiver);
        unregisterReceiver(stationaryLocationMonitorReceiver);

        super.onDestroy();
    }
}
