/******************************************************************************
 * Copyright 2015-2016 Befrest
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package rest.bef;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Display;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rest.bef.connectivity.NameValuePair;

/**
 * Main class to interact with Befrest service.
 */
public final class Befrest {
    private static String TAG = "Befrest";

    /**
     * Name for sharedPreferences used for saving Befrest data.
     */
    private static String SHARED_PREFERENCES_NAME = "rest.bef.SHARED_PREFERENCES";
    private static final String PREF_U_ID = "PREF_U_ID";
    private static final String PREF_AUTH = "PREF_AUTH";
    private static final String PREF_CH_ID = "PREF_CH_ID";
    private static final String PREF_CHECK_IN_DEEP_SLEEP = "PREF_CHECK_IN_DEEP_SLEEP";
    private static final String PREF_TOPICS = "PREF_TOPICS";
    private static final String PREF_IS_SERVICE_STARTED = "PREF_IS_SERVICE_STARTED";

    static final String ACTION_BEFREST_PUSH = "rest.bef.broadcasts.ACTION_BEFREST_PUSH";
    static final String BROADCAST_TYPE = "BROADCAST_TYPE";


    static boolean LegalStop = false;

    class BroadcastType {
        static final int PUSH = 0;
        static final int UNAUTHORIZED = 1;
        static final int CONNECTION_REFRESHED = 2;
    }

    protected static final String ACTION_WAKEUP = "rest.bef.broadcasts.WAKEUP";

    private static boolean checkInSleep = false;

    static boolean refreshIsRequested = false;
    static long lastAcceptedRefreshRequestTime = 0;


