package com.sya.code;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/17 14:54
 * @description：通知事件消息体
 * @modified By：
 * @version: 1.0.0$
 */
@Data
@NoArgsConstructor
public class CacheMessage implements Serializable {

	private static final long serialVersionUID = -1L;
	/**
	 * 缓存的名称
	 */
	private String cacheName;
	/**
	 * 缓存key
	 */
	private Object key;

	public CacheMessage(String cacheName, Object key) {
		super();
		this.cacheName = cacheName;
		this.key = key;
	}

}
