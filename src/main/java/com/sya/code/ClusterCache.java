package com.sya.code;

import com.github.benmanes.caffeine.cache.Cache;
import com.sya.config.ClusterCacheProperties;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/17 14:54
 * @description：自定义 cache 兼容2级缓存
 * @modified By：
 * @version: 1.0.0$
 */
public class ClusterCache extends AbstractValueAdaptingCache {

    private final Logger logger = LoggerFactory.getLogger(ClusterCache.class);
    /**
     * 缓存的名称 而不是 cache 中的key
     */
    private String name;
    /**
     * 主要操作2级redis 缓存
     */
    private RedisTemplate<Object, Object> redisTemplate;

    @Getter
    private Cache<Object, Object> caffeineCache;

    private boolean caffeineEnable;

    /**
     * 缓存前缀 cache.cluster.achePrefix的值
     */
    private String cachePrefix;

    /***
     * 创建时间戳
     */
    private long timestamp;

    public Long getTimestamp() {
        return timestamp;
    }

/**
     * 最终缓存数据的key  可以看做cachePrefix+ this.name + cache 中的key
     * 对于2级缓存redis来说  cachePrefix:this.name:cache 中的key 就是在redis 中的key
      */


    /**
     * 默认key超时时间 3600s
     */
    private long defaultExpiration = 3600;
    /**
     * 本地 1级缓存 默认过期时间
     */
    private Map<String, Long> defaultExpires = new HashMap<>();
    {
        defaultExpires.put(CacheNames.CACHE_15MINS, TimeUnit.MINUTES.toSeconds(15));
        defaultExpires.put(CacheNames.CACHE_30MINS, TimeUnit.MINUTES.toSeconds(30));
        defaultExpires.put(CacheNames.CACHE_60MINS, TimeUnit.MINUTES.toSeconds(60));
        defaultExpires.put(CacheNames.CACHE_180MINS, TimeUnit.MINUTES.toSeconds(180));
        defaultExpires.put(CacheNames.CACHE_12HOUR, TimeUnit.HOURS.toSeconds(12));
    }

    /**
     * redis 事件topic
     */
    private String topic;

    protected ClusterCache(boolean allowNullValues) {
        super(allowNullValues);
    }

    public ClusterCache(String name, RedisTemplate<Object, Object> redisTemplate,
                        Cache<Object, Object> caffeineCache, ClusterCacheProperties clusterCacheProperties) {
        super(clusterCacheProperties.isCacheNullValues());
        this.name = name;
        this.redisTemplate = redisTemplate;
        this.caffeineCache = caffeineCache;
        this.cachePrefix = clusterCacheProperties.getCachePrefix();
        this.defaultExpiration = clusterCacheProperties.getRedis().getDefaultExpiration();
        this.topic = clusterCacheProperties.getRedis().getTopic();
        defaultExpires.putAll(clusterCacheProperties.getRedis().getExpires());
        this.caffeineEnable = clusterCacheProperties.isCaffeineEnable();
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }
    /**
     * @Author shishuai
     * @Date 2021/11/18
     * @description
     * @param key   cache中的key
     * @param valueLoader
     * @return {@link T}
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object value = lookup(key);
        if (value != null) {
            return (T) value;
        }
        /**
         * key在redis和缓存中均不存在
         * 防止并发 添加重复了
         */

        ReentrantLock lock = new ReentrantLock();
        try {
            lock.lock();
            value = lookup(key);
            if (value != null) {
                return (T) value;
            }
            //执行原方法获得value
            value = valueLoader.call();
            Object storeValue = toStoreValue(value);
            put(key, storeValue);
            return (T) value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e.getCause());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (!super.isAllowNullValues() && value == null) {
            this.evict(key);
            return;
        }
        long expire = getExpire();
        logger.debug("put：{},expire:{}", getKey(key), expire);
        redisTemplate.opsForValue().set(getKey(key), toStoreValue(value), expire, TimeUnit.SECONDS);

        //缓存变更时通知其他节点清理本地缓存

        push(new CacheMessage(this.name, key));
        //此处put没有意义，会收到自己发送的缓存key失效消息
//        caffeineCache.put(key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        Object cacheKey = getKey(key);
        // 使用setIfAbsent原子性操作
        long expire = getExpire();
        boolean setSuccess;
        setSuccess = redisTemplate.opsForValue().setIfAbsent(getKey(key), toStoreValue(value), Duration.ofSeconds(expire));

        Object hasValue;
        //setNx结果
        if (setSuccess) {
            push(new CacheMessage(this.name, key));
            hasValue = value;
        }else {
            hasValue = redisTemplate.opsForValue().get(cacheKey);
        }
        if (this.caffeineEnable) {
            caffeineCache.put(key, toStoreValue(value));
        }
        return toValueWrapper(hasValue);
    }

    @Override
    public void evict(Object key) {
        // 先清除redis中缓存数据，然后清除caffeine中的缓存，避免短时间内如果先清除caffeine缓存后其他请求会再从redis里加载到caffeine中
        redisTemplate.delete(getKey(key));
        if (this.caffeineEnable) {
            push(new CacheMessage(this.name, key));
            caffeineCache.invalidate(key);
        }

    }

    @Override
    public void clear() {
        // 先清除redis中缓存数据，然后清除caffeine中的缓存，避免短时间内如果先清除caffeine缓存后其他请求会再从redis里加载到caffeine中
        Set<Object> keys = redisTemplate.keys(this.name.concat(":*"));
        for (Object key : keys) {
            redisTemplate.delete(key);
        }
        if (this.caffeineEnable) {
            push(new CacheMessage(this.name, null));
            caffeineCache.invalidateAll();
        }

    }

    /**
     * 取值逻辑
     * @param key
     * @return
     */
    @Override
    protected Object lookup(Object key) {
        Object cacheKey = getKey(key);
        Object value = null;
        if (this.caffeineEnable) {
            value = caffeineCache.getIfPresent(key);
            if (value != null) {
                logger.debug("从本地缓存中获得key, the key is : {}", cacheKey);
                return value;
            }
        }
        value = redisTemplate.opsForValue().get(cacheKey);
        if (this.caffeineEnable) {
            if (value != null) {
                logger.debug("从redis中获得值，将值放到本地缓存中, the key is : {}", cacheKey);

                caffeineCache.put(key, value);
            }
        }

        return value;
    }

    /**
     * @description 清理本地缓存
     */
    public void clearLocal(Object key) {
        logger.debug("clear local cache, the key is : {}", key);
        if (!this.caffeineEnable) {
            return;
        }
        if (key == null) {
            caffeineCache.invalidateAll();
        } else {
            caffeineCache.invalidate(key);
        }
    }

    //————————————————————————————私有方法——————————————————————————

    /**
     * 换取缓存的key
     * @Author shishuai
     * @Date 2021/11/17
     * @description
     * @param key
     * @return {@link Object}
     */
    private Object getKey(Object key) {
        String keyStr = this.name.concat(":").concat(key.toString());
        return StringUtils.isEmpty(this.cachePrefix) ? keyStr : this.cachePrefix.concat(":").concat(keyStr);
    }

    private long getExpire() {
        long expire = defaultExpiration;
        Long cacheNameExpire = defaultExpires.get(this.name);
        return cacheNameExpire == null ? expire : cacheNameExpire.longValue();
    }

    /**
     * @description 缓存变更时通知其他节点清理本地缓存
     */
    private void push(CacheMessage message) {
        if (this.caffeineEnable) {
            redisTemplate.convertAndSend(topic, message);
        }

    }

}