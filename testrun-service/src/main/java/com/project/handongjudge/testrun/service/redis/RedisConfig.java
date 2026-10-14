package com.project.handongjudge.testrun.service.redis;

import com.project.handongjudge.testrun.service.ServiceProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ServiceProperties properties) {
        ServiceProperties.Redis redis = properties.getRedis();
        RedisURI.Builder uri = RedisURI.builder().withHost(redis.getHost()).withPort(redis.getPort())
                .withTimeout(Duration.ofSeconds(10));
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            uri.withPassword(redis.getPassword().toCharArray());
        }
        return RedisClient.create(uri.build());
    }
}
