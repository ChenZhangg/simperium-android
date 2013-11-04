/**
 * Used by Simperium to create a WebSocket connection to Simperium. Manages Channels
 * and listens for channel write events. Notifies channels when the connection is connected
 * or disconnected.
 *
 * WebSocketManager is configured by Simperium and shouldn't need to be access directly
 * by applications.
 */
package com.simperium.android;

import com.codebutler.android_websockets.WebSocketClient;
import com.simperium.client.Bucket;
import com.simperium.client.Channel;
import com.simperium.client.ChannelProvider;
import com.simperium.util.Logger;

import org.apache.http.message.BasicNameValuePair;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class WebSocketManager implements ChannelProvider, WebSocketClient.Listener, Channel.OnMessageListener {

    public interface WebSocketFactory {

        public WebSocketClient buildClient(URI socketUri, WebSocketClient.Listener listener,
            List<BasicNameValuePair> headers);
    }

    private static class DefaultSocketFactory implements WebSocketFactory {

        public WebSocketClient buildClient(URI socketUri, WebSocketClient.Listener listener,
            List<BasicNameValuePair> headers) {
            return new WebSocketClient(socketUri, listener, headers);
        }

    }

    public enum ConnectionStatus {
        DISCONNECTING, DISCONNECTED, CONNECTING, CONNECTED
    }

    public static final String TAG = "Simperium.Websocket";
    private static final String WEBSOCKET_URL = "wss://api.simperium.com/sock/1/%s/websocket";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String COMMAND_HEARTBEAT = "h";
    private String appId, sessionId;
    private String clientId;
    private WebSocketClient socketClient;
    private boolean reconnect = true;
    private HashMap<Channel,Integer> channelIndex = new HashMap<Channel,Integer>();
    private HashMap<Integer,Channel> channels = new HashMap<Integer,Channel>();
    private URI socketURI;

    static final long HEARTBEAT_INTERVAL = 20000; // 20 seconds
    static final long DEFAULT_RECONNECT_INTERVAL = 3000; // 3 seconds

    private Timer heartbeatTimer, reconnectTimer;
    private int heartbeatCount = 0;
    private long reconnectInterval = DEFAULT_RECONNECT_INTERVAL;

    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;

    protected Channel.Serializer mSerializer;

    public WebSocketManager(String appId, String sessionId, Channel.Serializer channelSerializer) {
        this(appId, sessionId, channelSerializer, new DefaultSocketFactory());
    }

    public WebSocketManager(String appId, String sessionId, Channel.Serializer channelSerializer,
        WebSocketFactory socketFactory) {
        this.appId = appId;
        this.sessionId = sessionId;
        mSerializer = channelSerializer;
        List<BasicNameValuePair> headers = Arrays.asList(
            new BasicNameValuePair(USER_AGENT_HEADER, sessionId)
        );
        socketURI = URI.create(String.format(WEBSOCKET_URL, appId));
        socketClient = socketFactory.buildClient(socketURI, this, headers);
    }

    /**
     * Creates a channel for the bucket. Starts the websocket connection if not connected
     *
     */
    public Channel buildChannel(Bucket bucket) {
        // create a channel
        Channel channel = new Channel(appId, sessionId, bucket, mSerializer, this);
        int channelId = channels.size();
        channelIndex.put(channel, channelId);
        channels.put(channelId, channel);
        // If we're not connected then connect, if we don't have a user
        // access token we'll have to hold off until the user does have one
        if (!isConnected() && bucket.getUser().hasAccessToken()) {
            connect();
        } else if (isConnected()) {
            channel.onConnect();
        }
        return channel;
    }

    public void connect() {
        // if we have channels, then connect, otherwise wait for a channel
        cancelReconnect();
        if (!isConnected() && !isConnecting() && !channels.isEmpty()) {
            Logger.log(TAG, String.format("Connecting to %s", socketURI));
            setConnectionStatus(ConnectionStatus.CONNECTING);
            reconnect = true;
            socketClient.connect();
        }
    }

    public void disconnect() {
        // disconnect the channel
        reconnect = false;
        cancelReconnect();
        if (isConnected()) {
            setConnectionStatus(ConnectionStatus.DISCONNECTING);
            Logger.log(TAG, "Disconnecting");
            // being told to disconnect so don't automatically reconnect
            socketClient.disconnect();
        }
    }

    public boolean isConnected() {
        return connectionStatus == ConnectionStatus.CONNECTED;
    }

    public boolean isConnecting() {
        return connectionStatus == ConnectionStatus.CONNECTING;
    }

    public boolean isDisconnected() {
        return connectionStatus == ConnectionStatus.DISCONNECTED;
    }

    public boolean isDisconnecting() {
        return connectionStatus == ConnectionStatus.DISCONNECTING;
    }

    public boolean getConnected() {
        return isConnected();
    }

    protected void setConnectionStatus(ConnectionStatus status) {
        connectionStatus = status;
    }

    private void notifyChannelsConnected() {
        Set<Channel> channelSet = channelIndex.keySet();
        Iterator<Channel> iterator = channelSet.iterator();
        while(iterator.hasNext()) {
            Channel channel = iterator.next();
            channel.onConnect();
        }
    }

    private void notifyChannelsDisconnected() {
        Set<Channel> channelSet = channelIndex.keySet();
        Iterator<Channel> iterator = channelSet.iterator();
        while(iterator.hasNext()) {
            Channel channel = iterator.next();
            channel.onDisconnect();
        }
    }

    private void cancelHeartbeat() {
        if(heartbeatTimer != null) heartbeatTimer.cancel();
        heartbeatCount = 0;
    }

    private void scheduleHeartbeat() {
        cancelHeartbeat();
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            public void run() {
                sendHearbeat();
            }
        }, HEARTBEAT_INTERVAL);
    }

    synchronized private void sendHearbeat() {
        heartbeatCount ++;
        String command = String.format("%s:%d", COMMAND_HEARTBEAT, heartbeatCount);

        if(!isConnected()) return;

        socketClient.send(command);

    }

    private void cancelReconnect() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer = null;
        }
    }

    private void scheduleReconnect() {
        // check if we're not already trying to reconnect
        if (reconnectTimer != null) return;
        reconnectTimer = new Timer();
        // exponential backoff
        long retryIn = nextReconnectInterval();
        reconnectTimer.schedule(new TimerTask() {
            public void run() {
                connect();
            }
        }, retryIn);
        Logger.log(String.format("Retrying in %d", retryIn));
    }

    // duplicating javascript reconnect interval calculation
    // doesn't do exponential backoff
    private long nextReconnectInterval() {
        long current = reconnectInterval;
        if (reconnectInterval < 4000) {
            reconnectInterval ++;
        } else {
            reconnectInterval = 15000;
        }
        return current;
    }

    /**
     *
     * Channel.OnMessageListener event listener
     *
     */
    public void onMessage(Channel.MessageEvent event) {
        Channel channel = (Channel)event.getSource();
        Integer channelId = channelIndex.get(channel);
        // Prefix the message with the correct channel id
        String message = String.format("%d:%s", channelId, event.getMessage());

        if(isConnected()){
            socketClient.send(message);
        }
    }

    public void onClose(Channel fromChannel) {
        // if we're allready disconnected we can ignore
        if (isDisconnected()) return;

        // check if all channels are disconnected and if so disconnect from the socket
        for (Channel channel : channels.values()) {
            if (channel.isStarted()) return;
        }
        Logger.log(TAG, String.format("%s disconnect from socket", Thread.currentThread().getName()));
        disconnect();
    }

    public void onOpen(Channel fromChannel) {
        connect();
    }

    public void onIdle(Channel fromChannel){
        // no-op
    }

    /**
     *
     * WebSocketClient.Listener methods for receiving status events from the socket
     *
     */
    public void onConnect() {
        Logger.log(TAG, String.format("Connected"));
        setConnectionStatus(ConnectionStatus.CONNECTED);
        notifyChannelsConnected();
        heartbeatCount = 0; // reset heartbeat count
        scheduleHeartbeat();
        cancelReconnect();
        reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    }

    public void onMessage(String message) {
        scheduleHeartbeat();
        int size = message.length();
        String[] parts = message.split(":", 2);;
        if (parts[0].equals(COMMAND_HEARTBEAT)) {
            heartbeatCount = Integer.parseInt(parts[1]);
            return;
        }
        try {
            int channelId = Integer.parseInt(parts[0]);
            Channel channel = channels.get(channelId);
            channel.receiveMessage(parts[1]);
        } catch (NumberFormatException e) {
            Logger.log(TAG, String.format("Unhandled message %s", parts[0]));
        }
    }

    public void onMessage(byte[] data) {
    }

    public void onDisconnect(int code, String reason) {
        Logger.log(TAG, String.format("Disconnect %d %s", code, reason));
        setConnectionStatus(ConnectionStatus.DISCONNECTED);
        notifyChannelsDisconnected();
        cancelHeartbeat();
        if(reconnect) scheduleReconnect();
    }

    public void onError(Exception error) {
        Logger.log(TAG, String.format("Error: %s", error), error);
        setConnectionStatus(ConnectionStatus.DISCONNECTED);
        if (java.io.IOException.class.isAssignableFrom(error.getClass()) && reconnect) {
            scheduleReconnect();
        }
    }

}
