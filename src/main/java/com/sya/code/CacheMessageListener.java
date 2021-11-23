package com.sya.code;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/17 14:54
 * @description：缓存监听器
 * @modified By：
 * @version: 1.0.0$
 */
public class CacheMessageListener implements MessageListener {
	
	private final Logger logger = LoggerFactory.getLogger(CacheMessageListener.class);

	private RedisTemplate<String, Object> redisTemplate;
	
	private ClusterCacheManager clusterCacheManager;

	{
		//打开json autotype功能
		ParserConfig.getGlobalInstance().addAccept("com.axin.idea.rediscaffeinecachestarter.support.");
	}

	public CacheMessageListener(RedisTemplate<String, Object> redisTemplate,
                                ClusterCacheManager clusterCacheManager) {
		this.redisTemplate = redisTemplate;
		this.clusterCacheManager = clusterCacheManager;
	}

	/**
	 * 利用 redis 发布订阅通知其他节点清除本地缓存
	 *
	 * @param message
	 * @param pattern
	 */
	@Override
	public void onMessage(Message message, byte[] pattern) {

		logger.info("收到redis清除缓存消息, 开始清除本地缓存,   {}", message);
		CacheMessage cacheMessage = JSON.parseObject(message.toString(), CacheMessage.class);
		clusterCacheManager.clearLocal(cacheMessage.getCacheName(), cacheMessage.getKey());
	}
}
