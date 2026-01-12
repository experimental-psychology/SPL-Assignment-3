package bgu.spl.net.impl;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T>{

    private final ConcurrentHashMap<Integer,ConnectionHandler<T>> clientHandlers=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Set<Integer>> channelSub=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,Set<String>> clientSub=new ConcurrentHashMap<>();

    public void connect(int connectionId,ConnectionHandler<T> handler){
        clientHandlers.put(connectionId,handler);
        clientSub.put(connectionId, ConcurrentHashMap.newKeySet());
    }

    public void subscribe(int connectionId, String channel){
        channelSub.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        Set<String> channels=clientSub.get(connectionId);
        if(channels!=null)
            channels.add(channel);
    }

    public void unsubscribe(int connectionId,String channel) {
        Set<Integer> subs=channelSub.get(channel);
        if(subs!=null){
            subs.remove(connectionId);
            if (subs.isEmpty())
                channelSub.remove(channel, subs);
        }
        Set<String> channels = clientSub.get(connectionId);
        if(channels!=null)
            channels.remove(channel);
    }

    @Override
    public boolean send(int connectionId,T msg){
        ConnectionHandler<T> handler=clientHandlers.get(connectionId);
        if(handler==null) 
            return false;
        handler.send(msg);
        return true;
    }

    @Override
    public void send(String channel, T msg){
        Set<Integer> subscribers=channelSub.get(channel);
        if(subscribers==null) 
            return;
        for(Integer clientId:subscribers)
            send(clientId,msg);
    }

    @Override
    public void disconnect(int connectionId){
        clientHandlers.remove(connectionId);
        Set<String> subscribedChannels=clientSub.remove(connectionId);
        if(subscribedChannels==null)
             return;
        for(String channel:subscribedChannels){
            Set<Integer> subs=channelSub.get(channel);
            if(subs!=null){
                subs.remove(connectionId);
                if (subs.isEmpty())
                    channelSub.remove(channel,subs);
            }
        }
    }
}
