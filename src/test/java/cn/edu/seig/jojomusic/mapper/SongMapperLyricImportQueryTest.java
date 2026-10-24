package cn.edu.seig.jojomusic.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SongMapperLyricImportQueryTest {

    @Test
    void buildsCatalogQueryWithOptionalManualSearchAndLimit() throws Exception {
        Select select = SongMapper.class
                .getMethod("selectLyricImportCandidates", String.class, Integer.class)
                .getAnnotation(Select.class);
        Configuration configuration = new Configuration();
        String query = String.join("", select.value());
        var sqlSource = new XMLLanguageDriver().createSqlSource(configuration, query, SongMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("keyword", null);
        parameters.put("limit", null);
        BoundSql catalogSql = sqlSource.getBoundSql(parameters);
        String catalog = catalogSql.getSql().replaceAll("\\s+", " ").toUpperCase();
        assertTrue(catalog.contains("GROUP_CONCAT"));
        assertFalse(catalog.contains("WHERE S.NAME"));
        assertFalse(catalog.contains("LIMIT ?"));

        parameters.put("keyword", "豆科鱼");
        parameters.put("limit", 100);
        BoundSql searchSql = sqlSource.getBoundSql(parameters);
        String search = searchSql.getSql().replaceAll("\\s+", " ").toUpperCase();
        assertTrue(search.contains("WHERE S.NAME LIKE CONCAT"));
        assertTrue(search.endsWith("LIMIT ?"), search);
    }
}
