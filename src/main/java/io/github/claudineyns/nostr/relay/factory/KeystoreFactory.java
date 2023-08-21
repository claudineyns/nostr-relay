package io.github.claudineyns.nostr.relay.factory;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import io.github.claudineyns.nostr.relay.exceptions.UtilityInstantiationException;
import io.github.claudineyns.nostr.relay.utilities.LogService;

/**
 * @see https://www.baeldung.com/java-keystore
 */
public class KeystoreFactory {
    private static final LogService logger = LogService.getInstance(KeystoreFactory.class.getCanonicalName());

    private KeystoreFactory() {
        throw new UtilityInstantiationException();
    }

    public static KeyStore load() throws IOException {
        final char[] passphrase = "changeit".toCharArray();

        final KeyStore ks;
        try(final InputStream infile = KeystoreFactory.class.getResourceAsStream("/keystore")) {
            ks = KeyStore.getInstance("JKS");
            ks.load(infile, passphrase);
        } catch(KeyStoreException | NoSuchAlgorithmException | CertificateException failure) {
            throw new IOException(failure);
        }

        logger.info("[Server] Keystore initialized.");
        return ks;
    }

    // KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    
}
