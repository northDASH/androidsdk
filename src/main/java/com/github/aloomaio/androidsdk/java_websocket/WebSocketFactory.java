package com.github.aloomaio.androidsdk.java_websocket;

import java.net.Socket;
import java.util.List;

import com.github.aloomaio.androidsdk.java_websocket.drafts.Draft;

public interface WebSocketFactory {
	public WebSocket createWebSocket( WebSocketAdapter a, Draft d, Socket s );
	public WebSocket createWebSocket( WebSocketAdapter a, List<Draft> drafts, Socket s );

}
