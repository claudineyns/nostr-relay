package io.github.social.nostr.relay.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import io.github.social.nostr.relay.security.ServerSocketFactoryBuilder;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.websocket.Websocket;

public class ServerHandler implements Runnable {
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
	private final Websocket websocketHandler = new WebsocketHandler();
	private final Map<String, Boolean> remoteAddressesLocked = new ConcurrentHashMap<>();
	private final Map<String, Long> remoteAddressesFrozen = new ConcurrentHashMap<>();

    private final ExecutorService clientPool;
	
	private ServerSocket server;

    ServerHandler(final ExecutorService clientPool) {
        this.clientPool = clientPool;
    }
	
	void stop() {
		this.websocketHandler.onServerShutdown();

		try {
			if(server != null && !server.isClosed()) {
				server.close();
				logger.info("[Server] stopped.");
			}
		} catch(IOException e) {
            logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
        }
	}

    @Override
    public void run() {
		Runtime.getRuntime().addShutdownHook(new Thread(()-> stop()));

		final boolean isTls = AppProperties.isTls();

		final int port = isTls ? AppProperties.getTlsPort() : AppProperties.getPort();
        try {
			this.server = ServerSocketFactoryBuilder.newFactory(isTls).createServerSocket(port);
			logger.info("[Server] startup completed.");
        } catch(IOException cause) {
            throw new IllegalStateException(cause);
        }

		final String host = AppProperties.getHost();

		logger.info("[Server] listening on port {}.", port);

		CompletableFuture.runAsync(websocketHandler::onServerStartup);

		while(true) {
			Socket client = null;
			try {
				client = server.accept();
			} catch(IOException e) {
                logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
				break;
			}

			clientPool.submit(new ClientHandler(
				host,
				port,
				isTls,
				client,
				websocketHandler,
				remoteAddressesLocked,
				remoteAddressesFrozen
			));
		}
    }
}
