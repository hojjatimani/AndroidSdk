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

import android.content.Context;

import java.net.HttpURLConnection;
import java.net.URL;

import rest.bef.connectivity.NameValuePair;

public class SendPing extends Thread {
    private static final String TAG = "SendPing";
    private Context context;
    private String pingData;
    private Befrest befrest;


    public SendPing(Context context, String pingData) {
        super(TAG);
        this.context = context;
        this.pingData = pingData;
        befrest = Befrest.getInstance(context);
    }

    @Override
    public void run() {
        BefLog.d(TAG, "Befrest Sending Ping...");
        if (Befrest.Util.isConnectedToInternet(context))
            try {
                Thread.sleep(500); //sleep a bit to ensure pong receive handlers are ready
                URL url = new URL(befrest.getPingUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("connection", "close");
                NameValuePair authHeader = befrest.getAuthHeader();
                conn.addRequestProperty(authHeader.getName(), authHeader.getValue());
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5 * 1000);
                conn.setReadTimeout(10 * 1000);
                conn.connect();
                //wait to connect
                conn.getOutputStream().write(pingData.getBytes());
                conn.getOutputStream().flush();
                conn.getOutputStream().close();
                BefLog.v(TAG, "pingSendStatus: " + conn.getResponseCode() + " " + conn.getResponseMessage());
                conn.disconnect();
            } catch (Exception ex) {
                BefLog.w(TAG, "Befrest Had Problem In Sending A Ping. :: " + ex.getMessage());
            }
    }
}
