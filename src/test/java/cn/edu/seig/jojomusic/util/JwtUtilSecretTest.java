package cn.edu.seig.jojomusic.util;

import com.auth0.jwt.exceptions.JWTVerificationException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 密钥可配置化的回归测试。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>未配置（或仍为历史弱值）时回退到开发密钥并标记告警状态；</li>
 *   <li>显式配置后使用该密钥，且生成的 token 能被同一密钥解析；</li>
 *   <li>不同密钥签发的 token <b>互相无法解析</b> —— 这正是"密钥可轮换"的意义所在，
 *       也是本次修复的核心价值：泄漏源码不再等于可以伪造 token。</li>
 * </ol>
 */
class JwtUtilSecretTest {

    /** 通过反射模拟 Spring 注入配置值，并触发 @PostConstruct 逻辑 */
    private void configureSecret(String value) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "configuredSecret", value);
        ReflectionTestUtils.invokeMethod(util, "initSecret");
    }

    private Map<String, Object> sampleClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "ROLE_USER");
        claims.put("userId", 1L);
        return claims;
    }

    @Test
    void emptySecretFallsBackToDevKeyAndFlagsIt() {
        configureSecret("");
        assertTrue(JwtUtil.isUsingFallbackSecret(), "空配置应回退到开发密钥并标记告警");

        // 回退状态下功能必须照常可用，不能因为没配密钥就整个不能登录
        String token = JwtUtil.generateToken(sampleClaims());
        assertEquals("ROLE_USER", JwtUtil.parseToken(token).get("role"));
    }

    @Test
    void shortSecretIsRejectedAndFallsBack() {
        // 过短的密钥容易被穷举，一律视为未配置。
        // 这里刻意不用某个具体的历史口令：判据是长度，而不是一份关键词清单，
        // 因此任何短口令都该被拒，包括曾经写死在源码里的那一个。
        configureSecret("SHORT_KEY");
        assertTrue(JwtUtil.isUsingFallbackSecret(), "短密钥不应被视为已安全配置");
    }

    @Test
    void repeatedCharacterSecretIsRejected() {
        // 32 个相同字符虽然长度达标，但熵极低，同样应拒绝。
        configureSecret("a".repeat(40));
        assertTrue(JwtUtil.isUsingFallbackSecret(), "单一重复字符的密钥不应被视为已安全配置");
    }

    @Test
    void secretOfExactlyMinimumLengthIsAccepted() {
        // 32 字符是 RFC 7518 对 HS256 的最小要求，边界值应当通过。
        String exactly32 = "0123456789abcdefghijklmnopqrstuv";   // 32 chars
        assertEquals(32, exactly32.length());
        configureSecret(exactly32);
        assertFalse(JwtUtil.isUsingFallbackSecret(), "恰好达到长度下限的密钥应被接受");
    }

    @Test
    void secretJustBelowMinimumLengthIsRejected() {
        String only31 = "0123456789abcdefghijklmnopqrstu";      // 31 chars
        assertEquals(31, only31.length());
        configureSecret(only31);
        assertTrue(JwtUtil.isUsingFallbackSecret(), "比长度下限少一个字符也应被拒绝");
    }

    @Test
    void configuredSecretIsUsedAndRoundTrips() {
        configureSecret("a-strong-secret-for-testing-only-32chars");
        assertFalse(JwtUtil.isUsingFallbackSecret(), "显式配置后不应再标记为回退状态");

        String token = JwtUtil.generateToken(sampleClaims());
        assertEquals(1L, ((Number) JwtUtil.parseToken(token).get("userId")).longValue());
    }

    @Test
    void tokenSignedWithDifferentSecretCannotBeVerified() {
        // 用密钥 A 签发
        configureSecret("secret-A-aaaaaaaaaaaaaaaaaaaaaaaa");
        String tokenFromA = JwtUtil.generateToken(sampleClaims());

        // 换成密钥 B 后，A 签发的 token 必须无法通过校验。
        // 这条断言保证"轮换密钥即可让旧 token 全部失效"，
        // 也意味着攻击者拿不到真实密钥就无法伪造 token。
        configureSecret("secret-B-bbbbbbbbbbbbbbbbbbbbbbbb");
        assertThrows(JWTVerificationException.class,
                () -> JwtUtil.parseToken(tokenFromA),
                "不同密钥签发的 token 必须校验失败");
    }

    @Test
    void sameSecretOnBothSidesStillWorks() {
        configureSecret("stable-secret-cccccccccccccccccccc");
        String token = JwtUtil.generateToken(sampleClaims());
        // 同一密钥再次"初始化"（模拟重启）后，先前签发的 token 仍应有效
        configureSecret("stable-secret-cccccccccccccccccccc");
        assertEquals("ROLE_USER", JwtUtil.parseToken(token).get("role"));
    }

    @Test
    void secretIsTrimmedSoAccidentalWhitespaceDoesNotBreakTokens() {
        // 密钥需达到强度下限才会被采纳，因此这里用足够长的一组值，
        // 让本测试只关注"两侧空白是否被裁掉"这一件事。
        String padded = "   padded-secret-0123456789abcdefghijklmn   ";
        String trimmed = "padded-secret-0123456789abcdefghijklmn";

        configureSecret(padded);
        assertFalse(JwtUtil.isUsingFallbackSecret(), "长度达标且字符合多样，应被采纳");
        String token = JwtUtil.generateToken(sampleClaims());

        // 去掉空白后再初始化一次，签发的 token 必须仍然有效
        configureSecret(trimmed);
        assertEquals("ROLE_USER", JwtUtil.parseToken(token).get("role"));
    }
}