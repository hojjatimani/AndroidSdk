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

/**
 * Created by ehsan on 11/24/2015.
 */
public final class BefrestConnectivityChangeReceiver extends BroadcastReceiver {
    private static final String TAG = BefLog.TAG_PREF + "BefrestConnectivityChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        BefLog.v(TAG, "Broadcast received: action=" + action);
        Class<?> pushService = Befrest.getInstance(context).pushService;
        if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION))
            if (Befrest.Util.isConnectedToInternet(context))
                context.startService(new Intent(context, pushService).putExtra(PushService.NETWORK_CONNECTED, true));
            else
                context.startService(new Intent(context, pushService).putExtra(PushService.NETWORK_DISCONNECTED, true));
    }
}