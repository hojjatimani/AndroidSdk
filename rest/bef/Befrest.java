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
import android.os.AsyncTask;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static String SHARED_PREFRENCES_NAME = "rest.bef.SHARED_PREFERENCES";
    private static final String PREF_U_ID = "PREF_U_ID";
    private static final String PREF_AUTH = "PREF_AUTH";
    private static final String PREF_CH_ID = "PREF_CH_ID";
    private static final String PREF_CHECK_IN_DEEP_SLEEP = "PREF_CHECK_IN_DEEP_SLEEP";

    //befrest broadcast actions
    public static final String ACTION_PUSH_RECIEVED = "rest.bef.broadcasts.PUSH_RECEIVED";
    public static final String ACTION_UNAUTHORIZED = "rest.bef.broadcasts.UNAUTHORIZED";
    public static final String ACTION_CONNECTION_REFRESHED = "rest.bef.broadcasts.CONNECTION_REFRESHED";
    protected static final String ACTION_WAKEUP = "rest.bef.broadcasts.WAKEUP";

    private static boolean serviceInitialized = false;
    private static boolean checkInSleep = false;

    /**
     * Initialize push receive service
     *
     * @param U_ID    U_ID
     * @param AUTH    Authentication token
     * @param CH_ID   CH_ID
     */
    public static void init(Context context, int U_ID, String AUTH, long CH_ID) {
        if (context == null)
            throw new IllegalArgumentException("context can't be null!");
        if (AUTH == null)
            throw new IllegalArgumentException("AUTH can't be null!");
        Util.enableConnectivityChangeListenerIfNeeded(context);
        System.setProperty("http.keepAlive", "false"); //prevent CONNECTION RESET BY PEER Exception in sending http request.This is needed for some devices
        saveCredentials(context, U_ID, AUTH, CH_ID);
        context.startService(new Intent(context, PushService.class).putExtra(PushService.CONNECT, true));
        serviceInitialized = true;
        if (isCheckInSleepEnabled(context)) scheduleWakeUP(context);
    }

    /**
     * Start push service. (This method cant be called only after a successful call to init())
     *
     * @throws IllegalStateException if be called without a prior call to init()
     */
    public static void start(Context context) {
        Util.enableConnectivityChangeListenerIfNeeded(context);
        if (!isServiceInitialized(context))
            throw new IllegalStateException("Can not start service before initialize! call Befrest.init first");
        context.startService(new Intent(context, PushService.class).putExtra(PushService.CONNECT, true));
        if (isCheckInSleepEnabled(context)) scheduleWakeUP(context);
    }

    /**
     * Stops push service. Yous should call Befrest.init() to initialize Befrest later.
     */
    public static void stop(Context context) {
        context.stopService(new Intent(context, PushService.class));
        clearCredentials(context);
    }

    /**
     * Minimizes push delay when device is in deep sleep.
     * <p>
     * You <i><b>SHOULD NOT</b></i> enable this feature unless you really need it as it might cause battery drain.
     *
     * @param context Context
     */
    public static void enableCheckInSleep(Context context) {
        Befrest.checkInSleep = true;
        context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, true).commit();
        if (isServiceInitialized(context))
            scheduleWakeUP(context);
    }

    public static void disableCheckInSleep(Context context) {
        Befrest.checkInSleep = false;
        context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_IN_DEEP_SLEEP, false).commit();
        cancelWakeUP(context);
    }

    /**
     * Request the push service to refresh its connection. You will be notified through your receivers whenever
     * the connection refreshed.
     *
     * @param context Context
     * @return true if a request was accepted, false otherwise.
     * @throws IllegalStateException if be called before initializing service
     */
    public static boolean requestRefresh(Context context) {
        Util.enableConnectivityChangeListenerIfNeeded(context);
        if (!isServiceInitialized(context))
            throw new IllegalStateException("Can not request for refresh before initialize! call Befrest.init() first.");
        if (!Util.isConnectedToInternet(context))
            return false;
        context.startService(new Intent(context, PushService.class).putExtra(PushService.REFRESH, true));
        return true;
    }

    /**
     * Registers a receiver for Befrest events. Any registered receiver <i><b>must be</b></i> unregistered by passing the same receiver object
     * to {@link #unregisterPushReceiver}. Actually the method registers a BroadcastReceiver considering security using permissions.
     *
     * @param context  Context
     * @param receiver the receiver object that will receive events
     */
    public static void registerPushReceiver(Context context, BefrestPushBroadcastReceiver receiver) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Befrest.ACTION_PUSH_RECIEVED);
        intentFilter.addAction(Befrest.ACTION_CONNECTION_REFRESHED);
        context.registerReceiver(receiver, intentFilter, Util.getBroadcastSendingPermission(context), null);
    }

    /**
     * Unregister a previous registered receiver.
     *
     * @param context  Context
     * @param receiver receiver object to be unregistered
     */
    public static void unregisterPushReceiver(Context context, BefrestPushBroadcastReceiver receiver) {
        context.unregisterReceiver(receiver);
    }


    private static void saveCredentials(Context context, int U_ID, String AUTH, long CH_ID) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putInt(PREF_U_ID, U_ID);
        prefEditor.putString(PREF_AUTH, AUTH);
        prefEditor.putLong(PREF_CH_ID, CH_ID);
        prefEditor.commit();
    }

    private static boolean isServiceInitialized(Context context) {
        if (serviceInitialized)
            return true;
        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
        if (!(preferences.contains(PREF_U_ID) && preferences.contains(PREF_CH_ID) && preferences.contains(PREF_AUTH)))
            return (serviceInitialized = true);
        return false;
    }

    private static boolean isCheckInSleepEnabled(Context context) {
        if (checkInSleep)
            return true;
        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PREF_CHECK_IN_DEEP_SLEEP, false))
            return (checkInSleep = true);
        return false;
    }

    static void clearCredentials(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().clear().commit();
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
        private static String publishUrl;
        private static List<NameValuePair> authHeader;

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
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
                int uId = prefs.getInt(PREF_U_ID, 0);
                long chId = prefs.getLong(PREF_CH_ID, 0);
                subscribeUrl = String.format(Locale.US, "ws://gw.bef.rest:8000/xapi/%d/subscribe/%d/%d/%d", API_VERSION, uId, chId, SDK_VERSION);
            }
            Log.d(TAG, "getSubscribeUri: " + subscribeUrl);
            return subscribeUrl;
        }

        static List<NameValuePair> getAuthHeader(Context context) {
            if(authHeader == null){
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
                String auth = prefs.getString(PREF_AUTH, null);
                authHeader = new ArrayList<>();
                authHeader.add(new NameValuePair("X-BF-AUTH", auth));
            }
            return authHeader;
        }

        static String getPingUrl(Context context) {
            if (pingUrl == null) {
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
                int uId = prefs.getInt(PREF_U_ID, 0);
                long chId = prefs.getLong(PREF_CH_ID, 0);
                pingUrl = String.format(Locale.US, "http://gw.bef.rest:8000/xapi/%d/ping/%d/%d", API_VERSION, uId, chId);
            }
            return pingUrl;
        }

        static String getPublishUrl(Context context) {
            if (publishUrl == null) {
                SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFRENCES_NAME, Context.MODE_PRIVATE);
                int uId = prefs.getInt(PREF_U_ID, 0);
                long chId = prefs.getLong(PREF_CH_ID, 0);
                publishUrl = String.format(Locale.US, "http://gw.bef.rest:8000/xapi/%d/publish/%d/%d", API_VERSION, uId, chId);
            }
            return publishUrl;
        }

        static String getBroadcastSendingPermission(Context context) {
            return context.getApplicationContext().getPackageName() + BROADCAST_SENDING_PERMISSION_POSTFIX;
        }

        static void enableConnectivityChangeListenerIfNeeded(Context context) {
            ComponentName receiver = new ComponentName(context, BefrestConnectivityChangeReceiver.class);
            PackageManager pm = context.getPackageManager();
            boolean isConnectedToInternet = isConnectedToInternet(context);
            boolean isConnectivityChangeListenerDisabled = pm.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            FileLog.m(TAG, "isConnectedToInternet, isConnectivityChangeListenerDisabled", isConnectedToInternet, isConnectivityChangeListenerDisabled);
            if (!isConnectedToInternet && isConnectivityChangeListenerDisabled)
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

        static String encodeBase64(String s) {
            try {
                return Base64.encodeToString(s.getBytes("UTF-8"), Base64.DEFAULT);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return "";
        }
    }

    public static void sendMessage(Context context, String msg) {
        new SendMessage(context, msg).execute();
    }

    private static class SendMessage extends AsyncTask<String, Void, String> {
        public final String TAG = SendMessage.class.getSimpleName();
        private String data;
        Context context;

        SendMessage(Context context, String data) {
            this.data = data;
            this.context = context;
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                URL url = new URL(Util.getPublishUrl(context));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("connection", "close");
                NameValuePair authHeader = Util.getAuthHeader(context).get(0);
                conn.addRequestProperty(authHeader.getName(), authHeader.getValue());
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5 * 1000);
                conn.setReadTimeout(5 * 1000);
                conn.connect();
                //wait to connect
                conn.getOutputStream().write(data.getBytes());
                conn.getOutputStream().flush();
                conn.getOutputStream().close();
                FileLog.d(TAG, "SendMessageStatus: " + conn.getResponseCode() + " " + conn.getResponseMessage());
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String res = "";
                String line;
                while ((line = bufferedReader.readLine()) != null)
                    res += line;
                FileLog.d(TAG, "SendMessageResult : " + res);
                conn.disconnect();
            } catch (Exception ex) {
                FileLog.e(TAG, ex);
            }
            return null;
        }
    }
}