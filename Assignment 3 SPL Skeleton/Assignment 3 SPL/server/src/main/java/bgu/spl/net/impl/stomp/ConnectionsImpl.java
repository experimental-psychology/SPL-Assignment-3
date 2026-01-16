// bgu/spl/net/impl/stomp/ConnectionsImpl.java
package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionsImpl<T> implements Connections<T> {

    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> connectedHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>> channelSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> clientSubscriptions = new ConcurrentHashMap<>();

    // Users state
    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> activeUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> connToUser = new ConcurrentHashMap<>();

    private final AtomicInteger messageIdCounter = new AtomicInteger(0);

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = connectedHandlers.get(connectionId);
        if (handler == null) return false;
        handler.send(msg);
        return true;
    }

    @Override
    public void send(String channel, T msg) {
        Map<Integer, String> subs = getChannelSubscribersSnapshot(channel);
        for (Integer id : subs.keySet()) {
            send(id, msg);
        }
    }

    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> handler = connectedHandlers.remove(connectionId);
        if (handler != null) {
            try { handler.close(); } catch (IOException ignored) {}
        }

        ConcurrentHashMap<String, String> userSubs = clientSubscriptions.remove(connectionId);
        if (userSubs != null) {
            for (String channel : userSubs.values()) {
                removeSubscriberFromChannel(channel, connectionId);
            }
        }

        logout(connectionId);
    }

    // Called by BaseServer/Reactor when a new handler is created
    public void connect(int connectionId, ConnectionHandler<T> handler) {
        ConnectionHandler<T> old = connectedHandlers.put(connectionId, handler);
        if (old != null && old != handler) {
            try { old.close(); } catch (IOException ignored) {}
        }
        clientSubscriptions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>());
    }

    @Override
    public int nextMessageId() {
        return messageIdCounter.incrementAndGet();
    }

    // --- Login Logic ---

    @Override
    public synchronized LoginResult tryLogin(String username, String password, int connectionId) {
        if (username == null || password == null) {
            return LoginResult.WRONG_PASSWORD;
        }

        // בדיקה/הוספה של המשתמש והסיסמה
        String expected = users.putIfAbsent(username, password);
        if (expected != null && !expected.equals(password)) {
            return LoginResult.WRONG_PASSWORD;
        }

        // בדיקה אטומית אם המשתמש כבר מחובר
        // אם הוא כבר שם - הפעולה תחזיר את ה-ID הקיים ולא null
        if (activeUsers.putIfAbsent(username, connectionId) != null) {
            return LoginResult.ALREADY_LOGGED_IN;
        }

        connToUser.put(connectionId, username);
        return LoginResult.SUCCESS;
    }

    private void logout(int connectionId) {
        String user = connToUser.remove(connectionId);
        if (user != null) {
            activeUsers.remove(user, connectionId);
        }
    }

    // --- Subscription Logic ---

    @Override
    public boolean subscribe(String channel, int connectionId, String subscriptionId) {
        if (channel == null || subscriptionId == null) return false;

        ConcurrentHashMap<String, String> userSubs =
                clientSubscriptions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Integer, String> subsForChannel =
                channelSubscribers.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());

        if (userSubs.putIfAbsent(subscriptionId, channel) != null) return false;

        if (subsForChannel.putIfAbsent(connectionId, subscriptionId) != null) {
            userSubs.remove(subscriptionId);
            return false;
        }

        return true;
    }

    @Override
    public String unsubscribe(String subscriptionId, int connectionId) {
        ConcurrentHashMap<String, String> userSubs = clientSubscriptions.get(connectionId);
        if (userSubs == null) return null;

        String channel = userSubs.remove(subscriptionId);
        if (channel == null) return null;

        removeSubscriberFromChannel(channel, connectionId);
        return channel;
    }

    private void removeSubscriberFromChannel(String channel, int connectionId) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        if (subs != null) {
            subs.remove(connectionId);
            if (subs.isEmpty()) {
                channelSubscribers.compute(channel, (k, v) -> (v == subs && v.isEmpty()) ? null : v);
            }
        }
    }

    @Override
    public boolean isSubscribed(int connectionId, String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        return subs != null && subs.containsKey(connectionId);
    }

    @Override
    public Map<Integer, String> getChannelSubscribersSnapshot(String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        return (subs == null || subs.isEmpty()) ? new HashMap<>() : new HashMap<>(subs);
    }
}
