package com.sya.config;

import com.sya.code.CacheMessageListener;
import com.sya.code.ClusterCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

import java.net.UnknownHostException;

/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/17 14:54
 * @description：自动装配配置类
 * @modified By：
 * @version: 1.0.0$
 */
@ConditionalOnProperty(
        prefix = "cache.cluster",
        name = {"enabled"},
        havingValue = "true",
        matchIfMissing = true
)
@Configuration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(ClusterCacheProperties.class)
@Slf4j
public class ClusterCacheAutoConfiguration {
    @Autowired
    private ClusterCacheProperties clusterCacheProperties;

    @Bean("cacheRedisTemplate")
    @ConditionalOnMissingBean(name = "cacheRedisTemplate")
    public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory)
            throws UnknownHostException {
        RedisTemplate<Object, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // 使用 Jackson2JsonRedisSerializer 替换默认序列化
        Jackson2JsonRedisSerializer genericFastJsonRedisSerializer = new Jackson2JsonRedisSerializer(Object.class);
        // 设置key和value的序列化规则
        redisTemplate.setKeySerializer(new GenericToStringSerializer<>(Object.class));
        redisTemplate.setValueSerializer(genericFastJsonRedisSerializer);
        // 设置hashKey和hashValue的序列化规则
        redisTemplate.setHashKeySerializer(new GenericToStringSerializer<>(Object.class));
        redisTemplate.setHashValueSerializer(genericFastJsonRedisSerializer);
        // 设置支持事物
        redisTemplate.setEnableTransactionSupport(true);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;

    }

    @Bean("L2CacheManager")
    @ConditionalOnBean(name = "cacheRedisTemplate")
    public CacheManager cacheManager(@Qualifier("cacheRedisTemplate") RedisTemplate<Object, Object> redisTemplate) {
        return new ClusterCacheManager(clusterCacheProperties, redisTemplate);
    }

    @Bean
    @ConditionalOnBean(name = "cacheRedisTemplate")
    @ConditionalOnProperty(
            prefix = "cache.cluster",
            name = {"caffeineEnable"},
            havingValue = "true",
            matchIfMissing = true
    )
    public RedisMessageListenerContainer redisMessageListenerContainer(@Qualifier("cacheRedisTemplate") RedisTemplate<Object, Object> stringRedisTemplate,
                                                                       ClusterCacheManager clusterCacheManager) {
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        CacheMessageListener cacheMessageListener = new CacheMessageListener(stringRedisTemplate, clusterCacheManager);
        redisMessageListenerContainer.addMessageListener(cacheMessageListener, new ChannelTopic(clusterCacheProperties.getRedis().getTopic()));
        return redisMessageListenerContainer;
    }
}

