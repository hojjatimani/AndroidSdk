package rest.bef;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.List;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Created by hojjatimani on 2/25/2016 AD.
 */
public class BefrestConnection extends Handler {
    private static final String TAG = BefLog.TAG_PREF + "BefrestConnection";

    Looper mLooper;
    Context mContext;

    protected WebSocketReader mReader;
    protected WebSocketWriter mWriter;
    protected HandlerThread mWriterThread;
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

    private boolean refreshRequested;

    private Runnable disconnectIfWebSocketHandshakeTimeOut = new Runnable() {
        @Override
        public void run() {
            disconnectAndNotify(WebSocketConnectionHandler.CLOSE_HANDSHAKE_TIME_OUT, "Server Handshake Not Received After " + SERVER_HANDSHAKE_TIMEOUT + "ms");
        }
    };
    private static final int SERVER_HANDSHAKE_TIMEOUT = 7 * 1000;

    //pinging variables and constants
    private static final int[] PING_INTERVAL = {120 * 1000, 300 * 1000, 480 * 1000};
    private static final int PING_TIMEOUT = 5 * 1000;
    private static final String PING_DATA_PREFIX = String.valueOf((int) (Math.random() * 9999));
    private int currentPingId = 0;
    private int prevSuccessfulPings;
    private long lastPingSetTime; //last time a ping was set to be sent delayed

    private boolean restartInProgress;

