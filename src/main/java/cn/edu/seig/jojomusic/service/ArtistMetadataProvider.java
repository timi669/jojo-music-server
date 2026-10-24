package cn.edu.seig.jojomusic.service;

import java.io.IOException;
import java.util.Optional;

public interface ArtistMetadataProvider {

    Optional<ArtistMetadata> lookup(String artistName) throws IOException, InterruptedException;

    record ArtistMetadata(
            String imageUrl,
            String introduction,
            Integer gender,
            String area
    ) {
    }
}
