package cn.edu.seig.vibemusic.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百度百科词条可用性判定。
 *
 * <p>这里的用例全部来自真实实测数据——都是联网跑出来的原样返回，不是假想输入。
 * 百度百科按名字检索会命中同名异人词条，必须靠这套规则挡住。
 */
class BaiduBaikeArtistMetadataProviderTest {

    // ---------- 应当采用：身份可确认 + 文娱类 ----------

    @Test
    void acceptsEntryWhoseTitleEqualsTheName() {
        // 实测：谭维维 / 赵梦娇 / 元华 都是标题即本名。
        assertTrue(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "谭维维", "谭维维",
                "谭维维，1982年10月8日出生于四川省自贡市，中国内地女歌手、音乐人、演员。",
                "中国内地女歌手、音乐人、演员，一级演员"));
        assertTrue(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "赵梦娇", "赵梦娇",
                "赵梦娇，1990年5月7日出生于云南省大理白族自治州，中国内地女配音演员。",
                "中国内地女配音演员"));
    }

    @Test
    void acceptsEntryWhoseSummaryCarriesTheForeignOriginalName() {
        // 实测：标题是中文译名，摘要里用括号给出外文原名——这正是同一人的凭据。
        assertTrue(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Lily-Rose Depp", "莉莉-罗丝·德普",
                "莉莉-罗丝·德普（Lily-Rose Depp），1999年5月27日出生于法国巴黎，法国女演员、模特。",
                "法国女演员、模特"));
        assertTrue(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Ephraim Sykes", "伊弗雷姆·赛克斯",
                "伊弗雷姆·赛克斯（Ephraim Sykes），1985年7月17日出生于美国佛罗里达州圣彼得堡，男演员。",
                "男、演员"));
        assertTrue(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Giveon", "吉维昂",
                "吉维昂（GIVĒON），本名吉维昂·德兹曼·埃文斯（Giveon Dezmann Evans），美国R&B男歌手。",
                "美国男歌手"));
    }

    // ---------- 应当排除：身份对不上 ----------

    @Test
    void rejectsEntryForADifferentPerson() {
        // 实测：查 ROSÉ 返回的是篮球运动员"德里克·罗斯"，与歌手无关。
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "ROSÉ", "德里克·罗斯",
                "德里克·罗斯（Derrick Rose），1988年10月4日出生于美国伊利诺伊州芝加哥，美国职业篮球运动员。",
                "1988年10月4日出生的美国篮球运动员"));
    }

    @Test
    void rejectsMissingEntry() {
        // 实测：BOL4 / Jon Rua / Mutah 在百度百科没有对应词条，接口返回 title=undefined。
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry("BOL4", "undefined", "", ""));
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry("Jon Rua", "", "", ""));
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry("Mutah", null, null, null));
    }

    // ---------- 应当排除：身份对得上但词条不是艺人 ----------

    @Test
    void rejectsWordAndWeatherEntriesEvenIfNameMatches() {
        // 实测：查 Queen 返回"queen 是英语单词"；查 Storm 返回"天气现象"。
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Queen", "queen",
                "queen是英语单词，具有名词、及物动词和不及物动词三种词性。作为名词时意为女王、王后。",
                "英语单词"));
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Storm", "storm",
                "Storm是英文单词，指伴有强风、雷电或降水的天气现象。",
                "天气现象"));
    }

    @Test
    void rejectsNonEntertainmentProfessions() {
        // 实测：查 Nate Smith 返回的是"美国布景师"——描述里含"作品""电视剧"，
        // 单靠文娱关键词会被误判为艺人，必须由非艺人特征词拦下。
        assertFalse(BaiduBaikeArtistMetadataProvider.isUsableEntry(
                "Nate Smith", "Nate Smith",
                "Nate Smith，美国布景师，主要作品有电视剧《波特兰迪亚》。其工作涉及影视场景搭建与装饰。",
                "美国布景师"));
    }

    // ---------- 简介清洗与图片域名 ----------

    @Test
    void discardsPlaceholderIntroductionsButKeepsShortOnes() {
        assertEquals("", BaiduBaikeArtistMetadataProvider.sanitizeIntroduction("[]"));
        assertEquals("", BaiduBaikeArtistMetadataProvider.sanitizeIntroduction("1111111111"));
        assertEquals("", BaiduBaikeArtistMetadataProvider.sanitizeIntroduction(null));
        // 短但有内容的一句话应保留（不能按长度一刀切）
        assertEquals("专注于音乐创作的独立音乐人。",
                BaiduBaikeArtistMetadataProvider.sanitizeIntroduction("专注于音乐创作的独立音乐人。"));
    }

    @Test
    void onlyTrustsBaiduImageHosts() {
        assertTrue(BaiduBaikeArtistMetadataProvider.isTrustedImageHost("bkimg.cdn.bcebos.com"));
        assertTrue(BaiduBaikeArtistMetadataProvider.isTrustedImageHost("bkimg.bcebos.com"));
        assertFalse(BaiduBaikeArtistMetadataProvider.isTrustedImageHost("evil.example.com"));
        assertFalse(BaiduBaikeArtistMetadataProvider.isTrustedImageHost("bcebos.com.evil.com"));
        assertFalse(BaiduBaikeArtistMetadataProvider.isTrustedImageHost(null));
    }

    // ---------- 性别判定（取值约定：0=男、1=女、2=组合） ----------

    @Test
    void detectsIndividualGenderFromProfessionPhrase() {
        // 实测原样返回的 desc/abstract 片段。
        assertEquals(Integer.valueOf(1),
                BaiduBaikeArtistMetadataProvider.detectGender("中国内地女歌手、音乐人、演员，一级演员"));
        assertEquals(Integer.valueOf(0),
                BaiduBaikeArtistMetadataProvider.detectGender("中国内地男演员"));
        assertEquals(Integer.valueOf(0),
                BaiduBaikeArtistMetadataProvider.detectGender("澳大利亚男歌手"));
        assertEquals(Integer.valueOf(1),
                BaiduBaikeArtistMetadataProvider.detectGender("美国R&B女歌手、词曲作者，1995年出生"));
    }

    @Test
    void individualBeatsGroupWhenBothAppear() {
        // 实测：薛黛霏 的简介同时含"女歌手"（本人）与"组合"（所属团体），
        // 应以"本人是女歌手"为准，而不是判成组合。
        assertEquals(Integer.valueOf(1), BaiduBaikeArtistMetadataProvider.detectGender(
                "薛明媛，中国内地女歌手、演员，女子演唱组合超级女团成员"));
    }

    @Test
    void detectsGroupWhenNoIndividualPhraseExists() {
        assertEquals(Integer.valueOf(2),
                BaiduBaikeArtistMetadataProvider.detectGender("中国内地男子演唱组合"));
        assertEquals(Integer.valueOf(2),
                BaiduBaikeArtistMetadataProvider.detectGender("英国摇滚乐队，成立于1970年"));
    }

    @Test
    void returnsNullInsteadOfGuessing() {
        // 只出现"男""女"二字但不成对出现职业词时不能猜。
        assertNull(BaiduBaikeArtistMetadataProvider.detectGender("男女对唱作品"));
        assertNull(BaiduBaikeArtistMetadataProvider.detectGender("女声和音"));
        assertNull(BaiduBaikeArtistMetadataProvider.detectGender(""));
        assertNull(BaiduBaikeArtistMetadataProvider.detectGender(null));
    }

    @Test
    void compoundProfessionsAreRecognised() {
        // 实测 Trick Trick / Twista / Lil B 的词条用"说唱歌手"这种复合词；
        // 只列"说唱"或只列"歌手"都会漏，导致个人被误判成"组合"。
        assertEquals(Integer.valueOf(0), BaiduBaikeArtistMetadataProvider.detectGender("男说唱歌手"));
        assertEquals(Integer.valueOf(0), BaiduBaikeArtistMetadataProvider.detectGender(
                "Twista，本名Carl Terrell Mitchell，出生于美国伊利诺斯州芝加哥，男说唱歌手"));
        assertEquals(Integer.valueOf(0), BaiduBaikeArtistMetadataProvider.detectGender(
                "美国男歌手、音乐制作人"));
        assertEquals(Integer.valueOf(1), BaiduBaikeArtistMetadataProvider.detectGender(
                "美国女歌手、词曲作者、模特"));
    }

    @Test
    void bandMembersAreIndividualsNotGroups() {
        // 实测 Steve Perry 的词条是"美国Journey乐队主唱"——提到乐队，但他本人是个人歌手。
        // 不能因为出现"乐队"就记成组合；此处没有性别字，应返回"不确定"而非 2。
        assertNull(BaiduBaikeArtistMetadataProvider.detectGender("美国Journey乐队主唱"));
        // 若有明确性别字，按个人判断
        assertEquals(Integer.valueOf(0),
                BaiduBaikeArtistMetadataProvider.detectGender("某乐队主唱，美国男歌手"));
    }

    // ---------- 资料卡值清洗（HTML 与脚注） ----------

    @Test
    void stripsFootnoteSuperscriptAndRefAnchor() {
        // 实测原样返回：<a ...>中国</a><sup>179</sup><a name="ref_179"></a>
        // 只删标签会把 "179" 留下，曾写出 "中国179" 这种脏值。
        assertEquals("中国", BaiduBaikeArtistMetadataProvider.cleanCardValue(
                "<a target=_blank href=\"/item/%E4%B8%AD%E5%9B%BD/22516505\" data-lemmaid=\"22516505\">中国</a>"
                        + "<sup>179</sup><a name=\"ref_179\"></a>"));
        assertEquals("美国", BaiduBaikeArtistMetadataProvider.cleanCardValue(
                "<a target=_blank href=\"/item/%E7%BE%8E%E5%9B%BD/125486\" data-lemmaid=\"125486\">美国</a>"
                        + "<sup>2</sup><a name=\"ref_2\"></a>"));
    }

    @Test
    void keepsPlainValueUntouched() {
        // 实测 Khalid 的词条里国籍就是纯文本，没有标签。
        assertEquals("卡塔尔", BaiduBaikeArtistMetadataProvider.cleanCardValue("卡塔尔"));
        assertEquals("", BaiduBaikeArtistMetadataProvider.cleanCardValue(null));
        assertEquals("", BaiduBaikeArtistMetadataProvider.cleanCardValue(""));
    }
}