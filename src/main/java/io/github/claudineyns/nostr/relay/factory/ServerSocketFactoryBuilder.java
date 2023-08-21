package io.github.claudineyns.nostr.relay.factory;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import io.github.claudineyns.nostr.relay.exceptions.UtilityInstantiationException;
import io.github.claudineyns.nostr.relay.utilities.LogService;

/**
 * @see https://docs.oracle.com/en/java/javase/11/security/sample-code-illustrating-secure-socket-connection-client-and-server.html#GUID-3561ED02-174C-4E65-8BB1-5995E9B7282C
 */
public class ServerSocketFactoryBuilder {
    private static final LogService logger = LogService.getInstance(ServerSocketFactoryBuilder.class.getCanonicalName());

    public static String TLS = "TLS";

    private ServerSocketFactoryBuilder() {
        throw new UtilityInstantiationException();
    }

    public static ServerSocketFactory newFactory() throws IOException {
        return newFactory(TLS);
    }

    public static ServerSocketFactory newFactory(final String type) throws IOException {
        if (!TLS.equals(type)) {
            return ServerSocketFactory.getDefault();
        }

        final char[] passphrase = "changeit".toCharArray();

        try {
            final SSLContext ctx = SSLContext.getInstance(TLS);
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            final KeyStore ks = KeystoreFactory.load();

            kmf.init(ks, passphrase);
            ctx.init(kmf.getKeyManagers(), null, null);

            logger.info("[Server] Server Socket Factory initialized.");
            return ctx.getServerSocketFactory();
        } catch (NoSuchAlgorithmException
                | KeyStoreException
                | UnrecoverableKeyException
                | KeyManagementException failure) {
            throw new IOException(failure);
        }

    }

}
