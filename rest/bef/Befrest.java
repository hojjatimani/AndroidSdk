/******************************************************************************
 * Copyright 2015-2016 Befrest
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
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
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Main class to interact with Befrest service.
 */
public final class Befrest {

    private static volatile Befrest instance;

    private Befrest(Context context) {
        try {
            this.context = context.getApplicationContext();
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
            uId = prefs.getLong(PREF_U_ID, -1);
            chId = prefs.getString(PREF_CH_ID, null);
            auth = prefs.getString(PREF_AUTH, null);
            topics = prefs.getString(PREF_TOPICS, "");

            logLevel = prefs.getInt(PREF_LOG_LEVEL, LOG_LEVEL_DEFAULT);
            checkInSleep = prefs.getBoolean(PREF_CHECK_IN_DEEP_SLEEP, false);
            isBefrestStarted = prefs.getBoolean(PREF_IS_SERVICE_STARTED, false);
            loadPushServiceData(prefs);
            //for test
            loadTestAnomalyData(prefs);
        } catch (Exception e) {
            //TODo report
            throw e;
        }
    }

    private void loadPushServiceData(SharedPreferences prefs) {
        String customPushServicName = prefs.getString(PREF_CUSTOM_PUSH_SERVICE_NAME, null);
        if (customPushServicName == null) {
            pushService = PushService.class;
        } else
            try {
                pushService = Class.forName(customPushServicName);
            } catch (ClassNotFoundException e) {
                BefLog.e(TAG, e);
            }
    }

    private void loadTestAnomalyData(SharedPreferences prefs) {
        reportedContinuousCloses = prefs.getInt(PREF_CONTINUOUS_CLOSES, 0);
        continuousClosesTypes = prefs.getString(PREF_CONTINUOUS_CLOSES_TYPES, "");

        sentBroadcasts = new ArrayList<>();
        String bai = prefs.getString(PREF_BROADCAST_ANOMALY_INFO, "");
        if (bai.length() > 0) sentBroadcasts.addAll(Arrays.asList(bai.split("_")));
        shouldNotReportBroadcastAnomaly = prefs.getBoolean(PREF_SHOULD_NOT_REPORT_BROADCAST_ANOMALY, false);
    }

    public static Befrest getInstance(Context context) {
        if (instance != null) return instance;
        synchronized (Befrest.class) {
            if (instance == null) {
                instance = new Befrest(context);
            }
        }
        return instance;
    }

    Context context;
    Class<?> pushService;

    long uId;
    String chId;
    String auth;
    int logLevel;
    boolean checkInSleep;
    boolean isBefrestStarted;
    String topics;
    boolean connectionDataChangedSinceLastStart;

    boolean refreshIsRequested = false;
    long lastAcceptedRefreshRequestTime = 0;

    private static final int[] AuthProblemBroadcastDelay = {0, 60 * 1000, 240 * 1000, 600 * 1000};
    int prevAuthProblems = 0;

    private int reportedContinuousCloses;
    private String continuousClosesTypes;

    private String subscribeUrl;
    private List<NameValuePair> subscribeHeaders;
    private NameValuePair authHeader;

    /**
     * Every Detail Will Be Printed In Logcat.
     */
    public static final int LOG_LEVEL_VERBOSE = 2;
    /**
     * Data Needed For Debug Will Be Printed.
     */
    public static final int LOG_LEVEL_DEBUG = 3;
    /**
     * Standard Level. You Will Be Aware Of Befrest's Main State
     */
    public static final int LOG_LEVEL_INFO = 4;
    /**
     * Only Warning And Errors.
     */
    public static final int LOG_LEVEL_WARN = 5;
    /**
     * Only Errors.
     */
    public static final int LOG_LEVEL_ERROR = 6;
    /**
     * None Of Befrest Logs Will Be Shown.
     */
    public static final int LOG_LEVEL_NO_LOG = 100;

    private static final int LOG_LEVEL_DEFAULT = LOG_LEVEL_INFO;

    private static String TAG = BefLog.TAG_PREF + "Befrest";

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
    private static final String PREF_LOG_LEVEL = "PREF_LOG_LEVEL";
    public static final String PREF_CUSTOM_PUSH_SERVICE_NAME = "PREF_CUSTOM_PUSH_SERVICE_NAME";
    public static final String PREF_CONTINUOUS_CLOSES = "PREF_CONTINUOUS_CLOSES";
    public static final String PREF_CONTINUOUS_CLOSES_TYPES = "PREF_CONTINUOUS_CLOSES_TYPES";
    public static final String PREF_BROADCAST_ANOMALY_INFO = "PREF_BROADCAST_ANOMALY_INFO";
    private static final String PREF_SHOULD_NOT_REPORT_BROADCAST_ANOMALY = "PREF_SHOULD_NOT_REPORT_BROADCAST_ANOMALY";

