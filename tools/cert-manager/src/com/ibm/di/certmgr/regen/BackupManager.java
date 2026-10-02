/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.regen;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Creates timestamped {@code certificateBackup.MM-dd-yyyy_HH-mm-ss.tar.gz} archives
 * of SDI keystores, properties files, and other critical resources.
 *
 * <p>Mirrors Python {@code backup_sdi_resources}. Key behaviours:
 * <ul>
 *   <li>Archives are placed in {@code <installDir>/maintenance/BACKUP/} when an
 *       install directory is provided, otherwise in {@code baseDir}.</li>
 *   <li>Non-existent source files are silently skipped.</li>
 *   <li>If a filename collision occurs a numeric suffix is appended
 *       ({@code _1}, {@code _2}, …).</li>
 * </ul>
 */
public class BackupManager {

    private static final Logger log = LogManager.getLogger(BackupManager.class);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss");

    private final Path baseDir;
    private final Path installDir;   // nullable

    /**
     * @param baseDir    solution or base directory (must exist)
     * @param installDir optional install directory; when non-null archives go under
     *                   {@code <installDir>/maintenance/BACKUP/}
     */
    public BackupManager(final Path baseDir, final Path installDir) {
        this.baseDir    = baseDir;
        this.installDir = installDir;
    }

    /**
     * Create a {@code .tar.gz} archive of the given files.
     *
     * <p>Files that do not exist are skipped. Returns empty when no files exist.
     *
     * @param files paths to include in the archive
     * @return path to the created archive, or empty on failure
     */
    public Optional<Path> createArchive(final List<Path> files) throws IOException {
        Path destDir = resolveDestDir();
        Files.createDirectories(destDir);

        String ts       = LocalDateTime.now().format(TS_FMT);
        Path archivePath = uniqueArchivePath(destDir, ts);

        List<Path> existing = files.stream().filter(Files::exists).toList();
        if (existing.isEmpty()) {
            log.error("No files found to backup");
            return Optional.empty();
        }

        try (OutputStream fos  = Files.newOutputStream(archivePath);
             OutputStream bos  = new BufferedOutputStream(fos);
             OutputStream gzip = new GzipCompressorOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {

            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            for (Path f : existing) {
                TarArchiveEntry entry = new TarArchiveEntry(f, f.getFileName().toString());
                tar.putArchiveEntry(entry);
                Files.copy(f, tar);
                tar.closeArchiveEntry();
            }
        }

        log.info("Backup created: {} ({} files)", archivePath.getFileName(), existing.size());
        return Optional.of(archivePath);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path resolveDestDir() {
        if (installDir != null) {
            return installDir.resolve("maintenance").resolve("BACKUP");
        }
        return baseDir;
    }

    private static Path uniqueArchivePath(final Path destDir, final String ts) {
        Path candidate = destDir.resolve("certificateBackup." + ts + ".tar.gz");
        int counter = 0;
        while (Files.exists(candidate)) {
            counter++;
            candidate = destDir.resolve("certificateBackup." + ts + "_" + counter + ".tar.gz");
        }
        return candidate;
    }
}
