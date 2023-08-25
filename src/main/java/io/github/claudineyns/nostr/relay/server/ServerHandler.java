package io.github.claudineyns.nostr.relay.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import io.github.claudineyns.nostr.relay.factory.ServerSocketFactoryBuilder;
import io.github.claudineyns.nostr.relay.utilities.AppProperties;
import io.github.claudineyns.nostr.relay.utilities.LogService;

public class ServerHandler implements Runnable {
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
	private final WebsocketHandler websocketHandler = new WebsocketHandler();

    private final ExecutorService clientPool;
	
	private ServerSocket server;

    ServerHandler(final ExecutorService clientPool) {
        this.clientPool = clientPool;
    }
	
	void stop() {
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

		final boolean tlsRequired = AppProperties.isTls();

		final int port = tlsRequired ? AppProperties.getTlsPort() : AppProperties.getPort();
        try {
			this.server = ServerSocketFactoryBuilder.newFactory(tlsRequired).createServerSocket(port);
			logger.info("[Server] startup completed.");
        } catch(IOException cause) {
            throw new IllegalStateException(cause);
        }

		logger.info("[Server] listening on port {}.", port);

		while(true) {
			Socket client = null;
			try {
				client = server.accept();
				logger.info("Connection received.");
			} catch(IOException e) {
                logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
				break;
			}

			clientPool.submit(new ClientHandler(client, websocketHandler));
		}
    }
}
