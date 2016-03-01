package rest.bef;

import android.util.Log;

/**
 * Created by hojjatimani on 3/1/2016 AD.
 */
public class BefrestThread extends Thread implements Thread.UncaughtExceptionHandler {
    UncaughtExceptionHandler originalExceptionHandler = this.getUncaughtExceptionHandler();

    public BefrestThread() {
        this.setUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
    }
}
