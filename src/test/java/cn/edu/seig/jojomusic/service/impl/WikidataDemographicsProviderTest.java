package cn.edu.seig.jojomusic.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wikidata 国籍/性别来源的纯逻辑校验。
 *
 * <p>这些断言来自实测：Wikidata 按名字检索会命中同名无关实体，必须靠职业门禁挡住；
 * 国别写法也需归一化，否则同一国家会出现多个值、把前端筛选拆成两个桶。
 */
class WikidataDemographicsProviderTest {

    @Test
    void normalizesEntityNamesForMatching() {
        // 大小写不敏感：Wikidata 写作 "Rosé"，库里写作 "ROSÉ"，需能对上。
        assertEquals(
                WikidataDemographicsProvider.normalize("ROSÉ"),
                WikidataDemographicsProvider.normalize("Rosé"));
        // 空格与标点被剔除："Jung Kook" 与 "jungkook" 视为同一写法。
        assertEquals(
                WikidataDemographicsProvider.normalize("Jung Kook"),
                WikidataDemographicsProvider.normalize("jungkook"));
        // 注：变音符号（é）不会被剥离，因此 "Rosé" 与不带重音的 "Rose" 不相等。
        // 这是有意保守——把两者当成同一人会引入错误匹配，宁可放弃。
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WikidataDemographicsProvider.normalize("Rosé"),
                WikidataDemographicsProvider.normalize("Rose"));
    }
}