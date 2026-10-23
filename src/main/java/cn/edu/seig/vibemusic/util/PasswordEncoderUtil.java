package cn.edu.seig.vibemusic.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码加密与校验。
 *
 * <p><b>背景</b>：原实现直接使用未加盐的 {@code DigestUtils.md5DigestAsHex}，
 * 同一密码永远得到同一摘要，一旦数据库泄漏即可用彩虹表瞬间还原，
 * 且相同密码的用户在库中呈相同密文，可被批量识别。
 *
 * <p><b>现有做法</b>：
 * <ul>
 *   <li>新密码一律用 <b>BCrypt</b> 存储（自带随机盐，同一密码两次加密结果不同）；</li>
 *   <li>校验时<b>兼容历史 MD5 密文</b>，避免存量用户全部无法登录；</li>
 *   <li>历史 MD5 用户<b>在登录成功的那一刻自动升级</b>为 BCrypt
 *       （见 {@link #needsUpgrade}），无需强制改密、也不存在停机迁移窗口。</li>
 * </ul>
 *
 * <p><b>为什么迁移是安全的</b>：升级只发生在"用户刚提交了正确密码"这一时刻，
 * 此时明文密码就在手上，是唯一能算出 BCrypt 值的时机。因此不需要、也
 * <b>不应该</b>尝试在数据库里批量把 MD5 转成 BCrypt（那在数学上做不到）。
 */
@Component
public class PasswordEncoderUtil {

    /** BCrypt 强度：10 是安全性与登录耗时的常用平衡点（约几十毫秒） */
    private static final int BCRYPT_STRENGTH = 10;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /** 用 BCrypt 加密明文密码 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配库中密文。
     *
     * <p>同时支持 BCrypt 与历史 MD5 两种格式：
     * BCrypt 密文固定以 {@code $2a$}/{@code $2b$}/{@code $2y$} 开头，
     * 据此判断走哪条分支。
     *
     * @param rawPassword     用户提交的明文密码
     * @param storedPassword  数据库中保存的密文（BCrypt 或历史 MD5）
     * @return 是否匹配
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        if (isBcrypt(storedPassword)) {
            try {
                return encoder.matches(rawPassword, storedPassword);
            } catch (IllegalArgumentException e) {
                // 密文格式异常（例如被手工改坏）时按校验失败处理，不向上抛
                return false;
            }
        }
        // 历史 MD5 分支：仅用于兼容存量数据
        String md5 = legacyMd5(rawPassword);
        return constantTimeEquals(md5, storedPassword);
    }

    /**
     * 该密文是否需要升级为 BCrypt。
     *
     * <p>调用方应在"校验通过之后"判断，并在此时把返回值对应的新密文写回库，
     * 这样历史用户会在日常登录过程中被静默升级。
     */
    public boolean needsUpgrade(String storedPassword) {
        return storedPassword != null && !storedPassword.isEmpty() && !isBcrypt(storedPassword);
    }

    /** 计算历史格式的 MD5 摘要（16 进制小写，与旧实现完全一致，保证兼容） */
    public String legacyMd5(String rawPassword) {
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    /** 是否为 BCrypt 密文 */
    public boolean isBcrypt(String storedPassword) {
        return storedPassword != null
                && (storedPassword.startsWith("$2a$")
                || storedPassword.startsWith("$2b$")
                || storedPassword.startsWith("$2y$"));
    }

    /**
     * 定长比较，避免因提前返回而泄漏"前多少位匹配"的时序信息。
     * 在密码校验这种安全敏感路径上，成本极低而收益明确。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}