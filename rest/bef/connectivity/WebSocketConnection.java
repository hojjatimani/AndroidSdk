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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import rest.bef.BefLog;
import rest.bef.Befrest;


public class WebSocketConnection implements WebSocket {
    private static final String TAG = BefLog.TAG_PREF + "WebSocketConnection";


    protected Handler mMasterHandler;
    protected WebSocketReader mReader;
    protected WebSocketWriter mWriter;
    protected HandlerThread mWriterThread;
    protected WebSocketConnector connector;
    protected Socket mTransportChannel;
    private String mWsScheme;
    private String mWsHost;
    private int mWsPort;
    private String mWsPath;
    private String mWsQuery;
    private String[] mWsSubprotocols;
    private List<NameValuePair> mWsHeaders;
    private WebSocket.ConnectionHandler mWsHandler;
    protected WebSocketOptions mOptions;
    private boolean connected;

    private Runnable disconnectIfHandshakeTimeOut = new Runnable() {
        @Override
        public void run() {
            BefLog.v(TAG, "Handshake time out! ");
            disconnectAndNotify(WebSocketConnectionHandler.CLOSE_HANDSHAKE_TIME_OUT, "Server Handshake Time Out.");
        }
    };
    Handler handler = new Handler();

    /**
     * Asynchronous socket connector.
     */
    private class WebSocketConnector extends Thread {

