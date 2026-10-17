package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

@Data
public class LyricImportMapping {
    private Integer fileIndex;
    private Long songId;
    private boolean replaceExisting;
    private boolean manualMatch;
    private String expectedLyric;

    public LyricImportMapping() {
    }
}
