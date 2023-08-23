package io.github.claudineyns.nostr.relay.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import io.github.claudineyns.nostr.relay.factory.ServerSocketFactoryBuilder;
import io.github.claudineyns.nostr.relay.utilities.AppProperties;
import io.github.claudineyns.nostr.relay.utilities.LogService;

@SuppressWarnings("unused")
public class ServerHandler implements Runnable {
	
	private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final ExecutorService clientPool;
	
	private ServerSocket server;

    ServerHandler(final ExecutorService clientPool) {
        this.clientPool = clientPool;
    }
	
	void stop() {
		try {
			if(server != null && !server.isClosed()) {
				server.close();
				logger.info("Service terminated.");
			}
		} catch(IOException e) {
            logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
        }
	}

    @Override
    public void run() {
		Runtime.getRuntime().addShutdownHook(new Thread(()-> stop()));

		// final int port = AppProperties.getPort();
		final int tlsPort = AppProperties.getTlsPort();
        try {
            // this.server = new ServerSocket(port);
			this.server = ServerSocketFactoryBuilder.newFactory().createServerSocket(tlsPort);
			logger.info("[Server] server initialized.");
        } catch(IOException cause) {
            throw new IllegalStateException(cause);
        }

		// logger.info("Listening on port {}", port);
		logger.info("TLS listening on port {}.", tlsPort);

		while(true) {
			Socket client = null;
			try {
				client = server.accept();
				logger.info("Connection received.");
			} catch(IOException e) {
                logger.warning("{}: {}", e.getClass().getCanonicalName(), e.getMessage());
				break;
			}

			clientPool.submit(new ClientHandler(client));
		}
    }
}
