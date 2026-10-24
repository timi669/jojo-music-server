package cn.edu.seig.vibemusic.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词抓取里两处容易出错、且实测踩过的逻辑：合作艺人串的切分与标题归一化。
 */
class NetEaseLyricFetchServiceTest {

    // ---------- 合作艺人串切分 ----------

    @Test
    void splitsCollaboratorsOnChineseEnumerationComma() {
        // 实测库里最常见的就是中文顿号。最初只按 " / " 切分，导致整串
        // "Charlie Puth、Jung Kook、BTS" 被当成一个歌手名去搜索，首批 8 首全部漏抓。
        assertEquals("Charlie Puth", NetEaseLyricFetchService.firstArtistForTest("Charlie Puth、Jung Kook、BTS"));
    }

    @Test
    void splitsCollaboratorsOnSlashAndAmpersand() {
        assertEquals("Justin Bieber", NetEaseLyricFetchService.firstArtistForTest("Justin Bieber / Ludacris"));
        assertEquals("王力宏", NetEaseLyricFetchService.firstArtistForTest("王力宏&章子怡"));
        assertEquals("Charlie Puth", NetEaseLyricFetchService.firstArtistForTest("Charlie Puth, Selena Gomez"));
    }

    @Test
    void keepsSingleArtistUnchanged() {
        assertEquals("BOL4", NetEaseLyricFetchService.firstArtistForTest("BOL4"));
        assertEquals("ハセガワダイスケ", NetEaseLyricFetchService.firstArtistForTest("ハセガワダイスケ"));
    }

    @Test
    void handlesBlankArtist() {
        assertEquals("", NetEaseLyricFetchService.firstArtistForTest(""));
        assertEquals("", NetEaseLyricFetchService.firstArtistForTest(null));
    }

    // ---------- 标题归一化 ----------

    @Test
    void ignoresVersionAndContentSuffixes() {
        // (Explicit) 是内容标记，不是不同版本；不剔除会把同一首歌判成"标题不符"。
        assertEquals(
                NetEaseLyricFetchService.comparableTitle("Peaches"),
                NetEaseLyricFetchService.comparableTitle("Peaches (Explicit)"));
        assertEquals(
                NetEaseLyricFetchService.comparableTitle("Left and Right"),
                NetEaseLyricFetchService.comparableTitle("Left and Right (Feat. Jung Kook of BTS)"));
    }

    @Test
    void titleComparisonIsCaseAndDecorationInsensitive() {
        assertEquals(
                NetEaseLyricFetchService.comparableTitle("BLUE"),
                NetEaseLyricFetchService.comparableTitle("Blue"));
        assertEquals(
                NetEaseLyricFetchService.comparableTitle("What Do You Mean?"),
                NetEaseLyricFetchService.comparableTitle("What do you mean"));
    }

    @Test
    void differentTitlesStayDifferent() {
        // 不能把伴奏/翻唱当原曲：标题本身不同就必须区分开。
        assertFalse(NetEaseLyricFetchService.comparableTitle("Love Letter(Inst.)")
                .equals(NetEaseLyricFetchService.comparableTitle("Love Letter")));
        assertFalse(NetEaseLyricFetchService.comparableTitle("Blue")
                .equals(NetEaseLyricFetchService.comparableTitle("Blue Boi")));
    }

    // ---------- 歌手资料 Provider 的归一化 ----------

    @Test
    void artistNameNormalizationIgnoresDecorationAndCase() {
        assertEquals(
                NetEaseArtistMetadataProvider.normalize("ROSÉ"),
                NetEaseArtistMetadataProvider.normalize("rosé"));
        assertEquals(
                NetEaseArtistMetadataProvider.normalize("The Notorious B.I.G."),
                NetEaseArtistMetadataProvider.normalize("The Notorious BIG"));
    }

