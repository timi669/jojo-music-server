package cn.edu.seig.vibemusic.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LyricImportPreviewItem {
    private int fileIndex;
    private String filename;
    private long size;
    private int timestampCount;
    private String normalizedTitle;
    private String normalizedArtist;
    private String matchStatus;
    private Long suggestedSongId;
    private boolean existingLyric;
    private List<LyricSongCandidate> candidates;
}
