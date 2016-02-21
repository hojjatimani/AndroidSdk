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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import rest.bef.connectivity.WebSocketConnection;
import rest.bef.connectivity.WebSocketConnectionHandler;
import rest.bef.connectivity.WebSocketException;


public final class PushService extends Service {

    private static final String TAG = PushService.class.getSimpleName();

    //events
    /* package */ static final String NETWORK_CONNECTED = "NETWORK_CONNECTED";
    /* package */ static final String NETWORK_DISCONNECTED = "NETWORK_DISCONNECTED";
    /* package */ static final String CONNECT = "CONNECT";
    /* package */ static final String REFRESH = "REFRESH";
    /* package */ static final String WAKEUP = "WAKEUP";
    /* package */ static final String SERVICE_STOPPED = "SERVICE_STOPPED";
    /* package */ static final String RETRY = "RETRY";
    /* package */ static final String RESTART = "RESTART";

    //pinging variables and constants
    private static final int[] PING_INTERVAL = {10 * 1000, 30 * 1000, 60 * 1000, 100 * 1000, 150 * 1000 , 210 * 1000};
    private static final int PING_TIMEOUT = 5 * 1000;
    private static final String PING_DATA_PREFIX = String.valueOf((int) (Math.random() * 9999));
    private int currentPingId = 0;
    private int prevSuccessfulPings;
    private long lastPingSetTime; //last time a ping was set to be sent delayed
    private Runnable sendPing = new Runnable() {
        @Override
        public void run() {
            sendPing();
        }
    };
    private Runnable restart = new Runnable() {
        @Override
        public void run() {
            handleEvent(RESTART);
        }
    };

    //retrying variables and constants
    private static final int[] RETRY_INTERVAL = {0, 5 * 1000, 10 * 1000, 25 * 1000, 50 * 1000};
    private int prevFailedConnectTries;
    private Runnable retry = new Runnable() {
        @Override
        public void run() {
            handleEvent(RETRY);
        }
    };

    //bach mode variables and constants
    public static final int TIME_PER_MESSAGE_IN_BATH_MODE = 10;
    private static final int BATCH_MODE_TIMEOUT = 1000;
    private static int batchSize;
    private boolean isBachReceiveMode;

    //service wakeup variables and constants
    private static final int PUSH_SYNC_TIMEOUT = 45 * 1000;
    private static final int CONNECT_TIMEOUT = 45 * 1000;
    private volatile static CountDownLatch latch;
    private LocalWifiStateChangeReceiver wifiStateChangeListener;

    //state controllers
    private boolean retryInProgress;
    private boolean restartInProgress;
    private boolean connecting;

    static final int START_SERVICE_AFTER_ILLEGAL_STOP_DELAY = 10 * 1000;
    private boolean legalStop = false;

    private boolean authProblemSinceLastStart = false;

    private List<Parcelable> receivedMessages = new ArrayList<>();
    private Befrest befrest;
    private Handler handler = new Handler();
    private WebSocketConnection mConnection;

    private WebSocketConnectionHandler wscHandler;

