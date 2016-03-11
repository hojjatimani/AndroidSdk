package rest.bef;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static rest.bef.ACRAConstants.*;


import static rest.bef.ACRAReportField.*;

/**
 * Fluent API used to assemble the different options used for a crash handleException.
 *
 * @since 4.8.0
 */
final class ACRACrashReport {
    private static final String TAG = "ACRACrashReport";

    Context context;
    String message;
    Thread uncaughtExceptionThread;
    Throwable exception;

    public ACRACrashReport(Context context) {
        this.context = context;
    }

    public ACRACrashReport(Context context, String message, Thread uncaughtExceptionThread, Throwable exception) {
        this.context = context;
        this.message = message;
        this.uncaughtExceptionThread = uncaughtExceptionThread;
        this.exception = exception;
    }

    private final Map<String, String> customData = new HashMap<String, String>();

    /**
     * Sets additional values to be added to {@code CUSTOM_DATA}. Values
     * specified here take precedence over globally specified custom data.
     *
     * @param customData a map of custom key-values to be attached to the handleException
     * @return the updated {@code ACRACrashReport}
     */
    public ACRACrashReport addCustomData(Map<String, String> customData) {
        this.customData.putAll(customData);
        return this;
    }

    /**
     * Sets an additional value to be added to {@code CUSTOM_DATA}. The value
     * specified here takes precedence over globally specified custom data.
     *
     * @param key   the key identifying the custom data
     * @param value the value for the custom data entry
     * @return the updated {@code ACRACrashReport}
     */
    public ACRACrashReport addCustomData(String key, String value) {
        customData.put(key, value);
        return this;
    }

    /**
     * Assembles and sends the crash handleException
     */
    public void report() {

        final ACRACrashReportData crashReportData = createCrashData();

        // Always write the handleException file

        final File reportFile = getReportFileName(crashReportData);
        saveCrashReportFile(reportFile, crashReportData);

    }

    private File getReportFileName(ACRACrashReportData crashData) {
        final String timestamp = crashData.getProperty(USER_CRASH_DATE);
        final String isSilent = crashData.getProperty(IS_SILENT);
        final String fileName = ""
                + (timestamp != null ? timestamp : new Date().getTime()) // Need to check for null because old version of ACRA did not always capture USER_CRASH_DATE
                + (isSilent != null ? ACRAConstants.SILENT_SUFFIX : "")
                + ACRAConstants.REPORTFILE_EXTENSION;
        final ACRAReportLocator reportLocator = new ACRAReportLocator(context);
        return new File(reportLocator.getUnapprovedFolder(), fileName);
    }

    private void saveCrashReportFile(File file, ACRACrashReportData crashData) {
        try {
            final ACRACrashReportPersister persister = new ACRACrashReportPersister();
            persister.store(crashData, file);
        } catch (Exception e) {
            //log
        }
    }

