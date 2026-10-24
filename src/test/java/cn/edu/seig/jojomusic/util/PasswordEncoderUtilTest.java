package cn.edu.seig.jojomusic.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码加密与"MD5 → BCrypt"平滑迁移的回归测试。
 *
 * <p>这是本次安全加固中最容易出错的部分：既要让新密码用上带盐的 BCrypt，
 * 又不能让历史 MD5 用户被锁在门外。若迁移逻辑写错，后果是**存量用户全部无法登录**，
 * 因此这里逐条钉住行为契约。
 */
class PasswordEncoderUtilTest {

    private final PasswordEncoderUtil util = new PasswordEncoderUtil();

    // ---------- BCrypt 基本行为 ----------

    @Test
    void encodeProducesBcryptFormat() {
        String hash = util.encode("mySecret123");
        assertTrue(util.isBcrypt(hash), "新密码应为 BCrypt 格式: " + hash);
        assertTrue(hash.startsWith("$2"), "BCrypt 密文应以 $2 开头: " + hash);
    }

    @Test
    void encodeIsSaltedSoSamePasswordYieldsDifferentHashes() {
        // 这正是裸 MD5 的致命缺陷：同一密码永远同一摘要。
        // BCrypt 每次加密使用随机盐，因此两次结果必须不同。
        String a = util.encode("samePassword");
        String b = util.encode("samePassword");
        assertNotEquals(a, b, "同一密码两次加密结果应不同（随机盐）");
        assertTrue(util.matches("samePassword", a));
        assertTrue(util.matches("samePassword", b));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = util.encode("correct");
        assertFalse(util.matches("wrong", hash));
    }

    @Test
    void nullAndEmptyAreHandledSafely() {
        assertFalse(util.matches(null, util.encode("x")));
        assertFalse(util.matches("x", null));
        assertFalse(util.matches("x", ""));
        assertFalse(util.matches(null, null));
    }

    @Test
    void malformedBcryptHashDoesNotThrow() {
        // 库里若被手工改坏成 "$2a$garbage"，不应抛异常炸掉登录接口
        assertFalse(util.matches("anything", "$2a$not-a-real-hash"));
    }

    // ---------- 历史 MD5 兼容 ----------

    @Test
    void legacyMd5MatchesOldImplementation() {
        // 必须与旧实现 DigestUtils.md5DigestAsHex(password.getBytes()) 完全一致，
        // 否则历史用户会全部登录失败。此处用"123456"的已知 MD5 值做锚点。
        assertEquals("e10adc3949ba59abbe56e057f20f883e", util.legacyMd5("123456"));
    }

    @Test
    void matchesAcceptsLegacyMd5Hash() {
        String legacyHash = util.legacyMd5("123456");
        assertTrue(util.matches("123456", legacyHash), "历史 MD5 密文必须仍能通过校验");
        assertFalse(util.matches("wrong", legacyHash));
    }

    @Test
    void matchesAcceptsBcryptHashAsWell() {
        assertTrue(util.matches("123456", util.encode("123456")));
    }

    // ---------- 迁移判定 ----------

    @Test
    void needsUpgradeTrueOnlyForLegacyHash() {
        assertTrue(util.needsUpgrade(util.legacyMd5("123456")), "MD5 密文应需要升级");
        assertFalse(util.needsUpgrade(util.encode("123456")), "BCrypt 密文不应再升级");
    }

    @Test
    void needsUpgradeFalseForNullOrEmpty() {
        assertFalse(util.needsUpgrade(null));
        assertFalse(util.needsUpgrade(""));
    }

    @Test
    void upgradePathKeepsUserAbleToLogin() {
        // 模拟完整迁移：老用户用 MD5 存储 -> 校验通过 -> 升级为 BCrypt -> 之后仍能登录
        String oldHash = util.legacyMd5("myOldPassword");
        assertTrue(util.matches("myOldPassword", oldHash));
        assertTrue(util.needsUpgrade(oldHash));

        String upgraded = util.encode("myOldPassword");
        assertFalse(util.needsUpgrade(upgraded));
        assertTrue(util.matches("myOldPassword", upgraded), "升级后必须仍能用原密码登录");
    }

    @Test
    void isBcryptRecognizesAllCommonPrefixes() {
        assertTrue(util.isBcrypt("$2a$10$abcdefghijklmnopqrstuv"));
        assertTrue(util.isBcrypt("$2b$10$abcdefghijklmnopqrstuv"));
        assertTrue(util.isBcrypt("$2y$10$abcdefghijklmnopqrstuv"));
        assertFalse(util.isBcrypt("e10adc3949ba59abbe56e057f20f883e"));
        assertFalse(util.isBcrypt(""));
    }
}