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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import rest.bef.connectivity.WebSocketConnection;
import rest.bef.connectivity.WebSocketConnectionHandler;
import rest.bef.connectivity.WebSocketException;


public final class PushService extends Service {

    private static final String TAG = PushService.class.getSimpleName();

    //commands
    /* package */ static final String NETWORK_CONNECTED = "NETWORK_CONNECTED";
    /* package */ static final String NETWORK_DISCONNECTED = "NETWORK_DISCONNECTED";
    /* package */ static final String CONNECT = "CONNECT";
    /* package */ static final String REFRESH = "REFRESH";
    /* package */ static final String WAKEUP = "WAKEUP";
    private static final String NOT_ASSIGNED = "NOT_ASSIGNED";

    public static final String PING_PREFIX = String.valueOf((int) (Math.random() * 9999));

    //service wakeup variables and constants
    private static final int PUSH_SYNC_TIMEOUT = 45 * 1000;
    private static final int CONNECT_TIMEOUT = 45 * 1000;
    private volatile static CountDownLatch latch;
    private LocalWifiStateChangeReceiver wifiStateChangeListener;

    //number of previous successful pings in current connection
    private int prevSuccessfulPings;

    //number of previous failed retry attempts
    private int prevFailedConnectTries;

    //last time a ping was set to be sent delayed
    private long lastPingSetTime;

    //constants
    private static final int ASK_FOR_AUTH_INTERVAL = 35 * 1000;
    private static final int[] pingInterval = {10 * 1000, 30 * 1000, 50 * 1000, 70 * 1000, 100 * 1000, 200 * 1000, 500 * 1000};
    private static final int[] retryInterval = {0, 5 * 1000, 10 * 1000, 25 * 1000, 50 * 1000};
    private static final int PING_TIMEOUT = 10 * 1000;

    public static final int TIME_PER_MESSAGE_IN_BATH_MODE = 10;
    private static final int BATCH_MODE_TIMEOUT = 1 * 1000;
    private static int BATCH_SIZE;

    private int PING_ID = 0;
    private boolean retryInProgress;
    private boolean restartInProgress;
    private boolean isBachReceiveMode;
    private boolean connecting;

    private List<Parcelable> messages = new ArrayList<>();
    private Handler handler = new Handler();
    private WebSocketConnection mConnection;

    private WebSocketConnectionHandler wscHandler = new WebSocketConnectionHandler() {
        @Override
        public void onOpen() {
            FileLog.d(TAG, "Befrest Connected");
            connecting = false;
            prevFailedConnectTries = prevSuccessfulPings = 0;
            cancelALLPendingIntents();
            notifyConnectionRefreshedIfNeeded();
            setNextPingToSendInFuture();
            Befrest.Util.disableConnectivityChangeListener(PushService.this);
        }

        @Override
        public void onTextMessage(String message) {
            BefrestMessage msg = new BefrestMessage(message);
            FileLog.d(TAG, "Got Notif:: " + msg.type + "  " + msg);
            switch (msg.type) {
                case DATA:
                    messages.add(msg);
                    if (!isBachReceiveMode) {
                        sendBefrestBroadcast(Befrest.BroadcastType.PUSH);
                        revisePinging();
                    }
                    break;
                case BATCH:
                    isBachReceiveMode = true;
                    BATCH_SIZE = Integer.valueOf(msg.data);
                    int batchTime = getBatchTime();
                    FileLog.d(TAG, "BATCH Mode for : " + batchTime + "milliseconds");
                    handler.postDelayed(finishBatchMode, batchTime);
                    break;
                case PONG:
                    onPong(msg.data);
            }
        }

        @Override
        public void onClose(int code, String reason) {
            FileLog.d(TAG, "Connection lost. Code: " + code + ", Reason: " + reason);
            connecting = false;
            switch (code) {
                case CLOSE_UNAUTHORIZED:
                    handleAthorizeProblem();
                    break;
                case CLOSE_CANNOT_CONNECT:
                case CLOSE_CONNECTION_LOST:
                case CLOSE_INTERNAL_ERROR:
                case CLOSE_NORMAL:
                case CLOSE_PROTOCOL_ERROR:
                case CLOSE_SERVER_ERROR:
                case CLOSE_HANDSHAKE_TIME_OUT:
                    Befrest.Util.enableConnectivityChangeListener(PushService.this);
                    scheduleReconnect();
            }
        }
    };

