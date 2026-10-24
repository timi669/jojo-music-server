package cn.edu.seig.vibemusic.service.impl;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像缩放的验证。
 *
 * <p>为什么要测：数据源给出的原图可能极大（实测 2756x2239、9.08 MB），旧实现直接
 * 拒绝超 8 MB 的图，导致王力宏等歌手的头像始终补不上。改为服务端缩放后，必须确认
 * 缩放真的生效、且结果体积可控。同时这里也覆盖 java.awt 在无头环境可用——
 * 若不可用，整个补全任务的头像环节会全部失败。
 */
class AvatarNormalizationTest {

    private static byte[] pngOf(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // 铺一层渐变，确保不是纯色（纯色在图库里也可能被压得过小，不利于体积断言）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | ((x * 7) << 16) | ((y * 5) << 8) | ((x + y) & 0xFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void shrinksOversizedAvatarToConfiguredEdge() throws Exception {
        byte[] original = pngOf(2756, 2239);
        byte[] normalized = ArtistProfileEnrichmentService.normalizeAvatarBytesForTest(original);

        assertNotNull(normalized, "缩放应成功返回结果");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));
        assertNotNull(result, "结果应能被解码");
        assertTrue(Math.max(result.getWidth(), result.getHeight()) <= 512,
                "长边应缩到 512 以内，实际 " + result.getWidth() + "x" + result.getHeight());
        assertTrue(normalized.length < original.length,
                "缩放后体积应显著变小：" + normalized.length + " vs " + original.length);
    }

    @Test
    void keepsSmallAvatarWithinLimit() throws Exception {
        byte[] small = pngOf(200, 200);
        byte[] normalized = ArtistProfileEnrichmentService.normalizeAvatarBytesForTest(small);
        assertNotNull(normalized);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));
        assertNotNull(result);
        // 小图不放大，保持原尺寸（长边不超过 512 即可）
        assertTrue(Math.max(result.getWidth(), result.getHeight()) <= 512);
    }

    @Test
    void returnsNullForUndecodableBytesSoCallerCanFallBack() {
        // 无法解码时返回 null，调用方会退回原始字节，避免一张坏图阻断整个任务。
        assertNull(ArtistProfileEnrichmentService.normalizeAvatarBytesForTest(new byte[]{1, 2, 3, 4}));
    }
}