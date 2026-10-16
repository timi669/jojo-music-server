package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.model.entity.Artist;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtistServiceImplTest {

    @Test
    void filtersUnitedStatesAcrossSimplifiedTraditionalAndEnglishAreaValues() {
        QueryWrapper<Artist> query = new QueryWrapper<>();

        ArtistServiceImpl.applyArtistDemographicFilters(query, null, "United States");

        assertTrue(query.getSqlSegment().contains("area IN"));
        assertTrue(query.getParamNameValuePairs().containsValue("United States"));
        assertTrue(query.getParamNameValuePairs().containsValue("美国"));
        assertTrue(query.getParamNameValuePairs().containsValue("美國"));
        assertTrue(query.getParamNameValuePairs().containsValue("美利堅合眾國"));
    }

    @Test
    void filtersOtherAreasWithoutClassifiedChineseOrEnglishValues() {
        QueryWrapper<Artist> query = new QueryWrapper<>();

        ArtistServiceImpl.applyArtistDemographicFilters(query, null, "__OTHER__");

        assertTrue(query.getSqlSegment().contains("area NOT IN"));
        Collection<?> excludedAreas = query.getParamNameValuePairs().values();
        assertTrue(excludedAreas.contains("United States"));
        assertTrue(excludedAreas.contains("美国"));
        assertTrue(excludedAreas.contains("美國"));
        assertTrue(excludedAreas.contains("美利堅合眾國"));
        assertTrue(excludedAreas.contains("China"));
        assertTrue(excludedAreas.contains("中国"));
        assertTrue(excludedAreas.contains("中國"));
        assertFalse(excludedAreas.contains("澳大利亚"));
    }

    @Test
    void keepsUnclassifiedAreasSeparateFromOtherAreas() {
        QueryWrapper<Artist> query = new QueryWrapper<>();

        ArtistServiceImpl.applyArtistDemographicFilters(query, null, "__UNKNOWN__");

        assertTrue(query.getSqlSegment().contains("area IS NULL"));
        assertTrue(query.getSqlSegment().contains("area ="));
        assertEquals(1, query.getParamNameValuePairs().size());
        assertTrue(query.getParamNameValuePairs().containsValue(""));
    }
}
