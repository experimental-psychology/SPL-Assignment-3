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
        Map<Integer, String> subscribers = getChannelSubscribersSnapshot(channel);
        for (Integer subscriberId : subscribers.keySet()) {
            send(subscriberId, msg);
        }
    }

    public void connect(int connectionId, ConnectionHandler<T> handler) {
        ConnectionHandler<T> previous = connectedHandlers.put(connectionId, handler);
        if (previous != null && previous != handler) {
            closeQuietly(previous);
        }
        clientSubscriptions.computeIfAbsent(connectionId, key -> new ConcurrentHashMap<>());
    }

    @Override
    public void disconnect(int connectionId) {
        ConnectionHandler<T> handler = connectedHandlers.remove(connectionId);
        closeQuietly(handler);

        ConcurrentHashMap<String, String> subscriptions = clientSubscriptions.remove(connectionId);
        if (subscriptions != null) {
            for (String channel : subscriptions.values()) {
                removeSubscriberFromChannel(channel, connectionId);
            }
        }

        logout(connectionId);
    }

    @Override
    public int nextMessageId() {
        return messageIdCounter.incrementAndGet();
    }

    @Override
    public synchronized LoginResult tryLogin(String username, String password, int connectionId) {
        if (username == null || password == null) {
            return LoginResult.WRONG_PASSWORD;
        }

        String storedPassword = users.putIfAbsent(username, password);
        if (storedPassword != null && !storedPassword.equals(password)) {
            return LoginResult.WRONG_PASSWORD;
        }

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

    @Override
    public boolean subscribe(String channel, int connectionId, String subscriptionId) {
        if (channel == null || subscriptionId == null) return false;

        ConcurrentHashMap<String, String> userSubscriptions =
                clientSubscriptions.computeIfAbsent(connectionId, key -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Integer, String> channelSubscriptions =
                channelSubscribers.computeIfAbsent(channel, key -> new ConcurrentHashMap<>());

        if (userSubscriptions.putIfAbsent(subscriptionId, channel) != null) return false;

        if (channelSubscriptions.putIfAbsent(connectionId, subscriptionId) != null) {
            userSubscriptions.remove(subscriptionId);
            return false;
        }

        return true;
    }

    @Override
    public String unsubscribe(String subscriptionId, int connectionId) {
        ConcurrentHashMap<String, String> userSubscriptions = clientSubscriptions.get(connectionId);
        if (userSubscriptions == null) return null;

        String channel = userSubscriptions.remove(subscriptionId);
        if (channel == null) return null;

        removeSubscriberFromChannel(channel, connectionId);
        return channel;
    }

    private void removeSubscriberFromChannel(String channel, int connectionId) {
        ConcurrentHashMap<Integer, String> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            subscribers.remove(connectionId);
            if (subscribers.isEmpty()) {
                channelSubscribers.compute(channel, (key, current) -> (current == subscribers && current.isEmpty()) ? null : current);
            }
        }
    }

    @Override
    public boolean isSubscribed(int connectionId, String channel) {
        ConcurrentHashMap<Integer, String> subscribers = channelSubscribers.get(channel);
        return subscribers != null && subscribers.containsKey(connectionId);
    }

    @Override
    public Map<Integer, String> getChannelSubscribersSnapshot(String channel) {
        ConcurrentHashMap<Integer, String> subscribers = channelSubscribers.get(channel);
        return (subscribers == null || subscribers.isEmpty()) ? new HashMap<>() : new HashMap<>(subscribers);
    }

    private void closeQuietly(ConnectionHandler<T> handler) {
        if (handler == null) return;
        try {
            handler.close();
        } catch (IOException ignored) {
        }
    }
}
