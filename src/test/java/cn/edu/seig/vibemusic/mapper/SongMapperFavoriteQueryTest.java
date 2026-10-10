package cn.edu.seig.vibemusic.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SongMapperFavoriteQueryTest {

    @Test
    void favoriteSongQueryOrdersByFavoriteIdsWithoutDistinctJoin() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/SongMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("songIds", List.of(12L, 7L));
        parameters.put("songName", "");
        parameters.put("artistName", "");
        parameters.put("album", "");

        BoundSql boundSql = configuration
                .getMappedStatement("cn.edu.seig.vibemusic.mapper.SongMapper.getSongsByIds")
                .getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").toUpperCase();

        assertFalse(sql.contains("SELECT DISTINCT"));
        assertFalse(sql.contains("TB_USER_FAVORITE"));
        assertTrue(sql.contains("ORDER BY FIELD(S.ID"), sql);
        assertTrue(sql.endsWith(")"), sql);
    }
}
