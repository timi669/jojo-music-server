package cn.edu.seig.jojomusic.model.vo;

import lombok.Data;

@Data
public class LyricSongCandidate {
    private Long songId;
    private String songName;
    private String artistName;
    private String artistCredits;
    private String lyricUrl;
    private boolean hasLyric;

    public LyricSongCandidate() {
    }
}
