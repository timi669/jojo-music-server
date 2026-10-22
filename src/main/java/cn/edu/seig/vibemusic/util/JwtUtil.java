package cn.edu.seig.vibemusic.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtil {

    // 密钥
    private static final String SECRET_KEY = "VIBE_MUSIC"; // 更改为你的密钥
    // 设置 JWT 的过期时间 6 小时
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 6;

    /**
     * 生成 JWT token
     *
     * @param claims 自定义的业务数据
     * @return JWT token
     */
    public static String generateToken(Map<String, Object> claims) {
        return JWT.create()
                .withClaim("claims", claims) // 自定义的业务数据
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // 设置过期时间
                .sign(Algorithm.HMAC256(SECRET_KEY)); // 使用 HMAC256 算法加密
    }

    /**
     * 解析 JWT token
     *
     * @param token JWT token
     * @return 自定义的业务数据
     */
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(Algorithm.HMAC256(SECRET_KEY))
                .build()
                .verify(token)
                .getClaim("claims")
                .asMap();
    }

    /**
     * 从请求头解析当前登录用户标识，用于缓存 key 隔离用户态数据。
     *
     * <p>缓存结果里包含 likeStatus 这类"因人而异"的字段，如果 key 不含用户标识，
     * 就会把 A 用户的收藏状态返回给 B 用户。这里返回：
     * <ul>
     *   <li>已登录普通用户 → {@code "u{userId}"}</li>
     *   <li>未登录 / 解析失败 / 非普通用户 → {@code "anon"}</li>
     * </ul>
     *
     * @param request 当前请求
     * @return 用户标识字符串，永不为 null
     */
    public static String resolveCacheUserKey(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return "anon";
        }
        try {
            String token = request.getHeader("Authorization");
            if (token == null || token.isEmpty()) {
                return "anon";
            }
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            Map<String, Object> claims = parseToken(token);
            if (claims == null) {
                return "anon";
            }
            Object role = claims.get(cn.edu.seig.vibemusic.constant.JwtClaimsConstant.ROLE);
            if (!cn.edu.seig.vibemusic.enumeration.RoleEnum.USER.getRole().equals(role)) {
                return "anon";
            }
            Object userId = claims.get(cn.edu.seig.vibemusic.constant.JwtClaimsConstant.USER_ID);
            return userId == null ? "anon" : ("u" + userId);
        } catch (Exception ignored) {
            // token 无效/过期时按匿名处理，不影响主流程
            return "anon";
        }
    }

}
