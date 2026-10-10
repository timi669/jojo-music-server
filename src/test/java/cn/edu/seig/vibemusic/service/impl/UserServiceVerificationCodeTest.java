package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceVerificationCodeTest {

    @Mock
    private EmailService emailService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "emailService", emailService);
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void storesVerificationCodeOnlyAfterEmailIsSent() {
        when(emailService.sendVerificationCodeEmail("user@example.com")).thenReturn("ABC123");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Result result = userService.sendVerificationCode(" User@Example.com ");

        assertEquals(0, result.getCode());
        verify(valueOperations).set("verificationCode:user@example.com", "ABC123", 5, TimeUnit.MINUTES);
    }

    @Test
    void doesNotStoreVerificationCodeWhenEmailSendingFails() {
        when(emailService.sendVerificationCodeEmail("user@example.com")).thenReturn(null);

        Result result = userService.sendVerificationCode("user@example.com");

        assertEquals(1, result.getCode());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void verifiesCodeWithoutCaseOrSurroundingWhitespaceSensitivity() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verificationCode:user@example.com")).thenReturn("aBc123");

        boolean valid = userService.verifyVerificationCode(" User@Example.com ", " ABC123 ");

        org.junit.jupiter.api.Assertions.assertTrue(valid);
    }
}