        protected Socket createSocket() throws IOException {
            Socket soc;
            if (mWsScheme.equals("wss")) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
//                SSLSocket secSoc = (SSLSocket) factory.createSocket(mWsHost, mWsPort);


                SSLSocket secSoc = (SSLSocket) factory.createSocket();
                secSoc.setUseClientMode(true);
                secSoc.connect(new InetSocketAddress(mWsHost, mWsPort), mOptions.getSocketConnectTimeout());
//                secSoc.setSoTimeout(mOptions.getSocketReceiveTimeout());
                secSoc.setTcpNoDelay(mOptions.getTcpNoDelay());


                secSoc.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                    public void handshakeCompleted(HandshakeCompletedEvent event) {
                        BefLog.d(TAG, "ssl handshake completed");
                    }
                });
                soc = secSoc;
            } else
                soc = new Socket(mWsHost, mWsPort);
            return soc;
        }

        public void run() {
            BefLog.v(TAG, "connector ------------------------START-------------------");
            Thread.currentThread().setName("WebSocketConnector");

            //HOJJAT: wait a bit
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            /*
             * connect TCP socket
			 */
            try {
                mTransportChannel = createSocket();
            } catch (IOException e) {
                BefLog.e(TAG, e);
                if (mWsHandler != null)
                    mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT,
                            e.getMessage());
                else
                    BefLog.w(TAG, "Befrest Could Not Call OnClose From Connector.");

            } catch (AssertionError e) {
                if (isAndroidGetsocknameError(e)) {
                    BefLog.e(TAG, e);
                    if (mWsHandler != null)
                        mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT,
                                e.getMessage());
                    else
                        BefLog.w(TAG, "Befrest Could Not Call OnClose From Connector.");
                } else {
                    throw e;
                }
            }

            if (isConnected()) {

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

                } catch (Exception e) {
                    BefLog.e(TAG, e);
                    if (mWsHandler != null)
                        mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_INTERNAL_ERROR,
                                e.getMessage());
                    else
                        BefLog.w(TAG, "Befrest Warning! mWsHandler is null where it is expected to be valid!");
                    return;
                }
            } else {
                BefLog.e(TAG, "isConnected returnd false");
                if (mWsHandler != null)
                    mWsHandler.onClose(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT,
                            "Could not connect to WebSocket server");
                else
                    BefLog.w(TAG, "Befrest Warning! mWsHandler is null where it is expected to be valid!");
                return;
            }
            connected = true;
            connector = null;
            BefLog.v(TAG, "mTransportChannel:" + mTransportChannel.toString() + System.identityHashCode(mTransportChannel));
            BefLog.v(TAG, "connector   --------------------------END--------------------");
        }
    }

    public WebSocketConnection() {
        BefLog.v(TAG, "created");
        mMasterHandler = new MasterHandler(new WeakReference<>(this));
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

    public void sendPing(byte[] payload) {
        if (mWriter != null)
            mWriter.forward(new WebSocketMessage.Ping(payload));
        else BefLog.w(TAG, "could not send ping! mWriter is null!");
    }


    public boolean isConnected() {
        BefLog.v(TAG, "isConnected ******Start*****");
        if (mTransportChannel != null) {
            BefLog.v(TAG, "mTransportChannel:" + mTransportChannel.toString() + System.identityHashCode(mTransportChannel));
            BefLog.v(TAG, "mTransportChannel.isConnected() .isClosed" + mTransportChannel.isConnected() + "," + mTransportChannel.isClosed());
        }
        BefLog.v(TAG, "isConnected ******End*****");
        return mTransportChannel != null && mTransportChannel.isConnected() && !mTransportChannel.isClosed();
    }


    private void disconnectAndNotify(int code, String reason) {
        BefLog.v(TAG, "fail connection [code = " + code + ", reason = " + reason);
        closeConnection();
        mWsHandler.onClose(code, reason);
        mWsHandler = null;
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
        if (isConnected()) {
            throw new WebSocketException("already connected");
        }

        // parse WebSockets URI
        //
        try {
            URI mWsUri = new URI(wsUri);

            if (!mWsUri.getScheme().equals("ws") && !mWsUri.getScheme().equals("wss")) {
                throw new WebSocketException("unsupported scheme for WebSockets URI");
            }

            if (mWsUri.getScheme().equals("wss")) {
                BefLog.v(TAG, "WebSocket Url is Secured With SSL!");
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


        // use asynch connector on short-lived background thread
        connector = new WebSocketConnector();
        connector.start();
    }


    public void disconnect() {
        //mWsHandler must be set to null
        mWsHandler = null;
        closeConnection();
        BefLog.v(TAG, "disconnected");
    }

    private void closeConnection() {
        connected = false;
        if (connector != null) {
            try {
                connector.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            BefLog.v(TAG, "connector joind");
        }
        handler.removeCallbacks(disconnectIfHandshakeTimeOut); // it must be here!
        if (mReader != null) {
            mReader.quit();
            BefLog.v(TAG, "mReader.quit();");
        } else BefLog.v(TAG, "mReader was null (mReader.quit();)");
        if (mWriter != null) {
            mWriter.forward(new WebSocketMessage.Close());
            mWriter.forward(new WebSocketMessage.Quit());
            BefLog.v(TAG, "mWriter.forward(new WebSocketMessage.Close() , .Quit());");
        } else
            BefLog.v(TAG, "mWriter was null (mWriter.forward(new WebSocketMessage.Close() , .Quit());)");
        try {
            if (mWriterThread != null) {
                mWriterThread.join();
                BefLog.v(TAG, "mWriterThread joined");
            } else {
                BefLog.d(TAG, "mWriter was null (join)");
            }
            SocketCloser socketCloser = new SocketCloser();
            socketCloser.start();
            socketCloser.join();
            if (mReader != null) {
                mReader.join();
                BefLog.v(TAG, "mReader joined");
            } else {
                BefLog.d(TAG, "mReader was null (join)");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mReader = null;
        mWriter = null;
        mWriterThread = null;
        mTransportChannel = null;
        connector = null;
    }

    class SocketCloser extends Thread {

        @Override
        public void run() {
            Thread.currentThread().setName("SocketCloser");
            if (mTransportChannel != null) {
                try {
                    mTransportChannel.close();
                } catch (IOException e) {
                    BefLog.e(TAG, e);
                } catch (AssertionError e) {
                    if (!isAndroidGetsocknameError(e))
                        throw e;
                }
            } else {
                BefLog.v(TAG, "mTransportChannel already NULL");
            }
        }
    }

    /**
     * Create WebSockets background writer.
     */
    protected void createWriter() {
        mWriterThread = new HandlerThread("WebSocketWriter");
        mWriterThread.start();
        mWriter = new WebSocketWriter(mWriterThread.getLooper(), mMasterHandler, mTransportChannel, mOptions);

        BefLog.v(TAG, "WS writer created and started");
    }


    /**
     * Create WebSockets background reader.
     */
    protected void createReader() {
        if (mReader != null)
            BefLog.e(TAG, "createReader    -    mReader != null");
        mReader = new WebSocketReader(mMasterHandler, mTransportChannel, mOptions, "WebSocketReader");
        mReader.start();
        BefLog.v(TAG, "WS reader created and started");
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
                    BefLog.w(TAG, "could not call onTextMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.RawTextMessage) {

                WebSocketMessage.RawTextMessage rawTextMessage = (WebSocketMessage.RawTextMessage) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onRawTextMessage(rawTextMessage.mPayload);
                } else {
                    BefLog.w(TAG, "could not call onRawTextMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.BinaryMessage) {

                WebSocketMessage.BinaryMessage binaryMessage = (WebSocketMessage.BinaryMessage) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onBinaryMessage(binaryMessage.mPayload);
                } else {
                    BefLog.w(TAG, "could not call onBinaryMessage() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.Ping) {

                WebSocketMessage.Ping ping = (WebSocketMessage.Ping) msg.obj;
                BefLog.v(TAG, "WebSockets Ping received");

                // reply with Pong
                WebSocketMessage.Pong pong = new WebSocketMessage.Pong();
                pong.mPayload = ping.mPayload;
                wsConnection.mWriter.forward(pong);

            } else if (msg.obj instanceof WebSocketMessage.Pong) {

                WebSocketMessage.Pong pong = (WebSocketMessage.Pong) msg.obj;

                if (wsConnection.mWsHandler != null) {
                    wsConnection.mWsHandler.onPong(pong.mPayload);
                } else {
                    BefLog.w(TAG, "could not call onPong() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.Close) {

                WebSocketMessage.Close close = (WebSocketMessage.Close) msg.obj;

                BefLog.v(TAG, "WebSockets Close received (" + close.mCode + " - " + close.mReason + ")");

                final int closeCode = (close.mCode == 1000) ? ConnectionHandler.CLOSE_NORMAL : ConnectionHandler.CLOSE_CONNECTION_LOST;
                if (wsConnection.mWsHandler != null) {
                    wsConnection.disconnectAndNotify(closeCode, close.mReason);
                } else {
                    BefLog.w(TAG, "could not call onClose() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.ServerHandshake) {

                WebSocketMessage.ServerHandshake serverHandshake = (WebSocketMessage.ServerHandshake) msg.obj;
                BefLog.v(TAG, "opening handshake received");
                wsConnection.handler.removeCallbacks(wsConnection.disconnectIfHandshakeTimeOut);

                if (serverHandshake.mSuccess) {
                    if (wsConnection.mWsHandler != null) {
                        if (wsConnection.connected)
                            wsConnection.mWsHandler.onOpen();
                    } else {
                        BefLog.w(TAG, "could not call onOpen() .. handler already NULL");
                    }
                } else {
                    BefLog.w(TAG, "could not call onOpen() .. serverHandshake was not successful");
                }

            } else if (msg.obj instanceof WebSocketMessage.ConnectionLost) {

                @SuppressWarnings("unused")
                WebSocketMessage.ConnectionLost connnectionLost = (WebSocketMessage.ConnectionLost) msg.obj;
                if (wsConnection.mWsHandler != null) {
                    BefLog.d(TAG, "failConnectionWillBeCalled");
                    wsConnection.disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CONNECTION_LOST, "WebSockets connection lost");
                } else {
                    BefLog.w(TAG, "could not call disconnectAndNotify() .. handler already NULL");
                }


            } else if (msg.obj instanceof WebSocketMessage.ProtocolViolation) {

                @SuppressWarnings("unused")
                WebSocketMessage.ProtocolViolation protocolViolation = (WebSocketMessage.ProtocolViolation) msg.obj;
                if (wsConnection.mWsHandler != null) {
                    BefLog.d(TAG, "failConnectionWillBeCalled");
                    wsConnection.disconnectAndNotify(WebSocketConnectionHandler.CLOSE_PROTOCOL_ERROR, "WebSockets protocol violation");
                } else {
                    BefLog.w(TAG, "could not call disconnectAndNotify() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.Error) {

                WebSocketMessage.Error error = (WebSocketMessage.Error) msg.obj;
                BefLog.e(TAG, error.mException);
                if (wsConnection.mWsHandler != null) {
                    BefLog.d(TAG, "failConnectionWillBeCalled");
                    wsConnection.disconnectAndNotify(WebSocketConnectionHandler.CLOSE_INTERNAL_ERROR, "WebSockets internal error (" + error.mException.toString() + ")");
                } else {
                    BefLog.w(TAG, "could not call disconnectAndNotify() .. handler already NULL");
                }

            } else if (msg.obj instanceof WebSocketMessage.ServerError) {

                WebSocketMessage.ServerError error = (WebSocketMessage.ServerError) msg.obj;
                int errCode = WebSocketConnectionHandler.CLOSE_SERVER_ERROR;
                if (error.mStatusCode == 401)
                    errCode = WebSocketConnectionHandler.CLOSE_UNAUTHORIZED; //hojjat
                if (wsConnection.mWsHandler != null) {
                    BefLog.d(TAG, "failConnectionWillBeCalled");
                    wsConnection.disconnectAndNotify(errCode, "Server error " + error.mStatusCode + " (" + error.mStatusMessage + ")");
                } else {
                    BefLog.w(TAG, "could not call disconnectAndNotify() .. handler already NULL");
                }

            }
        }
    }

    /**
     * Returns true if {@code e} is due to a firmware bug fixed after Android 4.2.2.
     * https://code.google.com/p/android/issues/detail?id=54072
     */
    static boolean isAndroidGetsocknameError(AssertionError e) {
        return e.getCause() != null && e.getMessage() != null
                && e.getMessage().contains("getsockname failed");
    }
}
