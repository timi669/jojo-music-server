package cn.edu.seig.vibemusic.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioUploadValidationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptSupportedExtensionAndDetectedCodecPairs() {
        assertTrue(AudioUploadValidationService.extensionMatches("mp3", "mp3", "mp3"));
        assertTrue(AudioUploadValidationService.extensionMatches("flac", "flac", "flac"));
        assertTrue(AudioUploadValidationService.extensionMatches(
                "m4a", "mov,mp4,m4a,3gp,3g2,mj2", "aac"));
        assertTrue(AudioUploadValidationService.extensionMatches("aac", "aac", "aac"));
    }

    @Test
    void shouldRejectExtensionAndContentMismatches() {
        assertFalse(AudioUploadValidationService.extensionMatches(
                "mp3", "mov,mp4,m4a,3gp,3g2,mj2", "aac"));
        assertFalse(AudioUploadValidationService.extensionMatches("flac", "mp3", "mp3"));
        assertFalse(AudioUploadValidationService.extensionMatches("m4a", "aac", "aac"));
        assertFalse(AudioUploadValidationService.extensionMatches("wav", "wav", "pcm_s16le"));
    }

    @Test
    void shouldIdentifyAndFullyDecodeMp3BeforeUpload() throws Exception {
        assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "FFmpeg and FFprobe are required to run this integration check");
        Path fixture = temporaryDirectory.resolve("valid.mp3");
        Process generator = new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=1",
                "-c:a", "libmp3lame",
                "-q:a", "9",
                fixture.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(generator.waitFor(20, TimeUnit.SECONDS), "FFmpeg fixture generation timed out");
        assertEquals(0, generator.exitValue(), "FFmpeg must generate the MP3 test fixture");

        AudioUploadValidationService validator = new AudioUploadValidationService(
                new ObjectMapper(), "ffprobe", "ffmpeg", 30);
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "valid.mp3", "audio/mpeg", Files.readAllBytes(fixture));

        AudioUploadValidationService.AudioValidationResult result = validator.validate(audio);

        assertEquals("MP3", result.detectedFormat());
        assertEquals("mp3", result.codec());
        assertEquals("audio/mpeg", result.contentType());
        assertTrue(result.durationSeconds() > 0);
    }

    @Test
    void shouldRejectAnMp3FileRenamedAsFlac() throws Exception {
        assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "FFmpeg and FFprobe are required to run this integration check");
        Path fixture = temporaryDirectory.resolve("valid.mp3");
        Process generator = new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=1",
                "-c:a", "libmp3lame",
                "-q:a", "9",
                fixture.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(generator.waitFor(20, TimeUnit.SECONDS), "FFmpeg fixture generation timed out");
        assertEquals(0, generator.exitValue(), "FFmpeg must generate the MP3 test fixture");

        AudioUploadValidationService validator = new AudioUploadValidationService(
                new ObjectMapper(), "ffprobe", "ffmpeg", 30);
        MockMultipartFile mislabeledAudio = new MockMultipartFile(
                "audio", "misleading.flac", "audio/flac", Files.readAllBytes(fixture));

        AudioUploadValidationService.AudioValidationException exception = assertThrows(
                AudioUploadValidationService.AudioValidationException.class,
                () -> validator.validate(mislabeledAudio));

        assertTrue(exception.getMessage().contains("实际格式为 MP3"));
    }

    @Test
    void shouldFullyDecodeFlacM4aAndAacFiles() throws Exception {
        assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "FFmpeg and FFprobe are required to run this integration check");
        AudioUploadValidationService validator = new AudioUploadValidationService(
                new ObjectMapper(), "ffprobe", "ffmpeg", 30);

        assertValidatedFixture(validator, "flac", "flac", null, "FLAC", "flac", "audio/flac");
        assertValidatedFixture(validator, "m4a", "aac", "ipod", "M4A", "aac", "audio/mp4");
        assertValidatedFixture(validator, "aac", "aac", "adts", "AAC", "aac", "audio/aac");
    }

    private void assertValidatedFixture(
            AudioUploadValidationService validator,
            String extension,
            String codec,
            String muxer,
            String expectedFormat,
            String expectedCodec,
            String expectedContentType) throws Exception {
        Path fixture = temporaryDirectory.resolve("valid." + extension);
        var command = new java.util.ArrayList<>(java.util.List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=1",
                "-c:a", codec));
        if (muxer != null) {
            command.addAll(java.util.List.of("-f", muxer));
        }
        command.add(fixture.toString());

        Process generator = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(generator.waitFor(20, TimeUnit.SECONDS), "FFmpeg fixture generation timed out");
        assertEquals(0, generator.exitValue(), "FFmpeg must generate the audio test fixture");

        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                fixture.getFileName().toString(),
                expectedContentType,
                Files.readAllBytes(fixture));
        AudioUploadValidationService.AudioValidationResult result = validator.validate(audio);

        assertEquals(expectedFormat, result.detectedFormat());
        assertEquals(expectedCodec, result.codec());
        assertEquals(expectedContentType, result.contentType());
        assertTrue(result.durationSeconds() > 0);
    }

    private boolean commandAvailable(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
