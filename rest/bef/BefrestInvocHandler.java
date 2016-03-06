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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Created by hojjatimani on 3/1/2016 AD.
 */
class BefrestInvocHandler implements InvocationHandler {
    private static final String TAG = BefLog.TAG_PREF + "BefrestInvocHandler";
    BefrestImpl obj;

    public BefrestInvocHandler(BefrestImpl obj) {
        this.obj = obj;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object res;
        try {
            res = method.invoke(obj, args);
        } catch (IllegalStateException e) {
            //todo check if it is really our exception
            //nothing
            throw e;
        } catch (Exception e) {
            //todo report
            throw e;
        }
        return res;
    }
}