    private Runnable sendPing = new Runnable() {
        @Override
        public void run() {
            sendPing();
        }
    };
    private Runnable restart = new Runnable() {
        @Override
        public void run() {
            disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CONNECTION_NOT_RESPONDING, "connection did not respond to ping message after " + PING_TIMEOUT + "ms");
        }
    };

    private void sendPing() {
        if (mWriter != null) {
            BefLog.d(TAG, "Sending Ping ...");
            postDelayed(restart, PING_TIMEOUT);
            restartInProgress = true;
            currentPingId = (currentPingId + 1) % 5;
            String payload = PING_DATA_PREFIX + currentPingId;
            mWriter.forward(new WebSocketMessage.Ping(payload.getBytes(Charset.defaultCharset())));
        } else BefLog.e(TAG, "could not send ping! writer is null");
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

    private void cancelFuturePing() {
        BefLog.v(TAG, "cancelFuturePing()");
        removeCallbacks(sendPing);
    }

    private void cancelUpcommingRestart() {
        BefLog.v(TAG, "cancelUpcommingRestart()");
        removeCallbacks(restart);
        restartInProgress = false;
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

    private void setNextPingToSendInFuture() {
        setNextPingToSendInFuture(getPingInterval());
    }

    private void setNextPingToSendInFuture(int interval) {
//        cancelFuturePing(); //cancel any previous ping set
        BefLog.v(TAG, "setNextPingToSendInFuture()  interval : " + interval);
        lastPingSetTime = System.currentTimeMillis();
        postDelayed(sendPing, interval);
    }


    public BefrestConnection(Context context , Looper looper, WebSocket.ConnectionHandler wsHandler, String url, List<NameValuePair> headers) {
        super(looper);
        this.mLooper = looper;
        this.mWsHandler = wsHandler;
        this.mContext = context;
        parseWebsocketUri(url, headers);
    }

    @Override
    public void handleMessage(Message msg) {
        try {
            if (msg.obj instanceof WebSocketMessage.Message) {
                //msg from reader and writer
                handleMsgFromReaderWriter(((WebSocketMessage.Message) msg.obj));
            } else if (msg.obj instanceof BefrestEvent) {
                //msg from push service
                handleBefrestEvent(((BefrestEvent) msg.obj));
            } else {
                //unknown! should not come here!
            }
        }catch (Exception e){
            BefLog.e(TAG, "unExpected Exception!");
            //TODO report
            throw  e;
        }
    }

    public void forward(Object message) {
        Message msg = obtainMessage();
        msg.obj = message;
        sendMessage(msg);
    }

    public void handleMsgFromReaderWriter(WebSocketMessage.Message msg) {
        if (msg instanceof WebSocketMessage.TextMessage) {
            WebSocketMessage.TextMessage textMessage = (WebSocketMessage.TextMessage) msg;

            revisePinging();
            mWsHandler.onTextMessage(textMessage.mPayload);

        } else if (msg instanceof WebSocketMessage.RawTextMessage) {

            WebSocketMessage.RawTextMessage rawTextMessage = (WebSocketMessage.RawTextMessage) msg;

            mWsHandler.onRawTextMessage(rawTextMessage.mPayload);

        } else if (msg instanceof WebSocketMessage.BinaryMessage) {

            WebSocketMessage.BinaryMessage binaryMessage = (WebSocketMessage.BinaryMessage) msg;
            mWsHandler.onBinaryMessage(binaryMessage.mPayload);

        } else if (msg instanceof WebSocketMessage.Ping) {

            WebSocketMessage.Ping ping = (WebSocketMessage.Ping) msg;
            BefLog.v(TAG, "WebSockets Ping received");

            // reply with Pong
            WebSocketMessage.Pong pong = new WebSocketMessage.Pong();
            pong.mPayload = ping.mPayload;
            if (mWriter != null) mWriter.forward(pong);

        } else if (msg instanceof WebSocketMessage.Pong) {
            WebSocketMessage.Pong pong = (WebSocketMessage.Pong) msg;
            if (isMessageFromValidReaderWriter(msg))
                onPong(new String(pong.mPayload, Charset.defaultCharset()));

        } else if (msg instanceof WebSocketMessage.Close) {

            WebSocketMessage.Close close = (WebSocketMessage.Close) msg;
            BefLog.v(TAG, "WebSockets Close received (" + close.mCode + " - " + close.mReason + ")");
            final int closeCode = (close.mCode == 1000) ? WebSocket.ConnectionHandler.CLOSE_NORMAL : WebSocket.ConnectionHandler.CLOSE_CONNECTION_LOST;
            if (isMessageFromValidReaderWriter(msg))
                disconnectAndNotify(closeCode, close.mReason);
        } else if (msg instanceof WebSocketMessage.ServerHandshake) {

            WebSocketMessage.ServerHandshake serverHandshake = (WebSocketMessage.ServerHandshake) msg;
            BefLog.v(TAG, "opening handshake received");
            removeCallbacks(disconnectIfWebSocketHandshakeTimeOut);

            if (serverHandshake.mSuccess) {
                if (isMessageFromValidReaderWriter(msg)) {
                    mWsHandler.onOpen();
                    notifyConnectionRefreshedIfNeeded();
                    prevSuccessfulPings = 0;
                    setNextPingToSendInFuture();
                }
            } else {
                BefLog.w(TAG, "could not call onOpen() .. serverHandshake was not successful");
            }

        } else if (msg instanceof WebSocketMessage.ConnectionLost) {
            @SuppressWarnings("unused")
            WebSocketMessage.ConnectionLost connnectionLost = (WebSocketMessage.ConnectionLost) msg;
            if (isMessageFromValidReaderWriter(msg))
                disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CONNECTION_LOST, "WebSockets connection lost");
        } else if (msg instanceof WebSocketMessage.ProtocolViolation) {

            @SuppressWarnings("unused")
            WebSocketMessage.ProtocolViolation protocolViolation = (WebSocketMessage.ProtocolViolation) msg;
            if (isMessageFromValidReaderWriter(msg)) {
                disconnectAndNotify(WebSocketConnectionHandler.CLOSE_PROTOCOL_ERROR, "WebSockets protocol violation");
            }
        } else if (msg instanceof WebSocketMessage.Error) {

            WebSocketMessage.Error error = (WebSocketMessage.Error) msg;
            BefLog.e(TAG, error.mException);
            if (isMessageFromValidReaderWriter(msg))
                disconnectAndNotify(WebSocketConnectionHandler.CLOSE_INTERNAL_ERROR, "WebSockets internal error (" + error.mException.toString() + ")");

        } else if (msg instanceof WebSocketMessage.ServerError) {

            WebSocketMessage.ServerError error = (WebSocketMessage.ServerError) msg;
            int errCode = WebSocketConnectionHandler.CLOSE_SERVER_ERROR;
            if (error.mStatusCode == 401)
                errCode = WebSocketConnectionHandler.CLOSE_UNAUTHORIZED;
            if (isMessageFromValidReaderWriter(msg))
                disconnectAndNotify(errCode, "Server error " + error.mStatusCode + " (" + error.mStatusMessage + ")");
        }
    }

    private void disconnectAndNotify(int code, String reason) {
        BefLog.v(TAG, "disconnectAndNotify:" + code + " , " + reason);
        disconnect();
        mWsHandler.onClose(code, reason);
    }

    public void handleBefrestEvent(BefrestEvent e) {
        switch (e.type) {
            case CONNECT:
                connect();
                break;
            case DISCONNECT:
                disconnect();
                break;
            case STOP:
                mLooper.quit();
                break;
            case REFRESH:
                refresh();
        }
    }

    private void refresh() {
        refreshRequested = true;
        if (isConnected()) {
            prevSuccessfulPings = 0;
            cancelFuturePing();
            cancelUpcommingRestart();
            setNextPingToSendInFuture(0);
        } else {
            BefLog.v(TAG, "refresh received when socket is not connected. will connect...");
            connect();
        }
    }

    private void notifyConnectionRefreshedIfNeeded() {
        if (refreshRequested) {
            refreshRequested = false;
            mWsHandler.onConnectionRefreshed();
        }
    }

    public void connect() {
        BefLog.v(TAG , "--------------------------connect()_START--------------------");
        if (isConnected()) {
            BefLog.v(TAG, "already connected!");
        }else if (mContext != null && !Befrest.Util.isConnectedToInternet(mContext)){
            BefLog.v(TAG, "no internet connection!");
        }
        else {
            waitABit();
            try {
                mTransportChannel = createSocket();
                if (isConnected()) {
                    createReader();
                    createWriter();
                    startWebSocketHandshake();
                    postDelayed(disconnectIfWebSocketHandshakeTimeOut, SERVER_HANDSHAKE_TIMEOUT);
                } else {
                    disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT, "Could not connect to WebSocket server");
                }
            } catch (IOException e) {
                BefLog.e(TAG, e);
                disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT, e.getMessage());
            } catch (Exception e) {
                BefLog.e(TAG, e);
                disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT, e.getMessage());
                //TODO report
            } catch (AssertionError e) {
                if (isAndroidGetsocknameError(e))
                    disconnectAndNotify(WebSocketConnectionHandler.CLOSE_CANNOT_CONNECT, e.getMessage());
                else
                    throw e;
            }
        }
        BefLog.v(TAG, "--------------------------connect()_END--------------------");
    }

    private void waitABit() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startWebSocketHandshake() {
        WebSocketMessage.ClientHandshake hs = new WebSocketMessage.ClientHandshake(
                mWsHost + ":" + mWsPort);
        hs.mPath = mWsPath;
        hs.mQuery = mWsQuery;
        hs.mSubprotocols = mWsSubprotocols;
        hs.mHeaderList = mWsHeaders;
        mWriter.forward(hs);
    }

    private Socket createSocket() throws IOException {
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

    /**
     * Create WebSockets background writer.
     */
    protected void createWriter() {
        mWriterThread = new HandlerThread("WebSocketWriter");
        mWriterThread.start();
        mWriter = new WebSocketWriter(mWriterThread.getLooper(), this, mTransportChannel, mOptions);
        BefLog.v(TAG, "WS writer created and started");
    }


    /**
     * Create WebSockets background reader.
     */
    protected void createReader() {
        mReader = new WebSocketReader(this, mTransportChannel, mOptions, "WebSocketReader");
        mReader.start();
        BefLog.v(TAG, "WS reader created and started");
    }


    private void parseWebsocketUri(String wsUri, List<NameValuePair> headers) {
        try {
            URI mWsUri = new URI(wsUri);

            mWsScheme = mWsUri.getScheme();

            if (mWsUri.getPort() == -1) {
                if (mWsScheme.equals("ws")) mWsPort = 80;
                else mWsPort = 443;
            } else mWsPort = mWsUri.getPort();
            mWsHost = mWsUri.getHost();

            if (mWsUri.getRawPath() == null || mWsUri.getRawPath().equals("")) mWsPath = "/";
            else mWsPath = mWsUri.getRawPath();

            if (mWsUri.getRawQuery() == null || mWsUri.getRawQuery().equals(""))
                mWsQuery = null;
            else mWsQuery = mWsUri.getRawQuery();
        } catch (URISyntaxException e) {
            //should not come here
            //TODO
        }
        mWsSubprotocols = null;
        mWsHeaders = headers;
        mOptions = new WebSocketOptions();
    }

    private void disconnect() {
        BefLog.v(TAG, "--------------------------disconnect()_START--------------------");
        removeCallbacks(disconnectIfWebSocketHandshakeTimeOut);
        cancelFuturePing();
        cancelUpcommingRestart();
        if (mReader != null) {
            mReader.quit();
        } else BefLog.v(TAG, "mReader was null");
        if (mWriter != null) {
            mWriter.forward(new WebSocketMessage.Quit());
        } else
            BefLog.v(TAG, "mWriter was null");
        try {
            if (mTransportChannel != null) {
                try {
                    mTransportChannel.close();
                    BefLog.v(TAG, "mTranslateChannel closed");
                } catch (IOException e) {
                    BefLog.e(TAG, e);
                } catch (AssertionError e) {
                    if (isAndroidGetsocknameError(e))
                        BefLog.e(TAG, e);
                    else throw e;
                }
            } else {
                BefLog.v(TAG, "mTransportChannel was NULL");
            }
            if (mWriterThread != null) {
                mWriterThread.join();
                BefLog.v(TAG, "mWriterThread joined");
            }
            if (mReader != null) {
                mReader.join();
                BefLog.v(TAG, "mReader joined");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mReader = null;
        mWriter = null;
        mWriterThread = null;
        mTransportChannel = null;
        BefLog.v(TAG, "--------------------------disconnect()_END--------------------");
    }

    public boolean isConnected() {
        boolean res = mTransportChannel != null && mTransportChannel.isConnected() && !mTransportChannel.isClosed();
        return res;
    }

    private boolean isMessageFromValidReaderWriter(WebSocketMessage.Message msg) {
        if (msg.senderId == System.identityHashCode(mReader) || msg.senderId == System.identityHashCode(mWriter))
            return true;
        else {
            BefLog.e(TAG , "message from invalid Reader/Writer. message.getClass.getSimpleName = " + msg.getClass().getSimpleName());
            return false;
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
