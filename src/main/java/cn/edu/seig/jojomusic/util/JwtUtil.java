package cn.edu.seig.jojomusic.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类。
 *
 * <p><b>密钥来源</b>：从配置项 {@code app.jwt.secret} 读取，
 * 不再硬编码在源码里。硬编码的问题在于：仓库一旦公开（或代码一旦泄漏），
 * 任何人都能用同一个密钥<b>伪造出合法的管理员 token</b>，
 * 绕过全部登录流程 —— 这属于"凭据泄漏即全盘失守"的高危项。
 *
 * <p><b>关于本地开发</b>：为了不打断开发者开箱即用的体验，
 * 未配置时会回退到一个已知的默认值，但会在启动日志中<b>明确告警</b>；
 * 生产/公网环境必须通过环境变量或 profile 配置文件显式设置。
 *
 * <p><b>兼容性说明</b>：本类改为 Spring 组件后，既有调用点
 * （{@code JwtUtil.generateToken(...)} 静态调用）仍保持可用 ——
 * 通过静态字段接收注入值，因此无需改动任何调用方。
 */
@Component
public class JwtUtil {

    /** 未显式配置时使用的本地开发默认密钥（会在启动时告警） */
    private static final String DEV_FALLBACK_SECRET = "JOJO_MUSIC_DEV_ONLY";

    /**
     * 判定密钥是否够强的最低长度。
     *
     * <p>HMAC-SHA256 的密钥应至少与输出摘要等长，即 32 字节（256 位）；这是
     * <a href="https://datatracker.ietf.org/doc/html/rfc7518#section-3.2">RFC 7518 §3.2</a>
     * 对 HS256 的要求。
     */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * 判断密钥是否可用。
     *
     * <p>用<b>强度校验</b>而不是维护一份"弱密钥黑名单"。黑名单只能挡已知的那几个值，
     * 换个短口令照样能用；强度门槛则对任何过短的密钥都生效，也无需在源码里保留
     * 具体的历史口令文本。
     *
     * <p>两条规则：
     * <ol>
     *   <li>配置为空 —— 视为未配置；</li>
     *   <li>长度不足 {@link #MIN_SECRET_LENGTH}，或只有单一字符（如 {@code "aaaa..."}）——
     *       这两类都容易被穷举或猜测。</li>
     * </ol>
     *
     * <p>注意此处<b>不做</b>字典匹配：诸如 {@code "secret"} 这类常见词即使拼到 32 位
     * 也未必安全。真正的判据交给长度与来源（环境变量），避免把安全寄望于关键词清单。
     */
    static boolean isAcceptableSecret(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if (candidate.length() < MIN_SECRET_LENGTH) {
            return false;
        }
        // 全部为同一个字符（忽略大小写）时应拒绝
        return candidate.chars().distinct().count() > 2;
    }

    /** JWT 过期时间：6 小时 */
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 6;

    /**
     * 由 Spring 注入的密钥。
     *
     * <p>静态字段 + 实例 setter/初始化，使静态方法（generateToken/parseToken）
     * 可以读到配置值，同时保持原有 API 不变。
     */
    private static volatile String secretKey = DEV_FALLBACK_SECRET;

    /** 是否使用了开发兜底密钥（供启动告警与测试判断） */
    private static volatile boolean usingFallbackSecret = true;

    @Value("${app.jwt.secret:}")
    private String configuredSecret;

    /**
     * 容器初始化后把配置值写入静态字段。
     *
     * <p>长度与字符分布的校验见 {@link #isAcceptableSecret(String)}。配置为空或不够强时
     * 保留开发兜底值并打印告警，提示必须在部署前设置真实密钥——这样本地开箱即用，
     * 而公网部署如果忘了配置，启动日志会明确提示。
     */
    @PostConstruct
    void initSecret() {
        String candidate = configuredSecret == null ? "" : configuredSecret.trim();
        if (!isAcceptableSecret(candidate)) {
            secretKey = DEV_FALLBACK_SECRET;
            usingFallbackSecret = true;
            System.err.println("""
                    [安全告警] app.jwt.secret 未配置或强度不足（要求至少 32 个字符且非重复字符），\
                    当前使用开发用默认密钥，**请勿用于公网部署**。\
                    生产环境请设置环境变量 APP_JWT_SECRET 或配置文件中的 app.jwt.secret。""");
        } else {
            secretKey = candidate;
            usingFallbackSecret = false;
        }
    }

    /** 当前是否在使用开发兜底密钥（测试与诊断用） */
    public static boolean isUsingFallbackSecret() {
        return usingFallbackSecret;
    }

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
                .sign(Algorithm.HMAC256(secretKey)); // 使用 HMAC256 算法加密
    }

    /**
     * 解析 JWT token
     *
     * @param token JWT token
     * @return 自定义的业务数据
     */
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(Algorithm.HMAC256(secretKey))
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
            Object role = claims.get(cn.edu.seig.jojomusic.constant.JwtClaimsConstant.ROLE);
            if (!cn.edu.seig.jojomusic.enumeration.RoleEnum.USER.getRole().equals(role)) {
                return "anon";
            }
            Object userId = claims.get(cn.edu.seig.jojomusic.constant.JwtClaimsConstant.USER_ID);
            return userId == null ? "anon" : ("u" + userId);
        } catch (Exception ignored) {
            // token 无效/过期时按匿名处理，不影响主流程
            return "anon";
        }
    }

}