    /**
     * Initialize push receive service. You can also use setter messages for initializing.
     *
     * @param uId  uId
     * @param auth Authentication token
     * @param chId chId
     */
    public static void init(Context context, long uId, String auth, String chId) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        if (chId == null || !(chId.length() > 0))
            throw new IllegalArgumentException("invalid chId!");
        System.setProperty("http.keepAlive", "false"); //prevent CONNECTION RESET BY PEER Exception in sending http request.This is needed for some devices
        saveCredentials(context, uId, auth, chId);
    }

    /**
     * set uId.
     *
     * @param context Context
     * @param uId     uId
     */
    public static void setUId(Context context, long uId) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putLong(PREF_U_ID, uId);
        prefEditor.commit();
    }

    /**
     * set chId.
     *
     * @param context Context
     * @param chId    chId
     */
    public static void setChId(Context context, String chId) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        if (chId == null || !(chId.length() > 0))
            throw new IllegalArgumentException("invalid chId!");
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putString(PREF_CH_ID, chId);
        prefEditor.commit();
    }

    /**
     * set Authentication token.
     *
     * @param context Context
     * @param auth    Authentication Token
     */
    public static void setAuth(Context context, String auth) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        if (auth == null || !(auth.length() > 0))
            throw new IllegalArgumentException("invalid auth!");
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putString(PREF_AUTH, auth);
        prefEditor.commit();
    }

    /**
     * Start push service. You should set uId and chId before calling this start.
     * Best Practice: call this method in your onCreate() method of your Application class
     * Yous should also call this method anytime you set authToken.
     *
     * @throws IllegalStateException if be called without a prior call to init()
     */
    public static void start(Context context) {
        if (!isServiceInitialized(context))
            throw new IllegalStateException("uId and chId are not defined!!!");
        LegalStop = true;
        context.stopService(new Intent(context, PushService.class)); // stop service if is running with old credentials
        LegalStop = false;
        Util.clearTempCredentials(); //clear temp if data to be reCalculated
        context.startService(new Intent(context, PushService.class).putExtra(PushService.CONNECT, true));
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_IS_SERVICE_STARTED, true).commit();
        Util.enableConnectivityChangeListenerIfNeeded(context);
        if (isCheckInSleepEnabled(context)) scheduleWakeUP(context);
    }

    /**
     * Stop push service.
     * You can call start to run the service later.
     */
    public static void stop(Context context) {
        LegalStop = true;
        context.stopService(new Intent(context, PushService.class));
        LegalStop = false;
        cancelWakeUP(context);
        Util.disableConnectivityChangeListener(context);
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_IS_SERVICE_STARTED, false).commit();
    }

    public static void addTopic(Context context, String topicName) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        if (topicName == null || topicName.length() < 1 || !topicName.matches("[A-Za-z0-9]+"))
            throw new IllegalArgumentException("topic name should be an alpha-numeric string!");
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String topics = prefs.getString(PREF_TOPICS, "");
        for (String s : topics.split("-"))
            if (s.equals(topicName))
                return;
        if (topics.length() > 0)
            topics += "-";
        topics += topicName;
        prefs.edit().putString(PREF_TOPICS, topics).commit();
        FileLog.d(TAG, "topics: " + topics);
    }

    /**
     * remove a topic from current topics that user has.
     *
     * @param context   Context
     * @param topicCode Name of topic to be added
     */
    public static void removeTopic(Context context, String topicCode) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String t = prefs.getString(PREF_TOPICS, "");
        String[] topics = t.split("-");
        boolean found = false;
        t = "";
        for (int i = 0; i < topics.length; i++) {
            if (topics[i].equals(topicCode))
                found = true;
            else t += topics[i] + "-";
        }
        if (!found)
            throw new IllegalArgumentException("Topic Not Exist!");
        if (t.length() > 0) t = t.substring(0, t.length() - 1);
        if (t.length() == 0) t = null;
        prefs.edit().putString(PREF_TOPICS, t).commit();
    }

    /**
     * get List of currently added topics
     *
     * @param context Context
     * @return Topics List
     */
    public static String[] getCurrentTopics(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String topics = prefs.getString(PREF_TOPICS, null);
        if (topics != null && topics.length() > 0)
            return topics.split("-");
        return new String[0];
    }

    /**
     * Minimizes push delay when device is in deep sleep.
     * You <i><b>SHOULD NOT</b></i> enable this feature unless you really need it as it might cause battery drain.
     *
     * @param context Context
     */
    public static void enableCheckInSleep(Context context) {
        Befrest.checkInSleep = true;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, true).commit();
    }

    public static void disableCheckInSleep(Context context) {
        Befrest.checkInSleep = false;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, false).commit();
        cancelWakeUP(context);
    }

    /**
     * Request the push service to refresh its connection. You will be notified through your receivers whenever
     * the connection refreshed.
     *
     * @param context Context
     * @return true if a request was accepted, false otherwise.
     */
    public static boolean refresh(Context context) {
        if (!Util.isConnectedToInternet(context) || !isServiceStarted(context))
            return false;
        Util.enableConnectivityChangeListenerIfNeeded(context);
        if (refreshIsRequested && (System.currentTimeMillis() - lastAcceptedRefreshRequestTime) < 10 * 1000)
            return true;
        refreshIsRequested = true;
        lastAcceptedRefreshRequestTime = System.currentTimeMillis();
        context.startService(new Intent(context, PushService.class).putExtra(PushService.REFRESH, true));
        return true;
    }

    /**
     * Register a new push receiver. Any registered receiver <i><b>must be</b></i> unregistered by passing the same receiver object
     * to {@link #unregisterPushReceiver}. Actually the method registers a BroadcastReceiver considering security using permissions.
     *
     * @param context  Context
     * @param receiver the receiver object that will receive events
     */
    public static void registerPushReceiver(Context context, BefrestPushReceiver receiver) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Befrest.ACTION_BEFREST_PUSH);
        context.registerReceiver(receiver, intentFilter, Util.getBroadcastSendingPermission(context), null);
    }

    /**
     * Unregister a previously registered push receiver.
     *
     * @param context  Context
     * @param receiver receiver object to be unregistered
     */
    public static void unregisterPushReceiver(Context context, BefrestPushReceiver receiver) {
        context.unregisterReceiver(receiver);
    }


    public static int getSdkVersion() {
        return Util.SDK_VERSION;
    }

    private static void saveCredentials(Context context, long uId, String AUTH, String chId) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putLong(PREF_U_ID, uId);
        prefEditor.putString(PREF_AUTH, AUTH);
        prefEditor.putString(PREF_CH_ID, chId);
        prefEditor.commit();
    }

    private static boolean isServiceInitialized(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.contains(PREF_U_ID) && preferences.contains(PREF_CH_ID))
            return (true);
        return false;
    }

    private static boolean isServiceStarted(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        return preferences.getBoolean(PREF_IS_SERVICE_STARTED, false);
    }

    private static boolean isCheckInSleepEnabled(Context context) {
        if (checkInSleep)
            return true;
        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PREF_CHECK_IN_DEEP_SLEEP, false))
            return (checkInSleep = true);
        return false;
    }

    private static void scheduleWakeUP(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmMgr = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(appContext, WakeupAlarmReceiver.class);
        intent.setAction(ACTION_WAKEUP);
        PendingIntent broadcast = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        //by using InexatRepeating only pre defined intervals can be used
        alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, broadcast);
        FileLog.d(TAG, "Scheduled For Wake Up");
    }

    private static void cancelWakeUP(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmMgr = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(appContext, WakeupAlarmReceiver.class);
        intent.setAction(ACTION_WAKEUP);
        PendingIntent broadcast = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmMgr.cancel(broadcast);
        broadcast.cancel();
        FileLog.d(TAG, "Wakeup Canceled");
    }

    static class Util {
        protected static final String KEY_MESSAGE_PASSED = "KEY_MESSAGE_PASSED";
        private static final String BROADCAST_SENDING_PERMISSION_POSTFIX = ".permission.PUSH_SERVICE";
        private static final int API_VERSION = 1;
        private static final int SDK_VERSION = 1;
        static long lastScreenOnTime;

        private static WifiManager.WifiLock wifiLock;
        private static PowerManager.WakeLock wakeLock = null;

        private static String pingUrl;
        private static String subscribeUrl;
        private static List<NameValuePair> subscribeHeaders;
        private static NameValuePair authHeader;

        static void clearTempCredentials() {
            pingUrl = null;
            subscribeUrl = null;
            subscribeHeaders = null;
            authHeader = null;
        }

        /**
         * Acquire a wakelock
         */
        static void acquireWakeLock(Context context) {
            Context appContext = context.getApplicationContext();
            FileLog.d(TAG, "acquireWakeLock()");
            if (wakeLock == null) {
                FileLog.d(TAG, "init wake lock");
                PowerManager mgr =
                        (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ".WAKELOCK");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire();
        }

        /**
         * Release the wakelock acquired before
         */
        protected static void releaseWakeLock() {
            FileLog.d(TAG, "releaseWakeLock()");
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                    wakeLock = null;
                    FileLog.d(TAG, "WakeLockReleased");
                } catch (Exception e) {
                    FileLog.e(TAG, e);
                }
            }
        }

        /**
         * Release the wifilock acquired before
         */
        static void releaseWifiLock() {
            Log.d(TAG, "releaseWifiLock()");
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                FileLog.d(TAG, "wifiLock released");
            }
            wifiLock = null;
        }

        /**
         * Acquire a wifilock
         */
        static void acquireWifiLock(Context context) {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifiManager.createWifiLock(/* WifiManager.WIFI_MODE_FULL_HIGH_PERF */0x3, TAG + ".WIFI_LOCK");
            wifiLock.acquire();
            FileLog.d(TAG, "wifiLock acquired");
        }

        /**
         * Is device connected to Internet?
         */
        static boolean isConnectedToInternet(Context context) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }

        static boolean isWifiConnectedOrConnecting(Context context) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            boolean res = wifiInfo.isConnectedOrConnecting();
            Log.d(TAG, "isWifiConnectedOrConnecting() returned: " + res);
            return res;
        }

        static boolean isWifiEnabled(Context context) {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            boolean res;
            FileLog.d(TAG, "isWifiEnabled: " + (res = wifiManager.isWifiEnabled()));
            return res;
        }

        static void askWifiToConnect(Context context) {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            wifiManager.pingSupplicant(); // why is it here!?!?! before restarting wifi!

            try {
                // Brute force methods required for some devices
                wifiManager.setWifiEnabled(false);
                wifiManager.setWifiEnabled(true);
            } catch (Exception e) {
                // Catching exception which should not occur on most
                // devices. OS bug details at :
                // https://code.google.com/p/android/issues/detail?id=22036
                FileLog.e(TAG, e);
            }
            wifiManager.disconnect();
            wifiManager.startScan();
            wifiManager.reassociate();
            wifiManager.reconnect();
            FileLog.d(TAG, "connect wifi ...");
        }

        static boolean isUserInteractive(Context context) {
            if (isScreenOn(context) || System.currentTimeMillis() - lastScreenOnTime < 30 * 60 * 1000)
                return true;
            return false;
        }

        static String getSubscribeUri(Context context) {
            if (subscribeUrl == null) {
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
                long uId = prefs.getLong(PREF_U_ID, 0);
                String chId = prefs.getString(PREF_CH_ID, "");
                subscribeUrl = String.format(Locale.US, "ws://gw.bef.rest:8000/xapi/%d/subscribe/%d/%s/%d", API_VERSION, uId, chId, SDK_VERSION);
            }
            Log.d(TAG, "getSubscribeUri: " + subscribeUrl);
            return subscribeUrl;
        }

        static List<NameValuePair> getSubscribeHeaders(Context context) {
            if (subscribeHeaders == null) {
                subscribeHeaders = new ArrayList<>();
                subscribeHeaders.add(getAuthHeader(context));
                String topics = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(PREF_TOPICS, null);
                if (topics != null && topics.length() > 0)
                    subscribeHeaders.add(new NameValuePair("X-BF-TOPICS", topics));
            }
            return subscribeHeaders;
        }

        static NameValuePair getAuthHeader(Context context) {
            if (authHeader == null) {
                String authToken = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(PREF_AUTH, "authToken");
                authHeader = new NameValuePair("X-BF-AUTH", authToken);
            }
            return authHeader;
        }

        static String getPingUrl(Context context) {
            if (pingUrl == null) {
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
                long uId = prefs.getLong(PREF_U_ID, 0);
                String chId = prefs.getString(PREF_CH_ID, "");
                pingUrl = String.format(Locale.US, "https://gw.bef.rest:8443/xapi/%d/ping/%d/%s/%d", API_VERSION, uId, chId, SDK_VERSION);
            }
            return pingUrl;
        }

        static String getBroadcastSendingPermission(Context context) {
            return context.getApplicationContext().getPackageName() + BROADCAST_SENDING_PERMISSION_POSTFIX;
        }

        static void enableConnectivityChangeListenerIfNeeded(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            boolean isConnectedToInternet = isConnectedToInternet(context);
            boolean isConnectivityChangeListenerDisabled = pm.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            boolean isServiceStarted = isServiceStarted(context);
            FileLog.m(TAG, "isConnectedToInternet, isConnectivityChangeListenerDisabled, isServiceStarted", isConnectedToInternet, isConnectivityChangeListenerDisabled, isServiceStarted);
            if (!isConnectedToInternet && isConnectivityChangeListenerDisabled && isServiceStarted)
                enableConnectivityChangeListener(context);
        }

        static void disableConnectivityChangeListener(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            FileLog.d(TAG, "Connectivity change listener disabled");
        }

        static void enableConnectivityChangeListener(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            FileLog.d(TAG, "Connectivity change listener enabled");
        }

        static String decodeBase64(String s) {
            try {
                return new String(Base64.decode(s, Base64.DEFAULT), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return "";
        }

        static boolean isScreenOn(Context context) {
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) { // API 20
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                for (Display display : dm.getDisplays())
                    if (display.getState() != Display.STATE_OFF) return true;
            }
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm.isScreenOn();
        }
    }
}