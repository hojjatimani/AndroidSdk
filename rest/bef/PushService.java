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
import android.util.Log;

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
    /* package */ static final String START = "START";
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

    private boolean noAuthProblemSinceLastStart = true;

    //last time a ping was set to be sent delayed
    private long lastPingSetTime;

    //constants
    private static final int ASK_FOR_AUTH_INTERVAL = 35 * 1000;
    private static final int[] pingInterval = {10 * 1000, 30 * 1000, 50 * 1000, 70 * 1000, 100 * 1000, 200 * 1000, 500 * 1000};
    private static final int[] retryInterval = {0, 5 * 1000, 10 * 1000, 25 * 1000, 50 * 1000};
    private static final int PING_TIMEOUT = 10 * 1000;
    static final int startServiceAgainDelay = 10 * 1000;

    public static final int TIME_PER_MESSAGE_IN_BATH_MODE = 10;
    private static final int BATCH_MODE_TIMEOUT = 1 * 1000;
    private static int BATCH_SIZE;

    private int PING_ID = 0;
    private boolean retryInProgress;
    private boolean restartInProgress;
    private boolean isBachReceiveMode;
    private boolean connecting;

    private Befrest befrest;

    private volatile boolean stopHandled = false;

    private List<Parcelable> messages = new ArrayList<>();
    private Handler handler = new Handler();
    private WebSocketConnection mConnection;

    private WebSocketConnectionHandler wscHandler = new WebSocketConnectionHandler() {
        @Override
        public void onOpen() {
            BefLog.i(TAG, "Befrest Connected");
            connecting = false;
            prevFailedConnectTries = prevSuccessfulPings = 0;
            noAuthProblemSinceLastStart = true;
            befrest.prevAuthProblems = 0;
            sendBefrestBroadcast(BefrestPushReceiver.BEFREST_CONNECTED);
            cancelALLPendingIntents();
            notifyConnectionRefreshedIfNeeded();
            setNextPingToSendInFuture();
            Befrest.Util.disableConnectivityChangeListener(PushService.this);
        }

        @Override
        public void onTextMessage(String message) {
            BefrestMessage msg = new BefrestMessage(message);
            switch (msg.type) {
                case DATA:
                    BefLog.i(TAG, "Notification Received:: " + msg);
                    messages.add(msg);
                    if (!isBachReceiveMode) {
                        sendBefrestBroadcast(BefrestPushReceiver.PUSH);
                        revisePinging();
                    }
                    break;
                case BATCH:
                    BefLog.d(TAG, "Notification Received:: " + msg.type + "  " + msg);
                    isBachReceiveMode = true;
                    BATCH_SIZE = Integer.valueOf(msg.data);
                    int batchTime = getBatchTime();
                    BefLog.v(TAG, "BATCH Mode Started for : " + batchTime + "ms");
                    handler.postDelayed(finishBatchMode, batchTime);
                    break;
                case PONG:
                    BefLog.v(TAG, "Notification Received:: " + msg.type + "  " + msg);
                    onPong(msg.data);
            }
        }

        @Override
        public void onClose(int code, String reason) {
            BefLog.d(TAG, "Connection lost. Code: " + code + ", Reason: " + reason);
            BefLog.i(TAG, "Befrest Connection Closed. Will Retry To Connect If Possible.");
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
            BefLog.d(TAG, "Befrest Connection Is Not Responding. Will Reconnect.");
            restartInProgress = false;
            mConnection.disconnect();
            connectIfNeeded();
        }
    };

    private Runnable retry = new Runnable() {
        @Override
        public void run() {
            BefLog.d(TAG, "Befrest Retry");
            retryInProgress = false;
            mConnection.disconnect();
//            reconnectIfNeeded();
            connectIfNeeded();
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
                sendBefrestBroadcast(BefrestPushReceiver.PUSH);
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
        BefLog.v(TAG, "PushService onCreate()");
        befrest = Befrest.getInstance(this);
        mConnection = new WebSocketConnection();
        registerScreenStateBroadCastReceiver();
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = getCommand(intent);
        BefLog.v(TAG, "PushService onStartCommand(" + command + ")");
        switch (command) {
            case NETWORK_CONNECTED:
            case CONNECT:
            case START:
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
        BefLog.v(TAG, "PushService onDestroy()");
        cancelALLPendingIntents();
        mConnection.disconnect();
        unRegisterScreenStateBroadCastReceiver();
        wscHandler = null;
        super.onDestroy();
        if (!stopHandled) {
            if (befrest.legalStop) {
                befrest.legalStop = false;
            } else {
                BefLog.v(TAG, "illegal Stop! starting app again.");
                befrest.setStartServiceAlarm();
            }
            stopHandled = true;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        BefLog.v(TAG, "PushService onTaskRemoved: ");
        super.onTaskRemoved(rootIntent);
        if (!stopHandled) {
            if (befrest.legalStop) {
                befrest.legalStop = false;
            } else {
                BefLog.v(TAG, "illegal onTaskRemoved! starting app again.");
                befrest.setStartServiceAlarm();
            }
            stopHandled = true;
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
            if (intent.getBooleanExtra(START, false))
                return START;
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
        BefLog.v(TAG, "scheduleReconnect() retryInProgress, restartInProgress, hasNetworkConnection", retryInProgress, restartInProgress, hasNetworkConnection);
        if (retryInProgress || restartInProgress || !hasNetworkConnection)
            return; //a retry or restart is already in progress or network in unavailable
        cancelALLPendingIntents();
        prevFailedConnectTries++;
        int interval = getNextReconnectInterval();
        BefLog.d(TAG, "Befrest Will Retry To Connect In " + interval + "ms");
        handler.postDelayed(retry, getNextReconnectInterval());
        retryInProgress = true;
    }

    private void handleAthorizeProblem() {
        BefLog.i(TAG, "Befrest On Authorize Problem!");
        Befrest.Util.enableConnectivityChangeListener(this);
        if (befrest.prevAuthProblems == 0) {
            sendBefrestBroadcast(BefrestPushReceiver.UNAUTHORIZED);
        } else if (noAuthProblemSinceLastStart) {
            //dont send broadcast
        } else {
            sendBefrestBroadcast(BefrestPushReceiver.UNAUTHORIZED);
        }
        cancelALLPendingIntents();
        handler.postDelayed(retry, befrest.getSendOnAuthorizeBroadcastDelay());
        retryInProgress = true;
        befrest.prevAuthProblems++;
        noAuthProblemSinceLastStart = false;
    }

    private void notifyConnectionRefreshedIfNeeded() {
        if (befrest.refreshIsRequested) {
            sendBefrestBroadcast(BefrestPushReceiver.CONNECTION_REFRESHED);
            befrest.refreshIsRequested = false;
            befrest.lastAcceptedRefreshRequestTime = 0;
            Log.i(TAG, "Befrest Refreshed");
        }
    }

    private void connectIfNeeded() {
        BefLog.v(TAG, "connectIfNeeded()");
        if (shouldConnect())
            try {
                cancelALLPendingIntents();
                connecting = true;
                BefLog.i(TAG, "Befrest Is Connecting ...");
                mConnection.connect(befrest.getSubscribeUri(), wscHandler, befrest.getSubscribeHeaders());
            } catch (WebSocketException e) {
                BefLog.e(TAG, e);
            }
    }

    private boolean shouldConnect() {
        boolean isAleadyConnected = mConnection.isConnected();
        boolean isConnectedToInternet = Befrest.Util.isConnectedToInternet(this);
        BefLog.v(TAG, "shouldConnect() isAleadyConnected, isConnectedToInternet, connecting", isAleadyConnected, isConnectedToInternet, connecting);
        boolean shouldConnect = !isAleadyConnected && isConnectedToInternet && !connecting;
        return !isAleadyConnected && isConnectedToInternet && !connecting;
    }

    private void sendBefrestBroadcast(int type) {
        Intent intent = new Intent(BefrestPushReceiver.ACTION_BEFREST_PUSH);
        intent.putExtra(BefrestPushReceiver.BROADCAST_TYPE, type);
        if (type == BefrestPushReceiver.PUSH) {
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
        BefLog.d(TAG, "onPong(" + pongData + ") " + (isValid ? "valid" : "invalid!"));
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
        BefLog.v(TAG, "cancelFuturePing()");
        handler.removeCallbacks(sendPing);
    }

    private void cancelFutureRetry() {
        BefLog.v(TAG, "cancelFutureRetry()");
        handler.removeCallbacks(retry);
        retryInProgress = false;
    }

    private void cancelUpcommingRestart() {
        BefLog.v(TAG, "cancelUpcommingRestart()");
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
        BefLog.v(TAG, "Befrest Pinging Revised");
    }

    private void refresh() {
        if (connecting) {
            BefLog.v(TAG, "Befrest is connecting right now!");
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
        BefLog.v(TAG, "setNextPingToSendInFuture()  interval : " + interval);
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
                BefLog.v(TAG, "User is interactive. most likely " + "device is not asleep");
                refresh();
            } else if (Befrest.Util.isConnectedToInternet(PushService.this)) {
                BefLog.v(TAG, "Already connected to internet");
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
                BefLog.v(TAG, "No kind of network is enable");
            }
            Befrest.Util.releaseWakeLock();
        }

        private void giveSomeTimeToService() {
            try {
                boolean connectedToInternet = Befrest.Util.isConnectedToInternet(PushService.this);
                BefLog.v(TAG, "isConnectedToInternet", connectedToInternet);
                if (connectedToInternet || Befrest.Util.isWifiConnectedOrConnecting(PushService.this))
                    Thread.sleep(PUSH_SYNC_TIMEOUT);
            } catch (InterruptedException e) {
                BefLog.e(TAG, e);
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
                        BefLog.v(TAG, "Wifi Connect Timeout!");
                        latch.countDown();
                    }
                }
            }, CONNECT_TIMEOUT);
            BefLog.v(TAG, "Waiting For Wifi to Connect ...");
            latch.await();
            BefLog.v(TAG, "Finish Waiting For Wifi Connection");
        } catch (InterruptedException e) {
            BefLog.v(TAG, "waiting interrupted");
            BefLog.e(TAG, e);
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