    /**
     * Initialize push receive service. You can also use setter messages for initializing.
     *
     * @param uId  uId
     * @param auth Authentication token
     * @param chId chId
     */
    public Befrest init(long uId, String auth, String chId) {
        if (chId == null || !(chId.length() > 0))
            throw new IllegalArgumentException("invalid chId!");
        if (uId != this.uId || (auth != null && !auth.equals(this.auth)) || !chId.equals(this.chId)) {
            this.uId = uId;
            this.auth = auth;
            this.chId = chId;
            clearTempData();
            saveToPrefs(context, uId, auth, chId);
        }
        return this;
    }

    /**
     * @param customPushService that befrest will start in background. This class must extend rest.bef.PushService
     * @return
     */
    public Befrest advancedSetCustomPushService(Class<? extends PushService> customPushService) {
        if (customPushService == null)
            throw new IllegalArgumentException("invalid custom push service!");
        else if (isBefrestStarted && !customPushService.equals(pushService)) {
            throw new IllegalArgumentException("can not set custom push service after starting befrest!");
        } else {
            this.pushService = customPushService;
            SharedPreferences.Editor editor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
            editor.putString(PREF_CUSTOM_PUSH_SERVICE_NAME, customPushService.getName());
            editor.commit();
        }
        return this;
    }

    /**
     * set uId.
     *
     * @param uId uId
     */
    public Befrest setUId(long uId) {
        if (uId != this.uId) {
            this.uId = uId;
            clearTempData();
            SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
            prefEditor.putLong(PREF_U_ID, uId);
            prefEditor.commit();
        }
        return this;
    }

    /**
     * set chId.
     *
     * @param chId chId
     */
    public Befrest setChId(String chId) {
        if (chId == null || !(chId.length() > 0))
            throw new IllegalArgumentException("invalid chId!");
        if (!chId.equals(this.chId)) {
            this.chId = chId;
            clearTempData();
            SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
            prefEditor.putString(PREF_CH_ID, chId);
            prefEditor.commit();
        }
        return this;
    }

    /**
     * set Authentication token.
     *
     * @param auth Authentication Token
     */
    public Befrest setAuth(String auth) {
        if (auth == null || !(auth.length() > 0))
            throw new IllegalArgumentException("invalid auth!");
        if (!auth.equals(this.auth)) {
            this.auth = auth;
            clearTempData();
            SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
            prefEditor.putString(PREF_AUTH, auth);
            prefEditor.commit();
        }
        return this;
    }

    /**
     * Start push service. You should set uId and chId before calling this start.
     * Best Practice: call this method in your onCreate() method of your Application class
     * Yous should also call this method anytime you set authToken.
     *
     * @throws IllegalStateException if be called without a prior call to init()
     */
    public void start() {
        BefLog.i(TAG, "starting befrest");
        if (uId < 0 || chId == null || chId.length() < 1)
            throw new IllegalStateException("uId and chId are not properly defined!");
        setBefrestStarted(true);
        if (connectionDataChangedSinceLastStart)
            context.stopService(new Intent(context, pushService)); // stop service if is running with old credentials
        context.startService(new Intent(context, pushService).putExtra(PushService.CONNECT, true));
        connectionDataChangedSinceLastStart = false;
        Util.enableConnectivityChangeListener(context);
        if (checkInSleep) scheduleWakeUP();
    }

    /**
     * Stop push service.
     * You can call start to run the service later.
     */
    public void stop() {
        setBefrestStarted(false);
        context.stopService(new Intent(context, pushService));
        cancelWakeUP();
        Util.disableConnectivityChangeListener(context);
        BefLog.i(TAG, "Befrest Service Stopped.");
    }

    public Befrest addTopic(String topicName) {
        if (topicName == null || topicName.length() < 1 || !topicName.matches("[A-Za-z0-9]+"))
            throw new IllegalArgumentException("topic name should be an alpha-numeric string!");
        for (String s : topics.split("-"))
            if (s.equals(topicName))
                return this;
        if (topics.length() > 0)
            topics += "-";
        topics += topicName;
        updateTpics(topics);
        BefLog.i(TAG, "Topics: " + topics);
        return this;
    }