    public ACRACrashReportData createCrashData() {
        final ACRACrashReportData crashReportData = new ACRACrashReportData();
        try {
            final List<ACRAReportField> crashReportFields = Arrays.asList(DEFAULT_REPORT_FIELDS);

            // Make every entry here bullet proof and move any slightly dodgy
            // ones to the end.
            // This ensures that we collect as much info as possible before
            // something crashes the collection process.

            try {
                crashReportData.put(STACK_TRACE, getStackTrace(message, exception));
            } catch (RuntimeException e) {
            }

            // Collect DropBox and logcat. This is done first because some ROMs spam the log with every get on
            // Settings.
            final ACRAPackageManagerWrapper pm = new ACRAPackageManagerWrapper(context);

            // Before JellyBean, this required the READ_LOGS permission
            // Since JellyBean, READ_LOGS is not granted to third-party apps anymore for security reasons.
            // Though, we can call logcat without any permission and still get traces related to our app.
            final boolean hasReadLogsPermission = pm.hasPermission(Manifest.permission.READ_LOGS) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN);
            if (hasReadLogsPermission) {
                BefLog.d(TAG, "READ_LOGS granted! ACRA can include LogCat and DropBox data.");
                if (crashReportFields.contains(LOGCAT)) {
                    try {
                        crashReportData.put(LOGCAT, collectLogCat(null));
                    } catch (RuntimeException e) {
                        BefLog.e(TAG, "Error while retrieving LOGCAT data", e);
                    }
                }
                if (crashReportFields.contains(EVENTSLOG)) {
                    try {
                        crashReportData.put(EVENTSLOG, collectLogCat("events"));
                    } catch (RuntimeException e) {
                        BefLog.e(TAG, "Error while retrieving EVENTSLOG data", e);
                    }
                }
                if (crashReportFields.contains(RADIOLOG)) {
                    try {
                        crashReportData.put(RADIOLOG, collectLogCat("radio"));
                    } catch (RuntimeException e) {
                        BefLog.e(TAG, "Error while retrieving RADIOLOG data", e);
                    }
                }
                if (crashReportFields.contains(DROPBOX)) {
                    try {
//                        crashReportData.put(DROPBOX, new DropBoxCollector().read(context, config));
                    } catch (RuntimeException e) {
                        BefLog.e(TAG, "Error while retrieving DROPBOX data", e);
                    }
                }
            } else {
                BefLog.d(TAG, "READ_LOGS not allowed. ACRA will not include LogCat and DropBox data.");
            }

            try {
                crashReportData.put(ACRAReportField.USER_APP_START_DATE, getTimeString(System.currentTimeMillis()));
                //TODO change current time to time app has started!
            } catch (RuntimeException e) {
                BefLog.e(TAG, "Error while retrieving USER_APP_START_DATE data", e);
            }

            crashReportData.put(IS_SILENT, "true");

            // Always generate handleException uuid
            try {
                crashReportData.put(ACRAReportField.REPORT_ID, UUID.randomUUID().toString());
            } catch (RuntimeException e) {
                BefLog.e(TAG, "Error while retrieving REPORT_ID data", e);
            }

            // Always generate crash time
            try {
                crashReportData.put(ACRAReportField.USER_CRASH_DATE, getTimeString(System.currentTimeMillis()));
            } catch (RuntimeException e) {
                BefLog.e(TAG, "Error while retrieving USER_CRASH_DATE data", e);
            }

            // StackTrace hash
            if (crashReportFields.contains(STACK_TRACE_HASH)) {
                try {
                    crashReportData.put(ACRAReportField.STACK_TRACE_HASH, getStackTraceHash(exception));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving STACK_TRACE_HASH data", e);
                }
            }

            // Installation unique ID
            if (crashReportFields.contains(INSTALLATION_ID)) {
                try {
                    //hojjat: i will use channelId-ANDROID_ID
                    BefrestImpl bi = ((BefrestInvocHandler) Proxy.getInvocationHandler(BefrestFactory.getInstance(context))).obj;
                    String installationId = bi.uId + "-" + bi.chId + "-" + Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                    crashReportData.put(INSTALLATION_ID, installationId);
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving INSTALLATION_ID data", e);
                }
            }

            // Device Configuration when crashing
            if (crashReportFields.contains(INITIAL_CONFIGURATION)) {
                try {
                    crashReportData.put(INITIAL_CONFIGURATION, ACRAConfigurationCollector.collectConfiguration(context));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving INITIAL_CONFIGURATION data", e);
                }
            }
            if (crashReportFields.contains(CRASH_CONFIGURATION)) {
                try {
                    crashReportData.put(CRASH_CONFIGURATION, ACRAConfigurationCollector.collectConfiguration(context));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving CRASH_CONFIGURATION data", e);
                }
            }

            // Collect meminfo
            if (!(exception instanceof OutOfMemoryError) && crashReportFields.contains(DUMPSYS_MEMINFO)) {
                try {
                    crashReportData.put(DUMPSYS_MEMINFO, ACRADumpSysCollector.collectMemInfo());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving DUMPSYS_MEMINFO data", e);
                }
            }

            // Application Package name
            if (crashReportFields.contains(PACKAGE_NAME)) {
                try {
                    crashReportData.put(PACKAGE_NAME, context.getPackageName());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving PACKAGE_NAME data", e);
                }
            }

            // Android OS Build details
            if (crashReportFields.contains(BUILD)) {
                try {
                    crashReportData.put(BUILD, ACRAReflectionCollector.collectConstants(Build.class) + ACRAReflectionCollector.collectConstants(Build.VERSION.class, "VERSION"));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving BUILD data", e);
                }
            }

            // Device model
            if (crashReportFields.contains(PHONE_MODEL)) {
                try {
                    crashReportData.put(PHONE_MODEL, Build.MODEL);
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving PHONE_MODEL data", e);
                }
            }
            // Android version
            if (crashReportFields.contains(ANDROID_VERSION)) {
                try {
                    crashReportData.put(ANDROID_VERSION, Build.VERSION.RELEASE);
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving ANDROID_VERSION data", e);
                }
            }

            // Device Brand (manufacturer)
            if (crashReportFields.contains(BRAND)) {
                try {
                    crashReportData.put(BRAND, Build.BRAND);
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving BRAND data", e);
                }
            }
            if (crashReportFields.contains(PRODUCT)) {
                try {
                    crashReportData.put(PRODUCT, Build.PRODUCT);
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving PRODUCT data", e);
                }
            }

            // Device Memory
            if (crashReportFields.contains(TOTAL_MEM_SIZE)) {
                try {
                    crashReportData.put(TOTAL_MEM_SIZE, Long.toString(ACRAReportUtils.getTotalInternalMemorySize()));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving TOTAL_MEM_SIZE data", e);
                }
            }
            if (crashReportFields.contains(AVAILABLE_MEM_SIZE)) {
                try {
                    crashReportData.put(AVAILABLE_MEM_SIZE, Long.toString(ACRAReportUtils.getAvailableInternalMemorySize()));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving AVAILABLE_MEM_SIZE data", e);
                }
            }

            // Application file path
            if (crashReportFields.contains(FILE_PATH)) {
                try {
                    crashReportData.put(FILE_PATH, ACRAReportUtils.getApplicationFilePath(context));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving FILE_PATH data", e);
                }
            }

            // Main display details
            if (crashReportFields.contains(DISPLAY)) {
                try {
                    crashReportData.put(DISPLAY, ACRADisplayManagerCollector.collectDisplays(context));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving DISPLAY data", e);
                }
            }

            // Add custom info, they are all stored in a single field
            if (crashReportFields.contains(CUSTOM_DATA)) {
                try {
                    crashReportData.put(CUSTOM_DATA, createCustomInfoString(customData));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving CUSTOM_DATA data", e);
                }
            }

            //we dont need i think
//            if (crashReportFields.contains(BUILD_CONFIG)) {
//                try {
//                    final Class buildConfigClass = getBuildConfigClass();
//                    if (buildConfigClass != null) {
//                        crashReportData.put(BUILD_CONFIG, ACRAReflectionCollector.collectConstants(buildConfigClass));
//                    }
//                } catch (ClassNotFoundException ignored) {
//                    // We have already logged this when we had the name of the class that wasn't found.
//                } catch (RuntimeException e) {
//                    BefLog.e(TAG, "Error while retrieving BUILD_CONFIG data", e);
//                }
//            }

            //how whoud we have user emainl??
            // Add user email address, if set in the app's preferences
//            if (crashReportFields.contains(USER_EMAIL)) {
//                try {
//                    crashReportData.put(USER_EMAIL, prefs.getString(ACRA.PREF_USER_EMAIL_ADDRESS, "N/A"));
//                } catch (RuntimeException e) {
//                    BefLog.e(TAG, "Error while retrieving USER_EMAIL data", e);
//                }
//            }

            // Device features
            if (crashReportFields.contains(DEVICE_FEATURES)) {
                try {
                    crashReportData.put(DEVICE_FEATURES, ACRADeviceFeaturesCollector.getFeatures(context));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving DEVICE_FEATURES data", e);
                }
            }

            // Environment (External storage state)
            if (crashReportFields.contains(ENVIRONMENT)) {
                try {
                    crashReportData.put(ENVIRONMENT, ACRAReflectionCollector.collectStaticGettersResults(Environment.class));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving ENVIRONMENT data", e);
                }
            }

            final ACRASettingsCollector settingsCollector = new ACRASettingsCollector(context);
            // System settings
            if (crashReportFields.contains(SETTINGS_SYSTEM)) {
                try {
                    crashReportData.put(SETTINGS_SYSTEM, settingsCollector.collectSystemSettings());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving SETTINGS_SYSTEM data", e);
                }
            }

            // Secure settings
            if (crashReportFields.contains(SETTINGS_SECURE)) {
                try {
                    crashReportData.put(SETTINGS_SECURE, settingsCollector.collectSecureSettings());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving SETTINGS_SECURE data", e);
                }
            }

            // Global settings
            if (crashReportFields.contains(SETTINGS_GLOBAL)) {
                try {

                    crashReportData.put(SETTINGS_GLOBAL, settingsCollector.collectGlobalSettings());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving SETTINGS_GLOBAL data", e);
                }
            }

            // SharedPreferences
            if (crashReportFields.contains(SHARED_PREFERENCES)) {
                try {
                    crashReportData.put(SHARED_PREFERENCES, new ACRASharedPreferencesCollector(context).collect());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving SHARED_PREFERENCES data", e);
                }
            }

            // Now get all the crash data that relies on the PackageManager.getPackageInfo()
            // (which may or may not be here).
            try {
                final PackageInfo pi = pm.getPackageInfo();
                if (pi != null) {
                    // Application Version
                    if (crashReportFields.contains(APP_VERSION_CODE)) {
                        crashReportData.put(APP_VERSION_CODE, Integer.toString(pi.versionCode));
                    }
                    if (crashReportFields.contains(APP_VERSION_NAME)) {
                        crashReportData.put(APP_VERSION_NAME, pi.versionName != null ? pi.versionName : "not set");
                    }
                } else {
                    // Could not retrieve package info...
                    crashReportData.put(APP_VERSION_NAME, "Package info unavailable");
                }
            } catch (RuntimeException e) {
                BefLog.e(TAG, "Error while retrieving APP_VERSION_CODE and APP_VERSION_NAME data", e);
            }

            // Retrieve UDID(IMEI) if permission is available
            if (crashReportFields.contains(DEVICE_ID) && pm.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                try {
                    final String deviceId = ACRAReportUtils.getDeviceId(context);
                    if (deviceId != null) {
                        crashReportData.put(DEVICE_ID, deviceId);
                    }
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving DEVICE_ID data", e);
                }
            }

            //TODO i can do this!
            // Application specific log file
//            if (crashReportFields.contains(APPLICATION_LOG)) {
//                try {
//                    final String logFile = new LogFileCollector().collectLogFile(context, config.applicationLogFile(), config.applicationLogFileLines());
//                    crashReportData.put(APPLICATION_LOG, logFile);
//                } catch (IOException e) {
//                    BefLog.e(TAG, "Error while reading application log file " + config.applicationLogFile(), e);
//                } catch (RuntimeException e) {
//                    BefLog.e(TAG, "Error while retrieving APPLICATION_LOG data", e);
//
//                }
//            }

            // Media Codecs list
            if (crashReportFields.contains(MEDIA_CODEC_LIST)) {
                try {
                    crashReportData.put(MEDIA_CODEC_LIST, ACRAMediaCodecListCollector.collectMediaCodecList());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving MEDIA_CODEC_LIST data", e);
                }
            }

            // Failing thread details
            if (crashReportFields.contains(THREAD_DETAILS)) {
                try {
                    crashReportData.put(THREAD_DETAILS, ACRAThreadCollector.collect(uncaughtExceptionThread));
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving THREAD_DETAILS data", e);
                }
            }

            // IP addresses
            if (crashReportFields.contains(USER_IP)) {
                try {
                    crashReportData.put(USER_IP, ACRAReportUtils.getLocalIpAddress());
                } catch (RuntimeException e) {
                    BefLog.e(TAG, "Error while retrieving USER_IP data", e);
                }
            }

        } catch (RuntimeException e) {
            BefLog.e(TAG, "Error while retrieving crash data", e);
        }

