package io.github.claudineyns.nostr.relay.factory;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import io.github.claudineyns.nostr.relay.exceptions.UtilityInstantiationException;
import io.github.claudineyns.nostr.relay.utilities.LogService;

/**
 * @see https://www.baeldung.com/java-rsa
 */
public class KeypairFactory {
    private static final LogService logger = LogService.getInstance(KeypairFactory.class.getCanonicalName());

    private KeypairFactory() {
        throw new UtilityInstantiationException();
    }

    public static KeyPair newKeypair() throws IOException {
        
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);

            logger.info("[Server] Keypair generated.");
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException(failure);
        }

    }

}
