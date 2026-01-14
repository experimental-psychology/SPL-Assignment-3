package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionsImpl<T> implements Connections<T> {

    // 1) connectionId -> handler
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> connectedHandlers = new ConcurrentHashMap<>();

    // 2) channel -> (connectionId -> subscriptionId)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>> channelSubscribers = new ConcurrentHashMap<>();

    //    connectionId -> (subscriptionId -> channel)
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> clientSubscriptions = new ConcurrentHashMap<>();

    // 3) users + active sessions
    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> activeUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> connToUser = new ConcurrentHashMap<>(); // O(1) logout

    // 4) global message id
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);

    public enum LoginResult { SUCCESS, WRONG_PASSWORD, ALREADY_LOGGED_IN }

    public ConnectionsImpl() {
        users.put("meni", "films");
        users.put("fawzi", "films");
        users.put("client", "1234");
    }

    // -------- Connections<T> API --------

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = connectedHandlers.get(connectionId);
        if (handler == null) return false;
        handler.send(msg);
        return true;
    }

    @Override
    public void send(String channel, T msg) {
        // Generic broadcast (not used for STOMP MESSAGE usually)
        Map<Integer, String> subs = getChannelSubscribersSnapshot(channel);
        for (Integer id : subs.keySet()) send(id, msg);
    }

    @Override
    public void disconnect(int connectionId) {
        // close handler
        ConnectionHandler<T> handler = connectedHandlers.remove(connectionId);
        if (handler != null) {
            try { handler.close(); } catch (IOException ignored) {}
        }

        // remove from channels (subscriptions)
        ConcurrentHashMap<String, String> userSubs = clientSubscriptions.remove(connectionId);
        if (userSubs != null) {
            for (String channel : userSubs.values()) {
                ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
                if (subs != null) {
                    subs.remove(connectionId);
                    if (subs.isEmpty()) channelSubscribers.remove(channel, subs);
                }
            }
        }

        // logout
        logout(connectionId);
    }

    // -------- Server wiring --------
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        connectedHandlers.put(connectionId, handler);
        clientSubscriptions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>());
    }

    // Alias (אם בשרת שלך עדיין קוראים connect)
    public void connect(int connectionId, ConnectionHandler<T> handler) {
        addConnection(connectionId, handler);
    }

    // -------- Message ids --------
    public int nextMessageId() {
        return messageIdCounter.incrementAndGet();
    }

    // -------- Login logic --------
    public synchronized LoginResult tryLogin(String username, String password, int connectionId) {
        // New user allowed :contentReference[oaicite:6]{index=6}
        String expected = users.putIfAbsent(username, password);
        if (expected != null && !expected.equals(password)) {
            return LoginResult.WRONG_PASSWORD; // :contentReference[oaicite:7]{index=7}
        }

        if (activeUsers.containsKey(username)) {
            return LoginResult.ALREADY_LOGGED_IN; // :contentReference[oaicite:8]{index=8}
        }

        activeUsers.put(username, connectionId);
        connToUser.put(connectionId, username);
        return LoginResult.SUCCESS;
    }

    private void logout(int connectionId) {
        String user = connToUser.remove(connectionId);
        if (user != null) {
            activeUsers.remove(user, connectionId);
        }
    }

    // -------- Subscription helpers --------

    /**
     * @return false if subscriptionId already exists for this client OR already subscribed to the channel
     */
    public boolean subscribe(String channel, int connectionId, String subscriptionId) {
        clientSubscriptions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>());

        // 1) prevent duplicate subscribe to same channel (fixes the overwrite bug)
        ConcurrentHashMap<Integer, String> subsForChannel =
                channelSubscribers.computeIfAbsent(channel, k -> new ConcurrentHashMap<>());
        if (subsForChannel.containsKey(connectionId)) return false;

        // 2) subscription-id must be unique per client
        ConcurrentHashMap<String, String> userSubs = clientSubscriptions.get(connectionId);
        if (userSubs.containsKey(subscriptionId)) return false;

        userSubs.put(subscriptionId, channel);
        subsForChannel.put(connectionId, subscriptionId);
        return true;
    }

    public String unsubscribe(String subscriptionId, int connectionId) {
        ConcurrentHashMap<String, String> userSubs = clientSubscriptions.get(connectionId);
        if (userSubs == null) return null;

        String channel = userSubs.remove(subscriptionId);
        if (channel == null) return null;

        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        if (subs != null) {
            subs.remove(connectionId);
            if (subs.isEmpty()) channelSubscribers.remove(channel, subs);
        }
        return channel;
    }

    public boolean isSubscribed(int connectionId, String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        return subs != null && subs.containsKey(connectionId);
    }

    public Map<Integer, String> getChannelSubscribersSnapshot(String channel) {
        ConcurrentHashMap<Integer, String> subs = channelSubscribers.get(channel);
        if (subs == null) return new HashMap<>();
        return new HashMap<>(subs);
    }
}
