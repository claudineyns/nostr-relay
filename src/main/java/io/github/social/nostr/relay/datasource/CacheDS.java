package io.github.social.nostr.relay.datasource;

import java.time.Duration;

import io.github.social.nostr.relay.utilities.AppProperties;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class CacheDS {
    public static final CacheDS INSTANCE = new CacheDS();

    private boolean closed = false;

    private final JedisPool jedisPool;
    private CacheDS() {
        final int timeout = 0;
        jedisPool = new JedisPool(
            buildPoolConfig(),
            AppProperties.getRedisHost(),
            AppProperties.getRedisPort(),
            timeout,
            AppProperties.getRedisSecret());
    }

    public Jedis connect() {
        return jedisPool.getResource();
    }

    public synchronized byte close() {
        if(this.closed) return 0;

        this.closed = true;
        this.jedisPool.close();

        return 0;
    }

    private static JedisPoolConfig buildPoolConfig() {
        final JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(50);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTime(Duration.ofMillis(60000));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(30000));
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        return poolConfig;
    }

}
