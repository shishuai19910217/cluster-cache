package com.sya.config;

import com.alibaba.fastjson.support.spring.GenericFastJsonRedisSerializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import org.springframework.data.redis.serializer.*;

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
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory)
            throws UnknownHostException {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        RedisSerializer<String> redisSerializer = new StringRedisSerializer();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //key序列化方式
        redisTemplate.setKeySerializer(redisSerializer);
        /****
         * todo  我擦 value 序列化 现在是使用的默认jdk的 可读性不高 带有\xac\xed\x00\x05sr\x00\x1acn.usr.entity.。。。
         * 等待后来者完善吧
         *
         */


        return redisTemplate;

    }

    @Bean("L2CacheManager")
    @ConditionalOnBean(name = "cacheRedisTemplate")
    public CacheManager cacheManager(@Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
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
    public RedisMessageListenerContainer redisMessageListenerContainer(@Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> stringRedisTemplate,
                                                                       ClusterCacheManager clusterCacheManager) {
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        CacheMessageListener cacheMessageListener = new CacheMessageListener(stringRedisTemplate, clusterCacheManager);
        redisMessageListenerContainer.addMessageListener(cacheMessageListener, new ChannelTopic(clusterCacheProperties.getRedis().getTopic()));
        return redisMessageListenerContainer;
    }
}

