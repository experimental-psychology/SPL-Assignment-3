// bgu/spl/net/srv/Connections.java
package bgu.spl.net.srv;

import java.util.Map;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);

    // --- Additions for Protocol Logic (No Casting) ---

    boolean subscribe(String channel, int connectionId, String subscriptionId);

    String unsubscribe(String subscriptionId, int connectionId);

    boolean isSubscribed(int connectionId, String channel);

    int nextMessageId();

    Map<Integer, String> getChannelSubscribersSnapshot(String channel);

    // --- Login Support in Interface ---

    enum LoginResult {
        SUCCESS,
        WRONG_PASSWORD,
        ALREADY_LOGGED_IN
    }

    LoginResult tryLogin(String username, String password, int connectionId);
}