        return crashReportData;
    }

    private String createCustomInfoString(Map<String, String> reportCustomData) {
        Map<String, String> params = null;

        if (reportCustomData != null) {
            params = new HashMap<String, String>();
            params.putAll(reportCustomData);
        }

        final StringBuilder customInfo = new StringBuilder();
        for (final Map.Entry<String, String> currentEntry : params.entrySet()) {
            customInfo.append(currentEntry.getKey());
            customInfo.append(" = ");

            // We need to escape new lines in values or they are transformed into new
            // custom fields. => let's replace all '\n' with "\\n"
            final String currentVal = currentEntry.getValue();
            if (currentVal != null) {
                customInfo.append(currentVal.replaceAll("\n", "\\\\n"));
            } else {
                customInfo.append("null");
            }
            customInfo.append("\n");
        }
        return customInfo.toString();
    }

    private String getStackTrace(String msg, Throwable th) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);

        if (msg != null && !TextUtils.isEmpty(msg)) {
            printWriter.println(msg);
        }

        // If the exception was thrown in a background thread inside
        // AsyncTask, then the actual exception can be found with getCause
        Throwable cause = th;
        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }
        final String stacktraceAsString = result.toString();
        printWriter.close();

        return stacktraceAsString;
    }

    public String collectLogCat(String bufferName) {
        final int myPid = android.os.Process.myPid();
        String myPidStr = null;
        myPidStr = Integer.toString(myPid) + "):";

        final List<String> commandLine = new ArrayList<String>();
        commandLine.add("logcat");
        if (bufferName != null) {
            commandLine.add("-b");
            commandLine.add(bufferName);
        }


        final LinkedList<String> logcatBuf = new ACRABoundedLinkedList<String>(DEFAULT_TAIL_COUNT);

        BufferedReader bufferedReader = null;

        try {
            final Process process = Runtime.getRuntime().exec(commandLine.toArray(new String[commandLine.size()]));
            bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()), DEFAULT_BUFFER_SIZE_IN_BYTES);

            // Dump stderr to null
            new Thread(new Runnable() {
                public void run() {
                    try {
                        InputStream stderr = process.getErrorStream();
                        byte[] dummy = new byte[DEFAULT_BUFFER_SIZE_IN_BYTES];
                        //noinspection StatementWithEmptyBody
                        while (stderr.read(dummy) >= 0) ;
                    } catch (IOException ignored) {
                    }
                }
            }).start();

            while (true) {
                final String line = bufferedReader.readLine();
                if (line == null) {
                    break;
                }
                if (myPidStr == null || line.contains(myPidStr)) {
                    logcatBuf.add(line + "\n");
                }
            }

        } catch (IOException e) {
        } finally {
            safeClose(bufferedReader);
        }

        return logcatBuf.toString();
    }

    public static void safeClose(Reader reader) {
        if (reader == null) return;

        try {
            reader.close();
        } catch (IOException e) {
            // We made out best effort to release this resource. Nothing more we can do.
        }
    }

    public static String getTimeString(long time) {
        final SimpleDateFormat format = new SimpleDateFormat(DATE_TIME_FORMAT_STRING, Locale.ENGLISH);
        return format.format(time);
    }

    private String getStackTraceHash(Throwable th) {
        final StringBuilder res = new StringBuilder();
        Throwable cause = th;
        while (cause != null) {
            final StackTraceElement[] stackTraceElements = cause.getStackTrace();
            for (final StackTraceElement e : stackTraceElements) {
                res.append(e.getClassName());
                res.append(e.getMethodName());
            }
            cause = cause.getCause();
        }

        return Integer.toHexString(res.toString().hashCode());
    }
}