    private BroadcastReceiver screenStateBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF))
                Befrest.Util.lastScreenOnTime = System.currentTimeMillis();
            else internalRefreshIfPossible();
        }
    };

    private Runnable finishBatchMode = new Runnable() {
        @Override
        public void run() {
            int receivedMsgs = receivedMessages.size();
            if ((receivedMsgs >= batchSize - 1))
                isBachReceiveMode = false;
            else {
                batchSize -= receivedMsgs;
                handler.postDelayed(finishBatchMode, getBatchTime());
            }
            if (receivedMsgs > 0)
                sendBefrestBroadcast(BefrestPushReceiver.PUSH);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        BefLog.v(TAG, "PushService:" + System.identityHashCode(this) + "  onCreate()");
        befrest = Befrest.getInstance(this);
        mConnection = new WebSocketConnection();
        wscHandler = new WebSocketConnectionHandler() {
            @Override
            public void onOpen() {
                BefLog.i(TAG, "Befrest Connected");
                connecting = false;
                prevFailedConnectTries = prevSuccessfulPings = 0;
                authProblemSinceLastStart = true;
                befrest.prevAuthProblems = 0;
                sendBefrestBroadcast(BefrestPushReceiver.BEFREST_CONNECTED);
                cancelALLPendingIntents();
                notifyConnectionRefreshedIfNeeded();
                setNextPingToSendInFuture();
            }

            @Override
            public void onTextMessage(String message) {
                BefrestMessage msg = new BefrestMessage(message);
                switch (msg.type) {
                    case DATA:
                        BefLog.i(TAG, "Befrest Push Received:: " + msg);
                        receivedMessages.add(msg);
                        if (!isBachReceiveMode)
                            sendBefrestBroadcast(BefrestPushReceiver.PUSH);
                        break;
                    case BATCH:
                        BefLog.d(TAG, "Befrest Push Received:: " + msg.type + "  " + msg);
                        isBachReceiveMode = true;
                        batchSize = Integer.valueOf(msg.data);
                        int batchTime = getBatchTime();
                        BefLog.v(TAG, "BATCH Mode Started for : " + batchTime + "ms");
                        handler.postDelayed(finishBatchMode, batchTime);
                        break;
                    case PONG:
                        BefLog.v(TAG, "Deprecated Pong Message!!:: " + msg.type + "  " + msg);
                        PushService.this.onPong(msg.data);
                }
            }

            @Override
            public void onPong(byte[] payload) {
                PushService.this.onPong(new String(payload, Charset.defaultCharset()));
            }

            @Override
            public void onClose(int code, String reason) {
                BefLog.d(TAG, "WebsocketConnectionHandler: " + System.identityHashCode(this) + "Connection lost. Code: " + code + ", Reason: " + reason);
                BefLog.i(TAG, "Befrest Connection Closed. Will Try To Reconnect If Possible.");
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
                        scheduleReconnect();
                }
            }
        };
        registerScreenStateBroadCastReceiver();
        super.onCreate();
        System.gc();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleEvent(getIntentEvent(intent));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BefLog.v(TAG, "PushService:" + System.identityHashCode(this) + " onDestroy()==================START===============");
        cancelALLPendingIntents();
        mConnection.disconnect();
        wscHandler = null;
        mConnection = null;
        unRegisterScreenStateBroadCastReceiver();
        super.onDestroy();
        if (!legalStop)
            befrest.setStartServiceAlarm();
        BefLog.v(TAG, "PushService onDestroy()==================END===============");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        BefLog.v(TAG, "PushService onTaskRemoved: ");
        super.onTaskRemoved(rootIntent);
        if (!legalStop)
            befrest.setStartServiceAlarm();
    }

    private void handleEvent(String command) {
        BefLog.v(TAG, "PushService:" + System.identityHashCode(this) + " handleEvent( " + command + " )");
        switch (command) {
            case NETWORK_CONNECTED:
            case CONNECT:
                connectIfNeeded();
                break;
            case REFRESH:
                refresh();
                break;
            case RESTART:
                BefLog.d(TAG, "Befrest Connection Is Not Responding. Will Reconnect.");
                restartInProgress = false;
                reconnect();
                break;
            case RETRY:
                retryInProgress = false;
                reconnect();
                break;
            case NETWORK_DISCONNECTED:
                cancelALLPendingIntents();
                break;
            case WAKEUP:
                new WakeServiceUp().start();
                break;
            case SERVICE_STOPPED:
                handleServiceStopped();
                break;
            default:
                connectIfNeeded();
        }
    }

    private void reconnect() {
        BefLog.v(TAG, "call shouldConnect to log state");
        shouldConnect();
        mConnection.disconnect();
        connectIfNeeded();
    }

    private String getIntentEvent(Intent intent) {
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
            if (intent.getBooleanExtra(SERVICE_STOPPED, false))
                return SERVICE_STOPPED;
        }
        return "NOT_ASSIGNED";
    }

    private void registerScreenStateBroadCastReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateBroadCastReceiver, filter);
    }

    private void unRegisterScreenStateBroadCastReceiver() {
        unregisterReceiver(screenStateBroadCastReceiver);
    }

    private void scheduleReconnect() {
        boolean hasNetworkConnection = Befrest.Util.isConnectedToInternet(this);
        BefLog.v(TAG, "scheduleReconnect() retryInProgress, restartInProgress, hasNetworkConnection", retryInProgress, restartInProgress, hasNetworkConnection);
        if (retryInProgress || restartInProgress || !hasNetworkConnection)
            return; //a retry or restart is already in progress or network in unavailable
        cancelALLPendingIntents(); //just to be sure
        prevFailedConnectTries++;
        int interval = getNextReconnectInterval();
        BefLog.d(TAG, "Befrest Will Retry To Connect In " + interval + "ms");
        handler.postDelayed(retry, getNextReconnectInterval());
        retryInProgress = true;
    }

    private void handleServiceStopped() {
        if (befrest.isBefrestStarted) {
            if (!(retryInProgress || restartInProgress))
                connectIfNeeded();
        } else {
            legalStop = true;
            stopSelf();
        }
    }

    private void handleAthorizeProblem() {
        BefLog.i(TAG, "Befrest On Authorize Problem!");
        if (befrest.prevAuthProblems == 0)
            sendBefrestBroadcast(BefrestPushReceiver.UNAUTHORIZED);
        else if (authProblemSinceLastStart)
            sendBefrestBroadcast(BefrestPushReceiver.UNAUTHORIZED);

        cancelALLPendingIntents();
        handler.postDelayed(retry, befrest.getSendOnAuthorizeBroadcastDelay());
        retryInProgress = true;
        befrest.prevAuthProblems++;
        authProblemSinceLastStart = true;
    }

    private void notifyConnectionRefreshedIfNeeded() {
        if (befrest.refreshIsRequested) {
            sendBefrestBroadcast(BefrestPushReceiver.CONNECTION_REFRESHED);
            befrest.refreshIsRequested = false;
            BefLog.i(TAG, "Befrest Refreshed");
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
        BefLog.v(TAG, "shouldConnect %%%%%%%%%%%START%%%%%%%%%%%%");
        BefLog.v(TAG, "mConnection: " + System.identityHashCode(mConnection));
        boolean isAleadyConnected = mConnection.isConnected();
        boolean isConnectedToInternet = Befrest.Util.isConnectedToInternet(this);
        BefLog.v(TAG, "shouldConnect() isAleadyConnected, isConnectedToInternet, connecting", isAleadyConnected, isConnectedToInternet, connecting);
        BefLog.v(TAG, "shouldConnect %%%%%%%%%%%END%%%%%%%%%%%%%");
        return !isAleadyConnected && isConnectedToInternet && !connecting;
    }

    private void sendBefrestBroadcast(int type) {
        Intent intent = new Intent(BefrestPushReceiver.ACTION_BEFREST_PUSH).putExtra(BefrestPushReceiver.BROADCAST_TYPE, type);
        if (type == BefrestPushReceiver.PUSH) {
            Parcelable[] data = new BefrestMessage[receivedMessages.size()];
            intent.putExtra(Befrest.Util.KEY_MESSAGE_PASSED, receivedMessages.toArray(data));
            receivedMessages.clear();
            revisePinging();
        }
        sendBroadcast(intent, Befrest.Util.getBroadcastSendingPermission(this));
    }

    private void sendPing() {
        handler.postDelayed(restart, PING_TIMEOUT);
        restartInProgress = true;
        currentPingId = (currentPingId + 1) % 5;
        String payload = PING_DATA_PREFIX + currentPingId;
        BefLog.d(TAG, "Sending Ping ...");
        mConnection.sendPing(payload.getBytes(Charset.defaultCharset()));
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
        return (PING_DATA_PREFIX + currentPingId).equals(pongData);
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
        return RETRY_INTERVAL[prevFailedConnectTries < RETRY_INTERVAL.length ? prevFailedConnectTries : RETRY_INTERVAL.length - 1];
    }

    private int getBatchTime() {
        int requiredTime = TIME_PER_MESSAGE_IN_BATH_MODE * batchSize;
        return (requiredTime < BATCH_MODE_TIMEOUT ? requiredTime : BATCH_MODE_TIMEOUT);
    }

    private int getPingInterval() {
        return PING_INTERVAL[prevSuccessfulPings < PING_INTERVAL.length ? prevSuccessfulPings : PING_INTERVAL.length - 1];
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

    private void internalRefreshIfPossible() {
        BefLog.v(TAG, "internalRefreshIfPossible");
        if (Befrest.Util.isConnectedToInternet(this) && befrest.isBefrestStarted)
            refresh();
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
                internalRefreshIfPossible();
            } else if (Befrest.Util.isConnectedToInternet(PushService.this)) {
                BefLog.v(TAG, "Already connected to internet");
                internalRefreshIfPossible();
                giveSomeTimeToService();
            } else if (Befrest.Util.isWifiEnabled(PushService.this)) {
                Befrest.Util.acquireWifiLock(PushService.this);
                startMonitoringWifiState();
                Befrest.Util.askWifiToConnect(PushService.this);
                waitForConnection();
                stopMonitoringWifiState();
                internalRefreshIfPossible();
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