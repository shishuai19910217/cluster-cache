package com.sya.config;/**
 * @author EDZ
 * @date 2021/11/22
 **/

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.Charset;

/**
 * @author     ：shishuai
 * @date       ：Created in 2021/11/22 16:48
 * @description：
 * @modified By：
 * @version: $
 */
public class FastJsonRedisSerializer<T> implements RedisSerializer<T> {
    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");
    private Class<T> clazz;

    /**
     * 添加autotype白名单
     * 解决redis反序列化对象时报错 ：com.alibaba.fastjson.JSONException: autoType is not support
     */
    static {
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
    }

    public FastJsonRedisSerializer(Class<T> clazz) {
        super();
        this.clazz = clazz;
    }

    @Override
    public byte[] serialize(T t) throws SerializationException {
        if (null == t) {
            return new byte[0];
        }
        return JSON.toJSONString(t).getBytes(DEFAULT_CHARSET);
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (null == bytes || bytes.length <= 0) {
            return null;
        }
        String str = new String(bytes, DEFAULT_CHARSET);
        return JSON.parseObject(str, clazz, Feature.IgnoreAutoType);
    }
}
