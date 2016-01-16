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

package rest.bef.connectivity;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SocketChannel;
import java.util.List;

import rest.bef.FileLog;


public class WebSocketConnection implements WebSocket {
    private static final String TAG = "WebSocketConnection";

    protected Handler mMasterHandler;
    protected WebSocketReader mReader;
    protected WebSocketWriter mWriter;
    protected HandlerThread mWriterThread;

    protected SocketChannel mTransportChannel;

    private URI mWsUri;
    private String mWsScheme;
    private String mWsHost;
    private int mWsPort;
    private String mWsPath;
    private String mWsQuery;
    private String[] mWsSubprotocols;
    private List<NameValuePair> mWsHeaders;

    private WebSocket.ConnectionHandler mWsHandler;

    protected WebSocketOptions mOptions;

    private boolean mActive;
    private boolean mPrevConnected;
    private boolean disconnected;

    private Runnable disconnectIfHandshakeTimeOut = new Runnable() {
        @Override
        public void run() {
            FileLog.d(TAG, "Handshake time out! ");
            failConnection(WebSocketConnectionHandler.CLOSE_HANDSHAKE_TIME_OUT, "Server Handshake Time Out.");
        }
    };
    Handler handler = new Handler();

    /**
     * Asynchronous socket connector.
     */
    private class WebSocketConnector extends Thread {

        public void run() {
            Thread.currentThread().setName("WebSocketConnector");

			/*
             * connect TCP socket
			 */
            try {
                mTransportChannel = SocketChannel.open();

                // the following will block until connection was established or
                // an error occurred!
                mTransportChannel.socket().connect(
                        new InetSocketAddress(mWsHost, mWsPort),
                        mOptions.getSocketConnectTimeout());

                // before doing any data transfer on the socket, set socket
                // options
                mTransportChannel.socket().setSoTimeout(
                        mOptions.getSocketReceiveTimeout());
                mTransportChannel.socket().setTcpNoDelay(
                        mOptions.getTcpNoDelay());

            } catch (IOException e) {
                mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT,
                        e.getMessage());
                return;
            }

