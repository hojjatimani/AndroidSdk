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
import java.net.HttpURLConnection;
import java.net.URL;
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

    //service wakeup variables and constants
    private static final int PUSH_SYNC_TIMEOUT = 45 * 1000;
    private static final int CONNECT_TIMEOUT = 45 * 1000;
    private volatile static CountDownLatch latch;
    private LocalWifiStateChangeReceiver wifiStateChangeListener;

    //number of previous successful pings in current connection
    private int prevSuccessfulPings;

    //number of previous failed retry attempts
    private int prevFaildConnectTries;

    //last time a ping was set to be sent delayed
    private long lastPingSetTime;
    private long lastAcceptedRefreshRequestTime;

    //constants
    private static final int[] pingInterval = {10 * 1000, 30 * 1000, 50 * 1000, 70 * 1000, 100 * 1000};
    private static final int[] retryInterval = {0, 5 * 1000, 10 * 1000, 25 * 1000, 50 * 1000};
    private static final int PING_TIMEOUT = 10 * 1000;
    private static final int BATCH_MODE_TIMEOUT = 5 * 1000;
    private int PING_ID = 0;
    private boolean retryInProgress;
    private boolean restartInProgress;
    private boolean refreshIsRequested;
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
            prevFaildConnectTries = prevSuccessfulPings = 0;
            notifyConnectionRefreshedIfNeeded();
            setNextPingToSendInFuture();
        }

        @Override
        public void onTextMessage(String message) {
            BefrestMessage msg = new BefrestMessage(message);
            FileLog.d(TAG, "Got Notif:: " + msg.type + "  " + msg);
            switch (msg.type) {
                case DATA:
                    if (msg.data.startsWith("PONG")) {
                        onPong(msg.data);
                        return;
                    }
                    messages.add(msg);
                    if (!isBachReceiveMode) {
                        sendBefrestBroadcast(Befrest.ACTION_PUSH_RECIEVED);
                        revisePinging();
                    }
                    break;
                case BATCH:
                    isBachReceiveMode = true;
                    handler.postDelayed(finishBatchMode, BATCH_MODE_TIMEOUT);
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
                    sendBefrestBroadcast(Befrest.ACTION_UNAUTHORIZED);
                    stopSelf();
                    break;
                case CLOSE_RECONNECT:
                    //reconnection handled in autobahn
                    break;
                case CLOSE_CANNOT_CONNECT:
                case CLOSE_CONNECTION_LOST:
                case CLOSE_INTERNAL_ERROR:
                case CLOSE_NORMAL:
                case CLOSE_PROTOCOL_ERROR:
                case CLOSE_SERVER_ERROR:
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
            isBachReceiveMode = false;
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
                refresh(true);
                break;
            case NETWORK_DISCONNECTED:
                cancelALLPendingIntents();
                break;
            case WAKEUP:
                new WakeSeviceUp().start();
        }
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        FileLog.d(TAG, "onDestroy()");
        terminateConnection();
        unRegisterScreenStateBroadCastReceiver();
        super.onDestroy();
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
        prevFaildConnectTries++;
        int interval = getNextReconnectInterval();
        FileLog.d(TAG, "scheduled    interval : " + interval);
        handler.postDelayed(retry, getNextReconnectInterval());
        retryInProgress = true;
    }

    private void notifyConnectionRefreshedIfNeeded() {
        if (refreshIsRequested) {
            FileLog.d(TAG, "notifyRefreshIfNeeded");
            sendBefrestBroadcast(Befrest.ACTION_CONNECTION_REFRESHED);
            refreshIsRequested = false;
            lastAcceptedRefreshRequestTime = 0;
        }
    }

    private void terminateConnection() {
        FileLog.d(TAG, "terminateConnection()");
        mConnection.disconnect();
        cancelALLPendingIntents();
    }

    private void connectIfNeeded() {
        FileLog.d(TAG, "connectIfNeeded()");
        if (shouldConnect())
            try {
                connecting = true;
                FileLog.d(TAG, "connecting ...");
                mConnection.connect(Befrest.Util.getSubscribeUri(this), wscHandler);
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

    private void sendBefrestBroadcast(String action) {
        Intent intent = new Intent(action);
        if (action.equals(Befrest.ACTION_PUSH_RECIEVED)) {
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
        new SendPing("PONG" + PING_ID).start();
//        Befrest.sendMessage("PONG" + PING_ID);
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
        return Integer.parseInt(pongData.charAt(pongData.length() - 1) + "") == PING_ID;
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
        return retryInterval[prevFaildConnectTries < retryInterval.length ? prevFaildConnectTries : retryInterval.length - 1];
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

    private void refresh(boolean isUserRequest) {
        if (!Befrest.Util.isConnectedToInternet(this) || (System.currentTimeMillis() - lastAcceptedRefreshRequestTime < PING_TIMEOUT))
            return;
        lastAcceptedRefreshRequestTime = System.currentTimeMillis();
        refreshIsRequested = isUserRequest;
        if (connecting) {
            FileLog.d(TAG, "is connecting right now!");
            //TODO we must have a time out for connecting
        } else if (shouldConnect()) {
            cancelALLPendingIntents();
            connectIfNeeded();
        } else if (retryInProgress) {
            cancelFutureRetry();
            handler.post(retry);
        } else if (restartInProgress) {
            cancelUpcommingRestart();
            handler.post(restart);
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

    private class SendPing extends Thread {
        public final String TAG = SendPing.class.getSimpleName();
        private String pingData;

        SendPing(String data) {
            pingData = data;
        }

        @Override
        public void run() {
            FileLog.d(TAG, "sendingPing...");
            if (Befrest.Util.isConnectedToInternet(PushService.this))
                try {
                    Thread.sleep(500); //sleep a bit to ensure handlers for waiting for pong are ready
                    URL url = new URL(Befrest.Util.getPingUrl(PushService.this));
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("connection", "close");
                    conn.setDoInput(true);
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5 * 1000);
                    conn.connect();
                    //wait to connect
                    conn.getOutputStream().write(pingData.getBytes());
                    conn.getOutputStream().flush();
                    conn.getOutputStream().close();
                    FileLog.d(TAG, "pingSendStatus: " + conn.getResponseCode() + " " + conn.getResponseMessage());
                    conn.disconnect();
                } catch (Exception ex) {
                    FileLog.e(TAG, ex);
                }
        }
    }

    private class WakeSeviceUp extends Thread {
        @Override
        public void run() {
            try {
                if (Befrest.Util.isUserInteractive(PushService.this)) {
                    FileLog.d(TAG, "user is interactive. most likely " + "device is not asleep");
                    refresh(false);
                } else if (Befrest.Util.isConnectedToInternet(PushService.this)) {
                    FileLog.d(TAG, "already connected to internet");
                    refresh(false);
                } else if (Befrest.Util.isWifiEnabled(PushService.this)) {
                    Befrest.Util.acquireWifiLock(PushService.this);
                    startMonitoringWifiState();
                    Befrest.Util.askWifiToConnect(PushService.this);
                    waitForConnection();
                    stopMonitoringWifiState();
                    boolean connectedToInternet = Befrest.Util.isConnectedToInternet(PushService.this);
                    FileLog.m(TAG, "isConnectedToInternet", connectedToInternet);
                    if (Befrest.Util.isWifiConnectedOrConnecting(PushService.this) || connectedToInternet) {
                        refresh(false);
                        //give PushService a time to work
                        Thread.sleep(PUSH_SYNC_TIMEOUT);
                    }
                    Befrest.Util.releaseWifiLock();
                } else {
                    FileLog.d(TAG, "wifi is not enabled");
                }
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            } finally {
                Befrest.Util.releaseWakeLock();
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