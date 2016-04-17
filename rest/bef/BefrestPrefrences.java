package rest.bef;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by hojjatimani on 3/14/2016 AD.
 */
class BefrestPrefrences {

    /**
     * Name for sharedPreferences used for saving BefrestImpl data.
     */
    static String SHARED_PREFERENCES_NAME = "rest.bef.SHARED_PREFERENCES";
    static final String PREF_U_ID = "PREF_U_ID";
    static final String PREF_AUTH = "PREF_AUTH";
    static final String PREF_CH_ID = "PREF_CH_ID";
    static final String PREF_TOPICS = "PREF_TOPICS";
    static final String PREF_LOG_LEVEL = "PREF_LOG_LEVEL";
    static final String PREF_CUSTOM_PUSH_SERVICE_NAME = "PREF_CUSTOM_PUSH_SERVICE_NAME";
    static final String PREF_CONTINUOUS_CLOSES = "PREF_CONTINUOUS_CLOSES";
    static final String PREF_CONTINUOUS_CLOSES_TYPES = "PREF_CONTINUOUS_CLOSES_TYPES";
    static final String PREF_LAST_SUCCESSFUL_CONNECT_TIME = "PREF_LAST_SUCCESSFUL_CONNECT_TIME";
    static final String PREF_CONNECT_ANOMALY_DATA_RECORDING_TIME = "PREF_CONNECT_ANOMALY_DATA_RECORDING_TIME";

    static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    static void saveInt(Context context, String key, int value) {
        Editor editor = getPrefs(context).edit();
        editor.putInt(key, value);
        commitChanges(context, editor);
    }

    static void saveString(Context context, String key, String value) {
        Editor editor = getPrefs(context).edit();
        editor.putString(key, value);
        commitChanges(context, editor);
    }

    static void saveFloat(Context context, String key, float value) {
        Editor editor = getPrefs(context).edit();
        editor.putFloat(key, value);
        commitChanges(context, editor);
    }

    static void saveBoolean(Context context, String key, boolean value) {
        Editor editor = getPrefs(context).edit();
        editor.putBoolean(key, value);
        commitChanges(context, editor);
    }

    static void saveLong(Context context, String key, long value) {
        Editor editor = getPrefs(context).edit();
        editor.putLong(key, value);
        commitChanges(context, editor);
    }

    static void saveToPrefs(Context context, long uId, String AUTH, String chId) {
        Editor prefEditor = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();
        prefEditor.putLong(PREF_U_ID, uId);
        prefEditor.putString(PREF_AUTH, AUTH);
        prefEditor.putString(PREF_CH_ID, chId);
        commitChanges(context, prefEditor);
    }

    private static void commitChanges(Context context, SharedPreferences.Editor editor) {
        try {
            editor.commit();
        } catch (Throwable t) {
            ACRACrashReport crash = new ACRACrashReport(context, t);
            crash.message = "(handled) unable to commit changes to sharedPrefrences (Nazdika#930)";
            crash.setHandled(true);
            crash.report();
            editor.commit();
        }
    }
}