    /**
     * remove a topic from current topics that user has.
     *
     * @param topicName Name of topic to be added
     */
    public Befrest removeTopic(String topicName) {
        String[] splitedTopics = topics.split("-");
        boolean found = false;
        String resTopics = "";
        for (int i = 0; i < splitedTopics.length; i++) {
            if (splitedTopics[i].equals(topicName))
                found = true;
            else resTopics += splitedTopics[i] + "-";
        }
        if (!found)
            throw new IllegalArgumentException("Topic Not Exist!");
        if (resTopics.length() > 0) resTopics = resTopics.substring(0, resTopics.length() - 1);
        updateTpics(resTopics);
        BefLog.i(TAG, "Topics: " + topics);
        return this;
    }

    /**
     * get List of currently added topics
     *
     * @return Topics List
     */
    public String[] getCurrentTopics() {
        if (topics.length() > 0)
            return topics.split("-");
        return new String[0];
    }

    /**
     * Minimizes push delay when device is in deep sleep.
     * You <i><b>SHOULD NOT</b></i> enable this feature unless you really need it as it might cause battery drain.
     */
    public Befrest enableCheckInSleep() {
        checkInSleep = true;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, true).commit();
        if (isBefrestStarted) scheduleWakeUP();
        return this;
    }

    public Befrest disableCheckInSleep() {
        checkInSleep = false;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, false).commit();
        cancelWakeUP();
        return this;
    }

    /**
     * Request the push service to refresh its connection. You will be notified through your receivers whenever
     * the connection refreshed.
     *
     * @return true if a request was accepted, false otherwise.
     */
    public boolean refresh() {
        if (!Util.isConnectedToInternet(context) || !isBefrestStarted)
            return false;
        BefLog.i(TAG, "Befrest Is Refreshing ...");
        if (refreshIsRequested && (System.currentTimeMillis() - lastAcceptedRefreshRequestTime) < 10 * 1000)
            return true;
        refreshIsRequested = true;
        lastAcceptedRefreshRequestTime = System.currentTimeMillis();
        context.startService(new Intent(context, pushService).putExtra(PushService.REFRESH, true));
        return true;
    }

    /**
     * Register a new push receiver. Any registered receiver <i><b>must be</b></i> unregistered by passing the same receiver object
     * to {@link #unregisterPushReceiver}. Actually the method registers a BroadcastReceiver considering security using permissions.
     *
     * @param receiver the receiver object that will receive events
     */
    public void registerPushReceiver(BefrestPushReceiver receiver) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BefrestPushReceiver.ACTION_BEFREST_PUSH);
        context.registerReceiver(receiver, intentFilter, Util.getBroadcastSendingPermission(context), null);
    }

    /**
     * Unregister a previously registered push receiver.
     *
     * @param receiver receiver object to be unregistered
     */
    public void unregisterPushReceiver(BefrestPushReceiver receiver) {
        context.unregisterReceiver(receiver);
    }

    public Befrest setLogLevel(int logLevel) {
        if (logLevel < 0) BefLog.i(TAG, "Invalid Log Level!");
        else {
            context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putInt(PREF_LOG_LEVEL, logLevel).commit();
            this.logLevel = logLevel;
        }
        return this;
    }

    public static int getLogLevel() {
        if (instance != null)
            return instance.logLevel;
        return LOG_LEVEL_DEFAULT;
    }


    public static int getSdkVersion() {
        return Util.SDK_VERSION;
    }


    private void updateTpics(String topics) {
        this.topics = topics;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putString(PREF_TOPICS, topics).commit();
        clearTempData();
    }

    private void setBefrestStarted(boolean b) {
        isBefrestStarted = b;
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_IS_SERVICE_STARTED, isBefrestStarted).commit();
    }

    private static void saveToPrefs(Context context, long uId, String AUTH, String chId) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putLong(PREF_U_ID, uId);
        prefEditor.putString(PREF_AUTH, AUTH);
        prefEditor.putString(PREF_CH_ID, chId);
        prefEditor.commit();
    }

    private void scheduleWakeUP() {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, WakeupAlarmReceiver.class);
        intent.setAction(WakeupAlarmReceiver.ACTION_WAKEUP);
        PendingIntent broadcast = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        //by using InexatRepeating only pre defined intervals can be used
        alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, broadcast);
        BefLog.d(TAG, "Befrest Scheduled To Wake Device Up.");
    }

    void setStartServiceAlarm() {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, pushService).putExtra(PushService.SERVICE_STOPPED, true);
        PendingIntent pi = PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PushService.START_SERVICE_AFTER_ILLEGAL_STOP_DELAY, pi);
        BefLog.d(TAG, "Befrest Scheduled To Start Service In " + PushService.START_SERVICE_AFTER_ILLEGAL_STOP_DELAY + "ms");
    }

    void cancelStartServiceAlarm() {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, pushService).putExtra(PushService.SERVICE_STOPPED, true);
        PendingIntent pi = PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmMgr.cancel(pi);
        pi.cancel();
        BefLog.d(TAG, "Starting Service Canceled");
    }

    private void cancelWakeUP() {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, WakeupAlarmReceiver.class);
        intent.setAction(WakeupAlarmReceiver.ACTION_WAKEUP);
        PendingIntent broadcast = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmMgr.cancel(broadcast);
        broadcast.cancel();
        BefLog.d(TAG, "Befrest Wakeup Canceled");
    }

    private void clearTempData() {
        subscribeUrl = null;
        subscribeHeaders = null;
        authHeader = null;
        connectionDataChangedSinceLastStart = true;
    }

    String getSubscribeUri() {
        if (subscribeUrl == null)
            subscribeUrl = String.format(Locale.US, "wss://gw.bef.rest/xapi/%d/subscribe/%d/%s/%d", Util.API_VERSION, uId, chId, Util.SDK_VERSION);
        return subscribeUrl;
    }

    List<NameValuePair> getSubscribeHeaders() {
        if (subscribeHeaders == null) {
            subscribeHeaders = new ArrayList<>();
            subscribeHeaders.add(getAuthHeader());
            if (topics != null && topics.length() > 0)
                subscribeHeaders.add(new NameValuePair("X-BF-TOPICS", topics));
        }
        return subscribeHeaders;
    }

    NameValuePair getAuthHeader() {
        if (authHeader == null) {
            authHeader = new NameValuePair("X-BF-AUTH", auth);
        }
        return authHeader;
    }

    int getSendOnAuthorizeBroadcastDelay() {
        return AuthProblemBroadcastDelay[prevAuthProblems < AuthProblemBroadcastDelay.length ? prevAuthProblems : AuthProblemBroadcastDelay.length - 1];
    }

    public void reportOnClose(Context context, int code) {
        BefLog.d(TAG, "reportOnClose :: total:" + reportedContinuousCloses + " code:" + code);
        reportedContinuousCloses++;
        continuousClosesTypes += code + ",";
        SharedPreferences.Editor editor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_CONTINUOUS_CLOSES_TYPES, continuousClosesTypes);
        editor.putInt(PREF_CONTINUOUS_CLOSES, reportedContinuousCloses);
        editor.commit();
        //TODO 3 is for test!
        if (reportedContinuousCloses == 3 || reportedContinuousCloses == 10) {
            Bundle b = new Bundle(1);
            b.putString(Util.KEY_MESSAGE_PASSED, continuousClosesTypes);
            sendBefrestBroadcast(context, BefrestPushReceiver.Anomaly, b);
        } else if (reportedContinuousCloses == 50) {
            clearAnomalyHistory();
        }
    }

    void sendBefrestBroadcast(Context context, int type, Bundle extras) {
        Intent intent = new Intent(BefrestPushReceiver.ACTION_BEFREST_PUSH).putExtra(BefrestPushReceiver.BROADCAST_TYPE, type);
        if (extras != null) intent.putExtras(extras);
        String permission = Befrest.Util.getBroadcastSendingPermission(context);
        long now = System.currentTimeMillis();
        intent.putExtra(BefrestPushReceiver.KEY_TIME_SENT, "" + now);
        context.getApplicationContext().sendBroadcast(intent, permission);
        reportBroadcastSent(now + ":" + type);
        BefLog.v(TAG, "broadcast sent::    type: " + type + "      permission:" + permission);
    }

    void reportOnOpen() {
        BefLog.v(TAG, "reportOnOpen");
        clearAnomalyHistory();
    }

    private void clearAnomalyHistory() {
        reportedContinuousCloses = 0;
        continuousClosesTypes = "";
        SharedPreferences.Editor editor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_CONTINUOUS_CLOSES_TYPES, continuousClosesTypes);
        editor.putInt(PREF_CONTINUOUS_CLOSES, 0);
        editor.commit();
    }

    boolean shouldNotReportBroadcastAnomaly;
    ArrayList<String> sentBroadcasts;

    void reportBroadcastReceived(Context context, String id) {
        if (shouldNotReportBroadcastAnomaly) return;
        sentBroadcasts.remove(id);
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putString(PREF_BROADCAST_ANOMALY_INFO, getBroadcastAnomalyInfo()).commit();
    }

    void reportBroadcastSent(String id) {
        if (shouldNotReportBroadcastAnomaly) return;
        sentBroadcasts.add(id);
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putString(PREF_BROADCAST_ANOMALY_INFO, getBroadcastAnomalyInfo()).commit();
    }

    public boolean hasBroadcastAnomalyHappened() {
        if (sentBroadcasts.size() > 10) {
            stopTrackingBroadcasts();
            return true;
        }
        return false;
    }

    private void stopTrackingBroadcasts() {
        shouldNotReportBroadcastAnomaly = true;
        SharedPreferences.Editor editor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(PREF_SHOULD_NOT_REPORT_BROADCAST_ANOMALY, true);
        editor.commit();
    }

    public String getBroadcastAnomalyInfo() {
        String res = "";
        for (String id : sentBroadcasts) {
            res += id + "_";
        }
        if (res.length() > 0) res = res.substring(0, res.length() - 1);
        BefLog.v(TAG, "BroadcastAnomalyInfo: " + res);
        return res;
    }

    static class Util {
        protected static final String KEY_MESSAGE_PASSED = "KEY_MESSAGE_PASSED";
        private static final String BROADCAST_SENDING_PERMISSION_POSTFIX = ".permission.PUSH_SERVICE";
        private static final int API_VERSION = 1;
        private static final int SDK_VERSION = 1;
        static long lastScreenOnTime;

        private static WifiManager.WifiLock wifiLock;
        private static PowerManager.WakeLock wakeLock = null;

        /**
         * Acquire a wakelock
         */
        static void acquireWakeLock(Context context) {
            Context appContext = context.getApplicationContext();
            BefLog.d(TAG, "Befrest Acquired A WakeLock");
            if (wakeLock == null) {
                BefLog.v(TAG, "init wake lock");
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
            BefLog.v(TAG, "releaseWakeLock()");
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                    wakeLock = null;
                    BefLog.d(TAG, "Befrest WakeLock Released.");
                } catch (Exception e) {
                    BefLog.e(TAG, e);
                }
            }
        }

        /**
         * Release the wifilock acquired before
         */
        static void releaseWifiLock() {
            BefLog.v(TAG, "releaseWifiLock()");
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                BefLog.d(TAG, "Befrest WifiLock Released");
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
            BefLog.d(TAG, "Befrest Acquired A WifiLock");
        }

        /**
         * Is device connected to Internet?
         */
        static boolean isConnectedToInternet(Context context) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo netInfo = cm.getActiveNetworkInfo();
                if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                    return true;
                }

                netInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                } else {
                    netInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                    if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                        return true;
                    }
                }
            } catch (Exception e) {
                BefLog.e(TAG, e);
                return true;
            }
            return false;
        }

        static boolean isWifiConnectedOrConnecting(Context context) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            boolean res = wifiInfo.isConnectedOrConnecting();
            BefLog.v(TAG, "isWifiConnectedOrConnecting() returned: " + res);
            return res;
        }

        static boolean isWifiEnabled(Context context) {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            boolean res;
            BefLog.v(TAG, "isWifiEnabled: " + (res = wifiManager.isWifiEnabled()));
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
                BefLog.e(TAG, e);
            }
            wifiManager.disconnect();
            wifiManager.startScan();
            wifiManager.reassociate();
            wifiManager.reconnect();
            BefLog.v(TAG, "Befrest Asking Wifi To Connect ...");
        }

        static boolean isUserInteractive(Context context) {
            if (isScreenOn(context) || System.currentTimeMillis() - lastScreenOnTime < 30 * 60 * 1000)
                return true;
            return false;
        }

        static String getBroadcastSendingPermission(Context context) {
            return context.getApplicationContext().getPackageName() + BROADCAST_SENDING_PERMISSION_POSTFIX;
        }

        static void disableConnectivityChangeListener(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            BefLog.v(TAG, "Befrest Connectivity change listener disabled");
        }

        static void enableConnectivityChangeListener(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            BefLog.v(TAG, "Befrest Connectivity change listener enabled");
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
