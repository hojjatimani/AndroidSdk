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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import java.util.concurrent.CountDownLatch;

/**
 * Created by ehsan on 11/24/2015.
 */
public final class BefrestInternalReceiver extends BroadcastReceiver {
    public static final String TAG = BefrestInternalReceiver.class.getSimpleName();

    private final int LOCKS_TIMEOUT = 10 * 1000;
    PowerManager.WakeLock wakeLock;
    WifiManager wifiManager;
    WifiManager.WifiLock wifiLock;
    private volatile static CountDownLatch latch;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        FileLog.d(TAG, "Broadcast received: action=" + action);
        switch (action) {
            case ConnectivityManager.CONNECTIVITY_ACTION:
                if (Befrest.Util.isConnectedToInternet(context))
                    context.startService(new Intent(context, PushService.class).putExtra(PushService.NETWORK_CONNECTED, true));
                else
                    context.startService(new Intent(context, PushService.class).putExtra(PushService.NETWORK_DISCONNECTED, true));
                break;
            case Befrest.ACTION_WAKEUP:
                Befrest.Util.acquireWakeLock(context);
                context.startService(new Intent(context, PushService.class).putExtra(PushService.WAKEUP, true));
                break;
        }
    }

//    private void wakePhoneUp(Context context) {
//        if (isUserInteractive(context)) {
//            FileLog.d(TAG, "user is interactive. most likely device is not asleep");
//            return;
//        }
//        acquireWakeLock(context);
//        if (isWifiEnabled(context)) {
//            acquireWifiLock(context);
//            if (!isWifiConnectedOrConnecting(context)) {
//                connectWifi(context);
//                waitForConnection():
//                FileLog.d(TAG, "waiting for connection");
//            }
//        }
//        context.startService(new Intent(context, PushService.class).putExtra(PushService.REFRESH, true));
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                shutDown();
//            }
//        }, LOCKS_TIMEOUT);
//    }
//
//    private void waitForConnection() {
//        try {
//            latch = new CountDownLatch(1);
//            latch.await();
//            FileLog.d(TAG, "waiting");
//        }catch (InterruptedException e){
//            FileLog.d(TAG , "waiting interrupted");
//            FileLog.e(TAG, e);
//        }
//    }
//
//    private void shutDown() {
//        if (wakeLock != null) wakeLock.release();
//        if (wifiLock != null) wifiLock.release();
//        wakeLock = null;
//        wifiLock = null;
//        wifiManager = null;
//        FileLog.d(TAG, "locks released");
//    }
//
//    private void acquireWakeLock(Context context) {
//        PowerManager mgr =
//                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
//        wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ".WAKE_LOCK");
//        wakeLock.acquire();
//        FileLog.d(TAG, "wakeLock acquired");
//    }
//
//    private void acquireWifiLock(Context context) {
//        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG + ".WIFI_LOCK");
//        wifiLock.acquire();
//        FileLog.d(TAG, "wifiLock acquired");
//    }
//
//    private boolean isWifiEnabled(Context context) {
//        if (wifiManager == null)
//            wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
//        return wifiManager.isWifiEnabled();
//    }
//
//    private boolean isWifiConnectedOrConnecting(Context context) {
//        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
//        NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
//        return wifiInfo.isConnectedOrConnecting();
//    }
//
//    private void connectWifi(Context context) {
//        if (wifiManager == null)
//            wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
//        wifiManager.pingSupplicant(); // why is it here!?!?! before restarting wifi!
//
//        try {
//            // Brute force methods required for some devices
//            wifiManager.setWifiEnabled(false);
//            wifiManager.setWifiEnabled(true);
//        }catch (Exception e){
//            // Catching exception which should not occur on most
//            // devices. OS bug details at :
//            // https://code.google.com/p/android/issues/detail?id=22036
//            FileLog.e(TAG, e);
//        }
//        wifiManager.disconnect();
//        wifiManager.startScan();
//        wifiManager.reassociate();
//        wifiManager.reconnect();
//        FileLog.d(TAG, "connect wifi ...");
//    }
//
//    private boolean isUserInteractive(Context context) {
//        if (isScreenOn(context) || System.currentTimeMillis() - Befrest.lastScreenOnTime < 30 * 60 * 1000)
//            return true;
//        return false;
//    }
//
//    public boolean isScreenOn(Context context) {
//        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) { // API 20
//            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
//            for (Display display : dm.getDisplays())
//                if (display.getState() != Display.STATE_OFF) return true;
//        }
//        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
//        return pm.isScreenOn();
//    }
}