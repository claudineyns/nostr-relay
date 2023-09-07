package io.github.social.nostr.relay.server;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Bootstrap {
	static final Bootstrap bootstrap = new Bootstrap();
	static final ExecutorService serverPool = Executors.newSingleThreadExecutor();
	static final ExecutorService clientPool = Executors.newCachedThreadPool();
	public static void main(String[] args) throws Exception {
		bootstrap.start();
	}

	private void start() throws IOException {
		final ServerHandler serverHandler = new ServerHandler(clientPool);
		serverPool.submit(serverHandler);
	}

}
