package com.sya.code;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.sya.config.ClusterCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/17 14:54
 * @description：自定义CacheManager 兼容2级缓存
 * @modified By：
 * @version: 1.0.0$
 */
@Slf4j
public class ClusterCacheManager implements CacheManager {
	
	private final Logger logger = LoggerFactory.getLogger(ClusterCacheManager.class);
	/***
	 * ClusterCache 的集合   ClusterCache包含caffeine和redis 客户端
	 */
	private static ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<String, Cache>();
	
	private ClusterCacheProperties clusterCacheProperties;
	
	private RedisTemplate<Object, Object> stringKeyRedisTemplate;

	private boolean dynamic = true;

	private  boolean caffeineEnable = true;
	/***
	 *  caffeine缓存（1级缓存）默认的cacheName
	 *  dynamic 是否动态根据cacheName创建Cache的实现，默认true
	 */
	private Set<String> cacheNames;
	private int cacheInstanceNum;
	{
		cacheNames = new HashSet<>();
		cacheNames.add(CacheNames.CACHE_15MINS);
		cacheNames.add(CacheNames.CACHE_30MINS);
		cacheNames.add(CacheNames.CACHE_60MINS);
		cacheNames.add(CacheNames.CACHE_180MINS);
		cacheNames.add(CacheNames.CACHE_12HOUR);
	}
	public ClusterCacheManager(ClusterCacheProperties clusterCacheProperties,
							   RedisTemplate<Object, Object> stringKeyRedisTemplate) {
		super();
		this.clusterCacheProperties = clusterCacheProperties;
		this.stringKeyRedisTemplate = stringKeyRedisTemplate;
		this.dynamic = clusterCacheProperties.isDynamic();
		this.caffeineEnable = clusterCacheProperties.isCaffeineEnable();
		this.cacheInstanceNum = clusterCacheProperties.getCacheInstanceNum();

	}


	private void clearCacheMap() {
		int size = cacheMap.size();
		if (size> cacheInstanceNum) {
			int deleteNum =	size - cacheInstanceNum;
			logger.info("需要清除的缓存实例有{}个",deleteNum);
			Set<String> keySet = cacheMap.keySet();
			List<ClusterCache> list = new ArrayList<>();
			for (String s : keySet) {
				ClusterCache cache = (ClusterCache)cacheMap.get(s);
				list.add(cache);
			}
			// 升序
			Collections.sort(list,(o1,o2)->{return o2.getTimestamp().compareTo(o1.getTimestamp()); });
			for (int i=0;i<deleteNum;i++) {

				ClusterCache clusterCache = list.get(i);
				logger.info("需要清除的缓存实例第{}个--{}",i+1,clusterCache.getName());
				cacheMap.remove(clusterCache.getName());
				clusterCache.clearLocal(null);
			}


		}

	}
	//——————————————————————— 进行缓存工具 ——————————————————————
	/**
	 * 清除所有进程缓存
	 */
	public void clearAllCache() {
		stringKeyRedisTemplate.convertAndSend(clusterCacheProperties.getRedis().getTopic(), new CacheMessage(null, null));
	}

	/**
	 * 返回所有进程缓存(二级缓存)的统计信息
	 * result:{"缓存名称":统计信息}
	 * @return
	 */
	public static Map<String, CacheStats> getCacheStats() {
		if (CollectionUtils.isEmpty(cacheMap)) {
			return null;
		}

		Map<String, CacheStats> result = new LinkedHashMap<>();
		for (Cache cache : cacheMap.values()) {
			ClusterCache caffeineCache = (ClusterCache) cache;
			result.put(caffeineCache.getName(), caffeineCache.getCaffeineCache().stats());
		}
		return result;
	}

	//—————————————————————————— core —————————————————————————
	/**
	 * 获取
	 * @Author shishuai
	 * @Date 2021/11/18
	 * @description
	 * @param name
	 * @return {@link Cache}
	 */
	@Override
	public Cache getCache(String name) {
		Cache cache = cacheMap.get(name);
		if(cache != null) {
			return cache;
		}
		if(!dynamic && !cacheNames.contains(name)) {
			return null;
		}
		
		cache = new ClusterCache(name, stringKeyRedisTemplate, caffeineCache(name), clusterCacheProperties);
		Cache oldCache = cacheMap.putIfAbsent(name, cache);
		clearCacheMap();
		logger.info("create cache instance, the cache name is : {}", name);
		return oldCache == null ? cache : oldCache;
	}
	
	@Override
	public Collection<String> getCacheNames() {
		return this.cacheNames;
	}
	
	public void clearLocal(String cacheName, Object key) {
		//cacheName为null 清除所有进程缓存
		if (cacheName == null) {
			log.info("清除所有本地缓存");
			cacheMap = new ConcurrentHashMap<>();
			return;
		}

		Cache cache = cacheMap.get(cacheName);
		if(cache == null) {
			return;
		}
		
		ClusterCache clusterCache = (ClusterCache) cache;
		clusterCache.clearLocal(key);
	}

	/**
     * 实例化本地一级缓存
	 * @param name
     * @return
     */
	private com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeineCache(String name) {
		if (!this.caffeineEnable) {
			return null;
		}
		Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder();
		ClusterCacheProperties.CacheDefault cacheConfig;
		switch (name) {
			case CacheNames.CACHE_15MINS:
				cacheConfig = clusterCacheProperties.getCache15m();
				break;
			case CacheNames.CACHE_30MINS:
				cacheConfig = clusterCacheProperties.getCache30m();
				break;
			case CacheNames.CACHE_60MINS:
				cacheConfig = clusterCacheProperties.getCache60m();
				break;
			case CacheNames.CACHE_180MINS:
				cacheConfig = clusterCacheProperties.getCache180m();
				break;
			case CacheNames.CACHE_12HOUR:
				cacheConfig = clusterCacheProperties.getCache12h();
				break;
			default:
				cacheConfig = clusterCacheProperties.getCacheDefault();
		}
		long expireAfterAccess = cacheConfig.getExpireAfterAccess();
		long expireAfterWrite = cacheConfig.getExpireAfterWrite();
		int initialCapacity = cacheConfig.getInitialCapacity();
		long maximumSize = cacheConfig.getMaximumSize();
		long refreshAfterWrite = cacheConfig.getRefreshAfterWrite();

		log.info("本地缓存初始化：");
		if (expireAfterAccess > 0) {
			log.info("设置本地缓存访问后过期时间，{}秒", expireAfterAccess);
			cacheBuilder.expireAfterAccess(expireAfterAccess, TimeUnit.SECONDS);
		}
		if (expireAfterWrite > 0) {
			log.info("设置本地缓存写入后过期时间，{}秒", expireAfterWrite);
			cacheBuilder.expireAfterWrite(expireAfterWrite, TimeUnit.SECONDS);
		}
		if (initialCapacity > 0) {
			log.info("设置缓存初始化大小{}", initialCapacity);
			cacheBuilder.initialCapacity(initialCapacity);
		}
		if (maximumSize > 0) {
			log.info("设置本地缓存最大值{}", maximumSize);
			cacheBuilder.maximumSize(maximumSize);
		}
		if (refreshAfterWrite > 0) {
			cacheBuilder.refreshAfterWrite(refreshAfterWrite, TimeUnit.SECONDS);
		}
		cacheBuilder.recordStats();
		com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = cacheBuilder.build();
		return cache;
	}
}