            if (mTransportChannel.isConnected()) {

                try {

                    // create & start WebSocket reader
                    createReader();

                    // create & start WebSocket writer
                    createWriter();

                    // start WebSockets handshake
                    WebSocketMessage.ClientHandshake hs = new WebSocketMessage.ClientHandshake(
                            mWsHost + ":" + mWsPort);
                    hs.mPath = mWsPath;
                    hs.mQuery = mWsQuery;
                    hs.mSubprotocols = mWsSubprotocols;
                    hs.mHeaderList = mWsHeaders;
                    mWriter.forward(hs);

                    handler.postDelayed(disconnectIfHandshakeTimeOut, 7 * 1000);

                    mPrevConnected = true;

                } catch (Exception e) {
                    mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_INTERNAL_ERROR,
                            e.getMessage());
                    return;
                }
            } else {
                mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT,
                        "Could not connect to WebSocket server");
                return;
            }
        }

    }

    public WebSocketConnection() {
        FileLog.d(TAG, "created");

        mMasterHandler = new MasterHandler(new WeakReference<WebSocketConnection>(this));

        // set initial values
        mActive = false;
        mPrevConnected = false;
    }


    public void sendTextMessage(String payload) {
        mWriter.forward(new WebSocketMessage.TextMessage(payload));
    }


    public void sendRawTextMessage(byte[] payload) {
        mWriter.forward(new WebSocketMessage.RawTextMessage(payload));
    }


    public void sendBinaryMessage(byte[] payload) {
        mWriter.forward(new WebSocketMessage.BinaryMessage(payload));
    }


    public boolean isConnected() {
        return mTransportChannel != null && mTransportChannel.isConnected();
    }


    private void failConnection(int code, String reason) {
        FileLog.d(TAG, "fail connection [code = " + code + ", reason = " + reason);
        if (mReader != null) {
            mReader.quit();
            try {
                mReader.join();
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            }
            mReader = null;
        } else {
            FileLog.d(TAG, "mReader already NULL");
        }

        if (mWriter != null) {
            mWriter.forward(new WebSocketMessage.Quit());
            try {
                mWriterThread.join();
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            }
            mWriter = null;
            mWriterThread = null;
        } else {
            FileLog.d(TAG, "mWriter already NULL");
        }

        if (mTransportChannel != null) {
            try {
                mTransportChannel.close();
            } catch (IOException e) {
                FileLog.e(TAG, e);
            }
            mTransportChannel = null;
        } else {
            FileLog.d(TAG, "mTransportChannel already NULL");
        }

        mWsHandler.onClose(code, reason);

        FileLog.d(TAG, "worker threads stopped");
    }


    public void connect(String wsUri, WebSocket.ConnectionHandler wsHandler) throws WebSocketException {
        connect(wsUri, null, wsHandler, new WebSocketOptions(), null);
    }

    public void connect(String wsUri, WebSocket.ConnectionHandler wsHandler, List<NameValuePair> headers) throws WebSocketException {
        connect(wsUri, null, wsHandler, new WebSocketOptions(), headers);
    }


    public void connect(String wsUri, WebSocket.ConnectionHandler wsHandler, WebSocketOptions options) throws WebSocketException {
        connect(wsUri, null, wsHandler, options, null);
    }


    public void connect(String wsUri, String[] wsSubprotocols, WebSocket.ConnectionHandler wsHandler, WebSocketOptions options, List<NameValuePair> headers) throws WebSocketException {

        // don't connect if already connected .. user needs to disconnect first
        //
        if (mTransportChannel != null && mTransportChannel.isConnected()) {
            throw new WebSocketException("already connected");
        }

        // parse WebSockets URI
        //
        try {
            mWsUri = new URI(wsUri);

            if (!mWsUri.getScheme().equals("ws") && !mWsUri.getScheme().equals("wss")) {
                throw new WebSocketException("unsupported scheme for WebSockets URI");
            }

            if (mWsUri.getScheme().equals("wss")) {
                throw new WebSocketException("secure WebSockets not implemented");
            }

            mWsScheme = mWsUri.getScheme();

            if (mWsUri.getPort() == -1) {
                if (mWsScheme.equals("ws")) {
                    mWsPort = 80;
                } else {
                    mWsPort = 443;
                }
            } else {
                mWsPort = mWsUri.getPort();
            }

            if (mWsUri.getHost() == null) {
                throw new WebSocketException("no host specified in WebSockets URI");
            } else {
                mWsHost = mWsUri.getHost();
            }

            if (mWsUri.getRawPath() == null || mWsUri.getRawPath().equals("")) {
                mWsPath = "/";
            } else {
                mWsPath = mWsUri.getRawPath();
            }

            if (mWsUri.getRawQuery() == null || mWsUri.getRawQuery().equals("")) {
                mWsQuery = null;
            } else {
                mWsQuery = mWsUri.getRawQuery();
            }

        } catch (URISyntaxException e) {

            throw new WebSocketException("invalid WebSockets URI");
        }

        mWsSubprotocols = wsSubprotocols;
        mWsHeaders = headers;
        mWsHandler = wsHandler;

        // make copy of options!
        mOptions = new WebSocketOptions(options);

        // set connection active
        mActive = true;

        // use asynch connector on short-lived background thread
        new WebSocketConnector().start();
    }


    public void disconnect() {
        mActive = false;
        mPrevConnected = false;
        disconnected = true;
        if (mReader != null) {
            mReader.quit();
            try {
                mReader.join();
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            }
            mReader = null;
        } else {
            FileLog.d(TAG, "mReader already NULL");
        }

        if (mWriter != null) {
            mWriter.forward(new WebSocketMessage.Close());
            mWriter.forward(new WebSocketMessage.Quit());
            try {
                mWriterThread.join();
            } catch (InterruptedException e) {
                FileLog.e(TAG, e);
            }
            mWriter = null;
            mWriterThread = null;
        } else {
            FileLog.d(TAG, "mWriter already NULL");
        }

        if (mTransportChannel != null) {
            try {
                mTransportChannel.close();
            } catch (IOException e) {
                FileLog.e(TAG, e);
            }
            mTransportChannel = null;
        } else {
            FileLog.d(TAG, "mTransportChannel already NULL");
        }
        FileLog.d(TAG, "disconnected");
    }

    /**
     * Reconnect to the server with the latest options
     *
     * @return true if reconnection performed
     */
    public boolean reconnect() {
        if (!isConnected() && (mWsUri != null)) {
            new WebSocketConnector().start();
            return true;
        }
        return false;
    }

    /**
     * Perform reconnection
     *
     * @return true if reconnection was scheduled
     */
    protected boolean scheduleReconnect() {
        /**
         * Reconnect only if:
         *  - connection active (connected but not disconnected)
         *  - has previous success connections
         *  - reconnect interval is set
         */
        int interval = mOptions.getReconnectInterval();
        boolean need = mActive && mPrevConnected && (interval > 0);
        if (need) {
            FileLog.d(TAG, "Reconnection scheduled");
            mMasterHandler.postDelayed(new Runnable() {

                public void run() {
                    FileLog.d(TAG, "Reconnecting...");
                    reconnect();
                }
            }, interval);
        }
        return need;
    }


    protected void processAppMessage(Object message) {
    }


    /**
     * Create WebSockets background writer.
     */
    protected void createWriter() {
        mWriterThread = new HandlerThread("WebSocketWriter");
        mWriterThread.start();
        mWriter = new WebSocketWriter(mWriterThread.getLooper(), mMasterHandler, mTransportChannel, mOptions);

        FileLog.d(TAG, "WS writer created and started");
    }


    /**
     * Create WebSockets background reader.
     */
    protected void createReader() {

        mReader = new WebSocketReader(mMasterHandler, mTransportChannel, mOptions, "WebSocketReader");
        mReader.start();
        FileLog.d(TAG, "WS reader created and started");
    }

    private static class MasterHandler extends Handler {
        WebSocketConnection wsConnection;

        public MasterHandler(WeakReference<WebSocketConnection> weakReference) {
            super(Looper.getMainLooper());
            wsConnection = weakReference.get();
        }

        @Override
        public void handleMessage(Message msg) {

            if (msg.obj instanceof WebSocketMessage.TextMessage) {

                WebSocketMessage.TextMessage textMessage = (WebSocketMessage.TextMessage) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onTextMessage(textMessage.mPayload);
                } else {
                    FileLog.d(TAG, "could not call onTextMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.RawTextMessage) {

                WebSocketMessage.RawTextMessage rawTextMessage = (WebSocketMessage.RawTextMessage) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onRawTextMessage(rawTextMessage.mPayload);
                } else {
                    FileLog.d(TAG, "could not call onRawTextMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.BinaryMessage) {

                WebSocketMessage.BinaryMessage binaryMessage = (WebSocketMessage.BinaryMessage) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onBinaryMessage(binaryMessage.mPayload);
                } else {
                    FileLog.d(TAG, "could not call onBinaryMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.Ping) {

                WebSocketMessage.Ping ping = (WebSocketMessage.Ping) msg.obj;
                FileLog.d(TAG, "WebSockets Ping received");

                // reply with Pong
                WebSocketMessage.Pong pong = new WebSocketMessage.Pong();
                pong.mPayload = ping.mPayload;
                wsConnection.mWriter.forward(pong);

            } else if (msg.obj instanceof WebSocketMessage.Pong) {

                @SuppressWarnings("unused")
                WebSocketMessage.Pong pong = (WebSocketMessage.Pong) msg.obj;

                FileLog.d(TAG, "WebSockets Pong received");

            } else if (msg.obj instanceof WebSocketMessage.Close) {

                WebSocketMessage.Close close = (WebSocketMessage.Close) msg.obj;

                FileLog.d(TAG, "WebSockets Close received (" + close.mCode + " - " + close.mReason + ")");

                final int closeCode = (close.mCode == 1000) ? ConnectionHandler.CLOSE_NORMAL : ConnectionHandler.CLOSE_CONNECTION_LOST;
                wsConnection.disconnect();
                wsConnection.mWsHandler.onClose(closeCode, close.mReason);

            } else if (msg.obj instanceof WebSocketMessage.ServerHandshake) {

                WebSocketMessage.ServerHandshake serverHandshake = (WebSocketMessage.ServerHandshake) msg.obj;
                FileLog.d(TAG, "opening handshake received");
                wsConnection.handler.removeCallbacks(wsConnection.disconnectIfHandshakeTimeOut);

                if (serverHandshake.mSuccess) {
                    if (wsConnection.mWsHandler != null) {
                        wsConnection.mWsHandler.onOpen();
                    } else {
                        FileLog.d(TAG, "could not call onOpen() .. handler already NULL");
                    }
                }

            } else if (msg.obj instanceof WebSocketMessage.ConnectionLost) {

                @SuppressWarnings("unused")
                WebSocketMessage.ConnectionLost connnectionLost = (WebSocketMessage.ConnectionLost) msg.obj;
                wsConnection.failConnection(WebSocketConnectionHandler.CLOSE_CONNECTION_LOST, "WebSockets connection lost");

            } else if (msg.obj instanceof WebSocketMessage.ProtocolViolation) {

                @SuppressWarnings("unused")
                WebSocketMessage.ProtocolViolation protocolViolation = (WebSocketMessage.ProtocolViolation) msg.obj;
                wsConnection.failConnection(WebSocketConnectionHandler.CLOSE_PROTOCOL_ERROR, "WebSockets protocol violation");

            } else if (msg.obj instanceof WebSocketMessage.Error) {

                WebSocketMessage.Error error = (WebSocketMessage.Error) msg.obj;
                wsConnection.failConnection(WebSocketConnectionHandler.CLOSE_INTERNAL_ERROR, "WebSockets internal error (" + error.mException.toString() + ")");

            } else if (msg.obj instanceof WebSocketMessage.ServerError) {

                WebSocketMessage.ServerError error = (WebSocketMessage.ServerError) msg.obj;
                int errCode = WebSocketConnectionHandler.CLOSE_SERVER_ERROR;
                if (error.mStatusCode == 401)
                    errCode = WebSocketConnectionHandler.CLOSE_UNAUTHORIZED; //hojjat
                wsConnection.failConnection(errCode, "Server error " + error.mStatusCode + " (" + error.mStatusMessage + ")");

            } else {
                wsConnection.processAppMessage(msg.obj);
            }
        }
    }
}