    private BroadcastReceiver screenStateBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Befrest.Util.lastScreenOnTime = System.currentTimeMillis();
        }
    };

    private Runnable sendPing = new Runnable() {
        @Override
        public void run() {
            sendPing();
        }
    };

    private Runnable restart = new Runnable() {
        @Override
        public void run() {
            FileLog.d(TAG, "restart");
            restartInProgress = false;
            mConnection.disconnect();
            connectIfNeeded();
        }
    };

    private Runnable retry = new Runnable() {
        @Override
        public void run() {
            FileLog.d(TAG, "retry");
            retryInProgress = false;
            mConnection.disconnect();
            reconnectIfNeeded();
        }
    };

    private Runnable finishBatchMode = new Runnable() {
        @Override
        public void run() {
            int receivedMsgs = messages.size();
            if ((receivedMsgs >= BATCH_SIZE - 1)) {
                isBachReceiveMode = false;
            } else {
                BATCH_SIZE -= messages.size();
                handler.postDelayed(finishBatchMode, getBatchTime());
            }
            if (receivedMsgs > 0) {
                sendBefrestBroadcast(Befrest.BroadcastType.PUSH);
                revisePinging();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        FileLog.d(TAG, "onCreate()");
        mConnection = new WebSocketConnection();
        registerScreenStateBroadCastReceiver();
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = getCommand(intent);
        FileLog.d(TAG, "onStartCommand(" + command + ")");
        switch (command) {
            case NETWORK_CONNECTED:
            case CONNECT:
            case NOT_ASSIGNED:
                connectIfNeeded();
                break;
            case REFRESH:
                refresh();
                break;
            case NETWORK_DISCONNECTED:
                cancelALLPendingIntents();
                break;
            case WAKEUP:
                new WakeServiceUp().start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        FileLog.d(TAG, "onDestroy()");
        mConnection.disconnect();
        cancelALLPendingIntents();
        unRegisterScreenStateBroadCastReceiver();
        wscHandler = null;
        super.onDestroy();
        if (!Befrest.LegalStop) {
            FileLog.d(TAG, "illegal Stop! starting app again.");
            startService(new Intent(getApplicationContext(), PushService.class).putExtra(PushService.CONNECT, true));
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!Befrest.LegalStop) {
            FileLog.d(TAG, "illegal onTaskRemoved! starting app again.");
            startService(new Intent(getApplicationContext(), PushService.class).putExtra(PushService.CONNECT, true));
        }
    }

    private String getCommand(Intent intent) {
        if (intent != null) {
            if (intent.getBooleanExtra(CONNECT, false))
                return CONNECT;
            if (intent.getBooleanExtra(REFRESH, false))
                return REFRESH;
            if (intent.getBooleanExtra(NETWORK_CONNECTED, false))
                return NETWORK_CONNECTED;
            if (intent.getBooleanExtra(NETWORK_DISCONNECTED, false))
                return NETWORK_DISCONNECTED;
            if (intent.getBooleanExtra(WAKEUP, false))
                return WAKEUP;
        }
        return NOT_ASSIGNED;
    }

    private void registerScreenStateBroadCastReceiver() {
        registerReceiver(screenStateBroadCastReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private void unRegisterScreenStateBroadCastReceiver() {
        unregisterReceiver(screenStateBroadCastReceiver);
    }

    private void scheduleReconnect() {
        boolean hasNetworkConnection = Befrest.Util.isConnectedToInternet(this);
        FileLog.m(TAG, "scheduleReconnect() retryInProgress, restartInProgress, hasNetworkConnection", retryInProgress, restartInProgress, hasNetworkConnection);
        if (retryInProgress || restartInProgress || !hasNetworkConnection)
            return; //a retry or restart is already in progress or close was due to internet connection lost
        cancelFuturePing();
        prevFailedConnectTries++;
        int interval = getNextReconnectInterval();
        FileLog.d(TAG, "scheduled    interval : " + interval);
        handler.postDelayed(retry, getNextReconnectInterval());
        retryInProgress = true;
    }

    private void handleAthorizeProblem() {
        Befrest.Util.enableConnectivityChangeListener(this);
        boolean hasNetworkConnection = Befrest.Util.isConnectedToInternet(this);
        if (hasNetworkConnection) sendBefrestBroadcast(Befrest.BroadcastType.UNAUTHORIZED);
        cancelALLPendingIntents();
        handler.postDelayed(retry, ASK_FOR_AUTH_INTERVAL);
        retryInProgress = true;
    }

    private void notifyConnectionRefreshedIfNeeded() {
        if (Befrest.refreshIsRequested) {
            FileLog.d(TAG, "notifyRefreshIfNeeded");
            sendBefrestBroadcast(Befrest.BroadcastType.CONNECTION_REFRESHED);
            Befrest.refreshIsRequested = false;
            Befrest.lastAcceptedRefreshRequestTime = 0;
        }
    }

    private void connectIfNeeded() {
        FileLog.d(TAG, "connectIfNeeded()");
        if (shouldConnect())
            try {
                connecting = true;
                FileLog.d(TAG, "connecting ...");
                mConnection.connect(Befrest.Util.getSubscribeUri(this), wscHandler, Befrest.Util.getSubscribeHeaders(this));
            } catch (WebSocketException e) {
                FileLog.e(TAG, e);
            }
    }


    private boolean shouldConnect() {
        boolean isAleadyConnected = mConnection.isConnected();
        boolean isConnectedToInternet = Befrest.Util.isConnectedToInternet(this);
        FileLog.m(TAG, "shouldConnect() isAleadyConnected, isConnectedToInternet, connecting", isAleadyConnected, isConnectedToInternet, connecting);
        return !isAleadyConnected && isConnectedToInternet && !connecting;
    }

    private void reconnectIfNeeded() {
        FileLog.d(TAG, "reconnectIfNeeded()");
        if (shouldConnect()) {
            connecting = true;
            FileLog.d(TAG, "reconnecting ...");
            mConnection.reconnect();
        }
    }

    private void sendBefrestBroadcast(int type) {
        Intent intent = new Intent(Befrest.ACTION_BEFREST_PUSH);
        intent.putExtra(Befrest.BROADCAST_TYPE, type);
        if (type == Befrest.BroadcastType.PUSH) {
            Parcelable[] data = new BefrestMessage[messages.size()];
            data = messages.toArray(data);
            intent.putExtra(Befrest.Util.KEY_MESSAGE_PASSED, data);
            messages.clear();
//            intent.putExtra(Befrest.Util.KEY_MESSAGE_PASSED, (ArrayList<Parcelable>)messages);
//            messages = new ArrayList<Parcelable>();
        }
        sendBroadcast(intent, Befrest.Util.getBroadcastSendingPermission(this));
    }

    private void sendPing() {
        PING_ID = (PING_ID + 1) % 5;
        new SendPing(this, PING_PREFIX + PING_ID).start();
        restartInProgress = true;
        handler.postDelayed(restart, PING_TIMEOUT);
    }

    private void onPong(String pongData) {
        boolean isValid = isValidPong(pongData);
        FileLog.d(TAG, "onPong(" + pongData + ") " + (isValid ? "valid" : "invalid!"));
        if (!isValid) return;
        cancelUpcommingRestart();
        prevSuccessfulPings++;
        setNextPingToSendInFuture();
        notifyConnectionRefreshedIfNeeded();
    }

    private boolean isValidPong(String pongData) {
        return (PING_PREFIX + PING_ID).equals(pongData);
    }

    private void cancelALLPendingIntents() {
        cancelFuturePing();
        cancelFutureRetry();
        cancelUpcommingRestart();
    }

    private void cancelFuturePing() {
        FileLog.d(TAG, "cancelFuturePing()");
        handler.removeCallbacks(sendPing);
    }

    private void cancelFutureRetry() {
        FileLog.d(TAG, "cancelFutureRetry()");
        handler.removeCallbacks(retry);
        retryInProgress = false;
    }

    private void cancelUpcommingRestart() {
        FileLog.d(TAG, "cancelUpcommingRestart()");
        handler.removeCallbacks(restart);
        restartInProgress = false;
    }

    private int getNextReconnectInterval() {
        return retryInterval[prevFailedConnectTries < retryInterval.length ? prevFailedConnectTries : retryInterval.length - 1];
    }

    private int getBatchTime() {
        int requiredTime = TIME_PER_MESSAGE_IN_BATH_MODE * BATCH_SIZE;
        return (requiredTime < BATCH_MODE_TIMEOUT ? requiredTime : BATCH_MODE_TIMEOUT);
    }

    private int getPingInterval() {
        return pingInterval[prevSuccessfulPings < pingInterval.length ? prevSuccessfulPings : pingInterval.length - 1];
    }

    private void revisePinging() {
        if (restartInProgress || System.currentTimeMillis() - lastPingSetTime < getPingInterval() / 2)
            return;
        prevSuccessfulPings++;
        setNextPingToSendInFuture();
        FileLog.d(TAG, "pinging revised");
    }

    private void refresh() {
        if (connecting) {
            FileLog.d(TAG, "is connecting right now!");
        } else if (retryInProgress) {
            cancelFutureRetry();
            prevFailedConnectTries = 0;
            handler.post(retry);
        } else if (restartInProgress) {
            cancelUpcommingRestart();
            handler.post(restart);
        } else if (shouldConnect()) {
            cancelALLPendingIntents();
            connectIfNeeded();
        } else {
            prevSuccessfulPings = 0;
            setNextPingToSendInFuture(0);
        }
    }

    private void setNextPingToSendInFuture() {
        setNextPingToSendInFuture(getPingInterval());
    }

    private void setNextPingToSendInFuture(int interval) {
        cancelFuturePing(); //cancel any previous ping set
        FileLog.d(TAG, "setNextPingToSendInFuture()  interval : " + interval);
        lastPingSetTime = System.currentTimeMillis();
        handler.postDelayed(sendPing, interval);
    }

    private class WakeServiceUp extends Thread {
        private static final String TAG = "WakeServiceUp";

        public WakeServiceUp() {
            super(TAG);
        }

        @Override
        public void run() {
            if (Befrest.Util.isUserInteractive(PushService.this)) {
                FileLog.d(TAG, "user is interactive. most likely " + "device is not asleep");
                refresh();
            } else if (Befrest.Util.isConnectedToInternet(PushService.this)) {
                FileLog.d(TAG, "already connected to internet");
                refresh();
                giveSomeTimeToService();
            } else if (Befrest.Util.isWifiEnabled(PushService.this)) {
                Befrest.Util.acquireWifiLock(PushService.this);
                startMonitoringWifiState();
                Befrest.Util.askWifiToConnect(PushService.this);
                waitForConnection();
                stopMonitoringWifiState();
                refresh();
                giveSomeTimeToService();
                Befrest.Util.releaseWifiLock();
            } else {
                FileLog.d(TAG, "no kind of network is enabled");
            }
            Befrest.Util.releaseWakeLock();
        }

        private void giveSomeTimeToService() {
            try {
                boolean connectedToInternet = Befrest.Util.isConnectedToInternet(PushService.this);
                FileLog.m(TAG, "isConnectedToInternet", connectedToInternet);
                if (connectedToInternet || Befrest.Util.isWifiConnectedOrConnecting(PushService.this))
                    Thread.sleep(PUSH_SYNC_TIMEOUT);
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            }
        }
    }

    private void waitForConnection() {
        try {
            latch = new CountDownLatch(1);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (latch.getCount() == 1) {
                        FileLog.d(TAG, "Wifi Connect Timeout!");
                        latch.countDown();
                    }
                }
            }, CONNECT_TIMEOUT);
            FileLog.d(TAG, "Waiting For Wifi to Connect ...");
            latch.await();
            FileLog.d(TAG, "Finish Waiting For Wifi Connection");
        } catch (InterruptedException e) {
            FileLog.d(TAG, "waiting interrupted");
            FileLog.e(TAG, e);
        }
    }

    private void startMonitoringWifiState() {
        if (wifiStateChangeListener == null)
            wifiStateChangeListener = new LocalWifiStateChangeReceiver();
        registerReceiver(wifiStateChangeListener, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
    }

    private void stopMonitoringWifiState() {
        if (wifiStateChangeListener != null) unregisterReceiver(wifiStateChangeListener);
    }

    private final class LocalWifiStateChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent i) {
            if (i.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                if (Befrest.Util.isWifiConnectedOrConnecting(context))
                    if (latch != null && latch.getCount() == 1)
                        latch.countDown();
            }
        }
    }
}