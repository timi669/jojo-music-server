package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.service.ArtistMetadataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 验证 Spring 实际注入的歌手资料提供者是组合实现（网易云优先），而不是旧的维基实现。
 *
 * <p>这条断言很重要：若注入错成 {@code WikimediaArtistMetadataProvider}，新写的网易云
 * 逻辑就完全不会被执行——补全任务会表现成"跑了但几乎没有产出"，且不报任何错。
 * 实测首次补全时头像 +0、简介仅 +49，正是需要排除这种情况。
 */
@SpringBootTest
class ArtistMetadataProviderWiringTest {

    @Autowired
    private ArtistMetadataProvider metadataProvider;

    @Test
    void compositeProviderIsInjectedByDefault() {
        assertInstanceOf(CompositeArtistMetadataProvider.class, metadataProvider,
                "应注入组合提供者（网易云优先、维基可选兜底）");
    }
}