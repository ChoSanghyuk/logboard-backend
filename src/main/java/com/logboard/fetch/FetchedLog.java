package com.logboard.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Result of a fetch: one UTF-8 raw file (LF line endings) holding all source files merged oldest first.
 * {@code sources} tells which lines came from which remote file. {@code activeFileMissing}: the active logPath
 * did not exist, so only rotated files were fetched. Saved next to the raw file as {@code {raw}.meta.json}
 * so a later re-crop from the raw file has the same metadata.
 */
public record FetchedLog(Path rawFile, int totalLines, boolean activeFileMissing, List<Source> sources) {

    /**
     * Lines {@code firstLine .. firstLine + lineCount - 1} (1-based) of the raw file. {@code date} = the file's date
     * (rotated: from its name; active: last-modified date). Time-only patterns (LOGBACK_DEFAULT) take the date from it.
     */
    public record Source(String remotePath, int firstLine, int lineCount, LocalDate date) {}

    private record Meta(int totalLines, boolean activeFileMissing, List<Source> sources) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static Path metaPath(Path rawFile) {
        return rawFile.resolveSibling(rawFile.getFileName() + ".meta.json");
    }

    void writeMeta() {
        try {
            Files.writeString(metaPath(rawFile), JSON.writeValueAsString(new Meta(totalLines, activeFileMissing, sources)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads {@code {raw}.meta.json} written by the fetch that produced {@code rawFile}. */
    public static FetchedLog read(Path rawFile) {
        try {
            Meta m = JSON.readValue(Files.readString(metaPath(rawFile)), Meta.class);
            return new FetchedLog(rawFile, m.totalLines(), m.activeFileMissing(), m.sources());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
