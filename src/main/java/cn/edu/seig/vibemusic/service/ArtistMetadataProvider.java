package cn.edu.seig.vibemusic.service;

import java.io.IOException;
import java.util.Optional;

public interface ArtistMetadataProvider {

    Optional<ArtistMetadata> lookup(String artistName) throws IOException, InterruptedException;

    record ArtistMetadata(
            String imageUrl,
            String avatarSource,
            String introduction,
            String introductionSource
    ) {
    }
}
