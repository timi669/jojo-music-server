package cn.edu.seig.vibemusic.util;

import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JwtUtil.resolveCacheUserKey 的回归测试。
 *
 * <p>该方法用于把用户标识织入 @Cacheable 的 key，避免"用户 A 的收藏状态
 * 缓存后被返回给用户 B"。它必须在任何异常情况下都返回非 null 值（回退 anon），
 * 否则缓存 key 拼接会抛错、影响主流程。
 */
class JwtUtilResolveCacheUserKeyTest {

    /** 生成一个指定角色与 userId 的真实 JWT（与后端同一算法/密钥） */
    private String token(String role, Long userId) {
        Map<String, Object> claims = new HashMap<>();
        if (role != null) {
            claims.put("role", role);
        }
        if (userId != null) {
            claims.put("userId", userId);
        }
        claims.put("username", "tester");
        return JwtUtil.generateToken(claims);
    }

    private MockHttpServletRequest requestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void nullRequestFallsBackToAnonymous() {
        assertEquals("anon", JwtUtil.resolveCacheUserKey(null));
    }

    @Test
    void missingAuthorizationHeaderFallsBackToAnonymous() {
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith(null)));
    }

    @Test
    void blankAuthorizationHeaderFallsBackToAnonymous() {
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith("")));
    }

    @Test
    void loggedInUserGetsUserScopedKey() {
        String token = token(RoleEnum.USER.getRole(), 149L);
        assertEquals("u149", JwtUtil.resolveCacheUserKey(requestWith(token)));
    }

    @Test
    void bearerPrefixedTokenIsAccepted() {
        String token = token(RoleEnum.USER.getRole(), 42L);
        assertEquals("u42", JwtUtil.resolveCacheUserKey(requestWith("Bearer " + token)));
    }

    @Test
    void adminRoleIsTreatedAsAnonymous() {
        // 管理端没有 likeStatus 语义，且不应产生按管理员切分的冗余缓存
        String token = token(RoleEnum.ADMIN.getRole(), 65L);
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith(token)));
    }

    @Test
    void userTokenWithoutUserIdFallsBackToAnonymous() {
        String token = token(RoleEnum.USER.getRole(), null);
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith(token)));
    }

    @Test
    void malformedTokenFallsBackToAnonymousInsteadOfThrowing() {
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith("not-a-jwt")));
    }

    @Test
    void tokenSignedWithWrongKeyFallsBackToAnonymous() {
        // 用另一个密钥签发的 token 验签必然失败
        String forged = com.auth0.jwt.JWT.create()
                .withClaim("claims", Map.of("role", RoleEnum.USER.getRole(), "userId", 999))
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("WRONG_KEY"));
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith(forged)));
    }

    @Test
    void expiredTokenFallsBackToAnonymous() {
        String expired = com.auth0.jwt.JWT.create()
                .withClaim("claims", Map.of("role", RoleEnum.USER.getRole(), "userId", 7))
                .withExpiresAt(new Date(System.currentTimeMillis() - 1_000))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("VIBE_MUSIC"));
        assertEquals("anon", JwtUtil.resolveCacheUserKey(requestWith(expired)));
    }

    @Test
    void differentUsersProduceDifferentKeys() {
        String a = JwtUtil.resolveCacheUserKey(requestWith(token(RoleEnum.USER.getRole(), 1L)));
        String b = JwtUtil.resolveCacheUserKey(requestWith(token(RoleEnum.USER.getRole(), 2L)));
        assertEquals("u1", a);
        assertEquals("u2", b);
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void neverReturnsNullForArbitraryHeaderValues() {
        String[] samples = {"Bearer", "Bearer ", "null", "undefined", "a.b.c", "  ", "Bearer a.b.c"};
        for (String sample : samples) {
            String key = JwtUtil.resolveCacheUserKey(requestWith(sample));
            org.junit.jupiter.api.Assertions.assertNotNull(key, "header=" + sample);
        }
    }
}