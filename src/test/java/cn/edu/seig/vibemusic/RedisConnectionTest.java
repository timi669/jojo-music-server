package cn.edu.seig.vibemusic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
public class RedisConnectionTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testRedisConnection() {
        // 测试设置和获取字符串值
        String key = "testKey";
        String value = "testValue";

        // 设置值
        stringRedisTemplate.opsForValue().set(key, value);

        // 获取值
        String retrievedValue = stringRedisTemplate.opsForValue().get(key);

        // 验证值是否正确
        assertEquals(value, retrievedValue, "Redis连接成功，值匹配");

        // 删除测试键
        stringRedisTemplate.delete(key);

        // 验证键是否被删除
        assertNull(stringRedisTemplate.opsForValue().get(key), "测试键已删除");
    }

}