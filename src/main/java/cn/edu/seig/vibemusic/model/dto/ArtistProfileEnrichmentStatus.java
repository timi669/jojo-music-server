package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
public class ArtistProfileEnrichmentStatus {

    private volatile String jobId;
    private volatile String state;
    private volatile long total;
    private volatile long processed;
    private volatile long avatarsAdded;
    private volatile long introductionsAdded;
    private volatile long skipped;
    private volatile long failed;
    private volatile String currentArtist;
    private List<String> issues = new CopyOnWriteArrayList<>();
    private volatile long omittedIssues;

    public synchronized void addIssue(String issue) {
        if (issues.size() < 100) {
            issues.add(issue);
        } else {
            omittedIssues++;
        }
    }
}
