package cn.edu.seig.jojomusic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ArtistProfileEnrichmentConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService artistProfileEnrichmentExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "artist-profile-enrichment");
            thread.setDaemon(true);
            return thread;
        });
    }
}
