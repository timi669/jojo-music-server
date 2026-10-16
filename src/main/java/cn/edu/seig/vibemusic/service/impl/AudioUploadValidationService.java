package cn.edu.seig.vibemusic.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AudioUploadValidationService {

    private static final Logger log = LoggerFactory.getLogger(AudioUploadValidationService.class);
    private static final int MAX_OUTPUT_LENGTH = 8192;
    private static final String AAC_COMPATIBILITY_WARNING =
            "AAC/M4A 在部分 Chromium/Electron 浏览器（包括某些 VS Code 内置浏览器）可能无法播放，建议优先使用 MP3。";

    private final ObjectMapper objectMapper;
    private final String ffprobePath;
    private final String ffmpegPath;
    private final long timeoutSeconds;

    public AudioUploadValidationService(
            ObjectMapper objectMapper,
            @Value("${media.audio-validation.ffprobe-path:ffprobe}") String ffprobePath,
            @Value("${media.audio-validation.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${media.audio-validation.timeout-seconds:120}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.ffprobePath = ffprobePath;
        this.ffmpegPath = ffmpegPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public AudioValidationResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AudioValidationException("请选择非空音频文件。");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        if (!isSupportedExtension(extension)) {
            throw new AudioValidationException("仅支持 MP3、FLAC、M4A 和 AAC 音频文件。");
        }

        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("jojo-audio-validation-", "." + extension);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }

            JsonNode probe = probe(temporaryFile);
            String formatNames = probe.path("format").path("format_name").asText("").toLowerCase(Locale.ROOT);
            JsonNode audioStream = firstAudioStream(probe.path("streams"));
            String codec = audioStream.path("codec_name").asText("").toLowerCase(Locale.ROOT);
            String detectedFormat = detectFormat(formatNames, codec);
            if (!extensionMatches(extension, formatNames, codec)) {
                throw new AudioValidationException(
                        "文件扩展名为 ." + extension + "，但检测到实际格式为 " + detectedFormat
                                + "（" + (codec.isBlank() ? "未知编码" : codec) + "）。请更正扩展名或选择正确的文件。");
            }

            verifyFullDecode(temporaryFile);

            double duration = probe.path("format").path("duration").asDouble(Double.NaN);
            if (!Double.isFinite(duration) || duration <= 0) {
                throw new AudioValidationException("无法从音频文件中读取有效时长。");
            }

            String warning = "M4A".equals(detectedFormat) || "AAC".equals(detectedFormat)
                    ? AAC_COMPATIBILITY_WARNING
                    : null;
            return new AudioValidationResult(detectedFormat, codec, duration, contentType(detectedFormat), warning);
        } catch (AudioValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AudioValidationException("读取音频文件失败：" + exception.getMessage(), exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    log.warn("Unable to remove temporary audio validation file {}", temporaryFile, exception);
                }
            }
        }
    }

    static boolean extensionMatches(String extension, String formatNames, String codec) {
        String formats = formatNames.toLowerCase(Locale.ROOT);
        String normalizedCodec = codec.toLowerCase(Locale.ROOT);
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "mp3" -> normalizedCodec.equals("mp3") && formats.contains("mp3");
            case "flac" -> normalizedCodec.equals("flac") && formats.contains("flac");
            case "m4a" -> normalizedCodec.equals("aac")
                    && (formats.contains("mp4") || formats.contains("m4a") || formats.contains("mov"));
            case "aac" -> normalizedCodec.equals("aac") && formats.contains("aac")
                    && !formats.contains("mp4") && !formats.contains("m4a") && !formats.contains("mov");
            default -> false;
        };
    }

    private JsonNode probe(Path audioFile) {
        String output = runCommand(
                ffprobePath,
                "-v", "error",
                "-show_entries", "format=format_name,duration:stream=codec_type,codec_name",
                "-of", "json",
                audioFile.toString());
        try {
            JsonNode result = objectMapper.readTree(output);
            if (firstAudioStreamOrNull(result.path("streams")) == null) {
                throw new AudioValidationException("文件中没有可识别的音轨。");
            }
            return result;
        } catch (IOException exception) {
            throw new AudioValidationException("无法识别音频容器或编码。", exception);
        }
    }

    private void verifyFullDecode(Path audioFile) {
        runCommand(
                ffmpegPath,
                "-hide_banner",
                "-v", "error",
                "-xerror",
                "-err_detect", "explode",
                "-i", audioFile.toString(),
                "-map", "0:a:0",
                "-f", "null",
                "-");
    }

    private String runCommand(String executable, String... arguments) {
        Process process;
        try {
            String[] command = new String[arguments.length + 1];
            command[0] = executable;
            System.arraycopy(arguments, 0, command, 1, arguments.length);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new AudioValidationException(
                    "服务器音频校验组件不可用。请安装 FFmpeg/FFprobe，或配置 MEDIA_FFMPEG_PATH 与 MEDIA_FFPROBE_PATH。",
                    exception);
        }

        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread outputReader = new Thread(
                () -> copyOutputTail(process.getInputStream(), capturedOutput, readFailure));
        outputReader.setDaemon(true);
        outputReader.start();

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AudioValidationException("音频校验超时，文件可能过大或编码异常。");
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            if (outputReader.isAlive()) {
                throw new AudioValidationException("读取音频校验结果超时。");
            }
            String output = capturedOutput.toString(StandardCharsets.UTF_8).trim();
            if (readFailure.get() != null) {
                throw new AudioValidationException("读取音频校验结果失败。", readFailure.get());
            }
            if (process.exitValue() != 0) {
                throw new AudioValidationException(
                        "音频文件识别或完整解码失败：" + (output.isBlank() ? "文件可能已损坏。" : output));
            }
            return output;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AudioValidationException("音频校验被中断。", exception);
        }
    }

    private void copyOutputTail(
            InputStream input,
            ByteArrayOutputStream output,
            AtomicReference<IOException> readFailure) {
        byte[] buffer = new byte[1024];
        try (input) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                synchronized (output) {
                    byte[] current = output.toByteArray();
                    int retainedLength = Math.min(current.length, MAX_OUTPUT_LENGTH - length);
                    output.reset();
                    if (retainedLength > 0) {
                        output.write(current, current.length - retainedLength, retainedLength);
                    }
                    int newOffset = Math.max(0, length - MAX_OUTPUT_LENGTH);
                    output.write(buffer, newOffset, length - newOffset);
                }
            }
        } catch (IOException exception) {
            readFailure.set(exception);
        }
    }

    private JsonNode firstAudioStream(JsonNode streams) {
        JsonNode stream = firstAudioStreamOrNull(streams);
        if (stream == null) {
            throw new AudioValidationException("文件中没有可识别的音轨。");
        }
        return stream;
    }

    private JsonNode firstAudioStreamOrNull(JsonNode streams) {
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                if ("audio".equals(stream.path("codec_type").asText())) {
                    return stream;
                }
            }
        }
        return null;
    }

    private String detectFormat(String formatNames, String codec) {
        if ("mp3".equals(codec)) {
            return "MP3";
        }
        if ("flac".equals(codec)) {
            return "FLAC";
        }
        if ("aac".equals(codec)) {
            return formatNames.contains("mp4") || formatNames.contains("m4a") || formatNames.contains("mov")
                    ? "M4A" : "AAC";
        }
        return formatNames.isBlank() ? "未知格式" : formatNames;
    }

    private String contentType(String detectedFormat) {
        return switch (detectedFormat) {
            case "MP3" -> "audio/mpeg";
            case "FLAC" -> "audio/flac";
            case "M4A" -> "audio/mp4";
            case "AAC" -> "audio/aac";
            default -> throw new AudioValidationException("不支持的音频格式。");
        };
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isSupportedExtension(String extension) {
        return extension.equals("mp3") || extension.equals("flac")
                || extension.equals("m4a") || extension.equals("aac");
    }

    public record AudioValidationResult(
            String detectedFormat,
            String codec,
            double durationSeconds,
            String contentType,
            String warning) {
    }

    public static class AudioValidationException extends RuntimeException {
        public AudioValidationException(String message) {
            super(message);
        }

        public AudioValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
