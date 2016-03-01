package rest.bef;

/**
 * Created by hojjatimani on 2/25/2016 AD.
 */
public class BefrestEvent {
    Type type;

    public BefrestEvent(Type type) {
        this.type = type;
    }

    enum Type {
        CONNECT,
        DISCONNECT,
        REFRESH,
        STOP
    }
}