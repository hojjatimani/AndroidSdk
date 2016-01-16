/******************************************************************************
 * Copyright 2015-2016 Oddrun
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
import android.os.Parcelable;

import java.util.ArrayList;


/**
 * Override this class to make register receivers. You can register receivers
 * either dynamically in code or statically in projects AndroidManifest file.
 *
 */
public abstract class BefrestPushBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BefrestPushBroadcastReceiver";

    @Override
    public final void onReceive(Context context, Intent intent) {
        String intentAction = intent.getAction();
        FileLog.d(TAG, "BroadCastReceived : " + intentAction);
        switch (intentAction) {
            case Befrest.ACTION_PUSH_RECIEVED:
//                onPushReceived(context, intent.<BefrestMessage>getParcelableArrayListExtra(Befrest.Util.KEY_MESSAGE_PASSED));
                Parcelable[] p = intent.getParcelableArrayExtra(Befrest.Util.KEY_MESSAGE_PASSED);
                BefrestMessage[] bm = new BefrestMessage[p.length];
                System.arraycopy(p, 0, bm, 0, p.length);
                onPushReceived(context, bm);
                break;
            case Befrest.ACTION_UNAUTHORIZED:
                onAuthorizeProblem(context);
                break;
            case Befrest.ACTION_CONNECTION_REFRESHED:
                onConnectionRefreshed(context);
                break;
            default:
                FileLog.d(TAG, "Unknown Befrest Action!!");
        }
    }

//    abstract public void onPushReceived(Context context, ArrayList<BefrestMessage> messages);

    /**
     * Called when new push messages are received.
     *
     * @param context Context
     *
     * @param messages messages
     */
    abstract public void onPushReceived(Context context, BefrestMessage[] messages);


    /**
     * Called when there is a problem with your Authentication token. The Service encounters authorization errors while trying to connect ro Befrest servers.
     * The service will stop if an authorization problem happen.
     *
     * Override this method to initialize the service again with right Auth token
     *
     * @param context Context
     */
    public void onAuthorizeProblem(Context context) {
        FileLog.d(TAG, "Befrest : Authorization Problem!");
    }

    /**
     * Connection Refreshed! This method is called when Befrest refreshes its connection to server after a refresh request from user
     *
     * @param context Context
     */
    public void onConnectionRefreshed(Context context){
        FileLog.d(TAG, "Connection Refreshed");
    }
}