    @Test
    void multiArtistNamesTakeTheLeadArtist() {
        // 合作条目取"首位艺人"的资料：这些行在库里是独立歌手记录，留空会让界面显得残缺；
        // 而首位艺人本人的头像与简介是准确的，不是错配。
        // （错配是指"赵梦娇"这类单人条目匹配到别人，由名称精确匹配负责拦截。）
        assertTrue(NetEaseArtistMetadataProvider.isMultiArtist("王力宏&谭维维"));
        assertTrue(NetEaseArtistMetadataProvider.isMultiArtist("洛天依、乐正绫"));
        assertTrue(NetEaseArtistMetadataProvider.isMultiArtist("Charlie Puth feat. Selena Gomez"));
        assertFalse(NetEaseArtistMetadataProvider.isMultiArtist("Madonna"));
        assertFalse(NetEaseArtistMetadataProvider.isMultiArtist("A$AP Ferg"));

        assertEquals("王力宏", NetEaseArtistMetadataProvider.firstArtist("王力宏&谭维维"));
        assertEquals("Charlie Puth", NetEaseArtistMetadataProvider.firstArtist("Charlie Puth、Jung Kook、BTS"));
        assertEquals("TizzyT", NetEaseArtistMetadataProvider.firstArtist("TizzyT、万妮达Vinida Weng"));
    }

    @Test
    void leadArtistStripsTrailingParentheticalAlias() {
        // "ロゼ (ROSÉ)" 这类带括号译名的写法直接搜索匹配不上，剥掉括号才能命中本人条目。
        assertEquals("ロゼ", NetEaseArtistMetadataProvider.firstArtist("ロゼ (ROSÉ)"));
        assertEquals("双笙", NetEaseArtistMetadataProvider.firstArtist("双笙（陈元汐）"));
        // 没有括号注记时保持原样。
        assertEquals("Madonna", NetEaseArtistMetadataProvider.firstArtist("Madonna"));
    }

    @Test
    void searchVariantsTryParentheticalNameFirst() {
        // "로제 (ROSÉ)"：括号内是英文正式名，必须优先尝试——实测用括号外的 "로제"
        // 会命中只有 6 首歌、简介为空的韩文条目，而 "ROSÉ" 才是本人（76 首歌、70 字简介）。
        java.util.List<String> variants = NetEaseArtistMetadataProvider.searchNameVariants("로제 (ROSÉ)");
        assertEquals("ROSÉ", variants.get(0), "括号内名字应优先尝试");
        assertTrue(variants.contains("로제"), "去括号主体应作为后续候选保留");

        // 常规形态不受影响
        java.util.List<String> plain = NetEaseArtistMetadataProvider.searchNameVariants("Madonna");
        assertEquals(java.util.List.of("Madonna"), plain);

        // 合作条目只看首位艺人
        java.util.List<String> collab = NetEaseArtistMetadataProvider.searchNameVariants("王力宏&谭维维");
        assertEquals("王力宏", collab.get(0));
    }

    // ---------- 简介清洗 ----------

    @Test
    void keepsGenuineShortIntroductions() {
        // 实测 双笙 的简介只有 14 字但是有效内容；旧的 20 字下限会把它误杀。
        assertEquals("专注于音乐创作的独立音乐人。",
                NetEaseArtistMetadataProvider.sanitizeIntroduction("专注于音乐创作的独立音乐人。"));
        assertEquals("著名歌手，作家。",
                NetEaseArtistMetadataProvider.sanitizeIntroduction("著名歌手，作家。"));
    }

    @Test
    void discardsPlaceholderIntroductions() {
        // 实测数据源里确实存在这类无意义占位内容。
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction("1111111111"));
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction("[]"));
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction("（ ）"));
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction(""));
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction(null));
        // 纯数字/符号，没有文字字符
        assertEquals("", NetEaseArtistMetadataProvider.sanitizeIntroduction("12345"));
    }

    @Test
    void onlyNetEaseImageHostsAreTrusted() {
        // 头像下载白名单：只接受网易云图片域名，避免被数据源重定向到任意主机。
        assertTrue(NetEaseArtistMetadataProvider.isTrustedImageHost("p1.music.126.net"));
        assertTrue(NetEaseArtistMetadataProvider.isTrustedImageHost("p2.music.126.net"));
        assertFalse(NetEaseArtistMetadataProvider.isTrustedImageHost("evil.example.com"));
        assertFalse(NetEaseArtistMetadataProvider.isTrustedImageHost("music.126.net.evil.com"));
        assertFalse(NetEaseArtistMetadataProvider.isTrustedImageHost(null));
    }
}