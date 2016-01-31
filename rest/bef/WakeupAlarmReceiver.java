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

public class WakeupAlarmReceiver extends BroadcastReceiver {
    protected static final String ACTION_WAKEUP = "rest.bef.broadcasts.WAKEUP";
    private static final String TAG = "WakeupAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        BefLog.d(TAG, "Befrest Wakeup Time!" + action);
        if (action.equals(ACTION_WAKEUP)) {
            Befrest.Util.acquireWakeLock(context);
            context.startService(new Intent(context, PushService.class).putExtra(PushService.WAKEUP, true));
        }
    }
}