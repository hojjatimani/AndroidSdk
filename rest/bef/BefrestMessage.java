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

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONException;
import org.json.JSONObject;

public final class BefrestMessage implements Parcelable {
    private static final String TAG = BefLog.TAG_PREF + "BefrestMessage";

    /* package */ enum MsgType {
        DATA, BATCH, PONG;
    }

    /* package */ MsgType type;
    /* package */ String data;
    /* package */ String timeStamp;

    /* package */ BefrestMessage(String rawMsg) {
        JSONObject jsObject = null;
        try {
            jsObject = new JSONObject(rawMsg);
            switch (jsObject.getString("t")) {
                case "0":
                    type = MsgType.PONG;
                    break;
                case "1":
                    type = MsgType.DATA;
                    break;
                case "2":
                    type = MsgType.BATCH;
                    break;
                default:
                    BefLog.e(TAG, "BefrestImpl Internal ERROR! Unknown Received Push Type!!!");
            }
            data = BefrestImpl.Util.decodeBase64(jsObject.getString("m"));
            timeStamp = jsObject.getString("ts");
        } catch (JSONException e) {
            BefLog.e(TAG, e);
        }
    }

    public String getData() {
        return data;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        return sb.append(" data: ").append(data)
                .append("        time: ").append(timeStamp).toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(data);
        dest.writeString(timeStamp);
    }

    public BefrestMessage(Parcel source) {
        data = source.readString();
        timeStamp = source.readString();
    }

    public static final Parcelable.Creator CREATOR =
            new Parcelable.Creator() {

                @Override
                public BefrestMessage createFromParcel(Parcel source) {
                    return new BefrestMessage(source);
                }

                @Override
                public BefrestMessage[] newArray(int size) {
                    return new BefrestMessage[size];
                }

            };
}