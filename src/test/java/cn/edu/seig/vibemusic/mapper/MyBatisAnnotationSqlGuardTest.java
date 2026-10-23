package cn.edu.seig.vibemusic.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis 注解 SQL 的静态合规检查。
 *
 * <p>背景：在 {@code @Select} / {@code @Update} 等注解里使用动态标签
 * （{@code <foreach>}、{@code <if>}、{@code <where>} 等）时，
 * MyBatis 要求整段 SQL 用 {@code <script>} 包裹。
 * 若漏了包裹，**编译期毫无提示**，只有在该方法被调用的那一刻才抛
 * BuilderException —— 这类缺陷极易潜伏（本项目曾真实存在：
 * {@code SongArtistMapper.selectArtistNamesBySongIds}）。
 *
 * <p>由于验证真实语句构建需要重量级的 Spring 上下文，
 * 这里改为**扫描源码**做静态检查：成本极低、且能覆盖全部 Mapper，
 * 比单点运行时测试更不容易遗漏。
 */
class MyBatisAnnotationSqlGuardTest {

    /** 需要检查动态标签的注解 */
    private static final List<String> DYNAMIC_TAGS = List.of(
            "<foreach", "<if", "<choose", "<when", "<otherwise",
            "<where", "<set", "<trim", "<bind", "<include"
    );

    /** 匹配注解内的文本块（Java 15+ 三引号）与普通字符串两种写法 */
    private static final Pattern ANNOTATION_SQL = Pattern.compile(
            "@(?:Select|Insert|Update|Delete)\\s*\\(\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")\\s*\\)",
            Pattern.DOTALL
    );

    @Test
    void annotationSqlWithDynamicTagsMustBeWrappedInScript() throws IOException {
        Path mapperDir = Path.of("src", "main", "java", "cn", "edu", "seig", "vibemusic", "mapper");
        assertTrue(Files.isDirectory(mapperDir),
                "未找到 Mapper 目录，测试路径可能因工作目录变化而失效: " + mapperDir.toAbsolutePath());

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(mapperDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = ANNOTATION_SQL.matcher(source);

                while (m.find()) {
                    String sql = m.group(1) != null ? m.group(1) : m.group(2);
                    if (sql == null || sql.isBlank()) continue;

                    boolean hasDynamicTag = DYNAMIC_TAGS.stream().anyMatch(sql::contains);
                    boolean wrapped = sql.contains("<script>");

                    if (hasDynamicTag && !wrapped) {
                        // 定位行号，便于直接改
                        int line = 1 + (int) source.substring(0, m.start())
                                .chars().filter(c -> c == '\n').count();
                        violations.add(file.getFileName() + ":" + line
                                + " —— 注解 SQL 含动态标签但未用 <script> 包裹");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "发现 MyBatis 注解 SQL 缺少 <script> 包裹（调用时会抛 BuilderException）:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    void guardItselfCanDetectMissingScript() {
        // 反向自检：确保本测试的判定逻辑真的能发现问题，
        // 否则"全绿"可能只是正则没匹配到任何东西
        String broken = "@Select(\"\"\"\nSELECT 1 WHERE id IN\n<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach>\n\"\"\")";
        String fixed = "@Select(\"\"\"\n<script>\nSELECT 1 WHERE id IN\n<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach>\n</script>\n\"\"\")";

        Matcher m1 = ANNOTATION_SQL.matcher(broken);
        assertTrue(m1.find(), "正则应能匹配到注解 SQL");
        String sql1 = m1.group(1) != null ? m1.group(1) : m1.group(2);
        assertTrue(DYNAMIC_TAGS.stream().anyMatch(sql1::contains), "应识别出动态标签");
        assertTrue(!sql1.contains("<script>"), "破损样例应被判定为缺少 <script>");

        Matcher m2 = ANNOTATION_SQL.matcher(fixed);
        assertTrue(m2.find(), "正则应能匹配到注解 SQL");
        String sql2 = m2.group(1) != null ? m2.group(1) : m2.group(2);
        assertTrue(sql2.contains("<script>"), "修复样例应被判定为已包裹");
    }
}