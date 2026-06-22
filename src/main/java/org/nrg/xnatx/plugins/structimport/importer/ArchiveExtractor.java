package org.nrg.xnatx.plugins.structimport.importer;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.nrg.action.ClientException;
import org.nrg.xft.utils.fileExtraction.Format;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts upload archives (ZIP, TAR, TGZ) into a working directory.
 * <p>
 * Every entry is resolved against the destination root and rejected if it would
 * escape that root (a "Zip Slip" path-traversal attempt), so a malicious archive
 * cannot write outside the extraction directory.
 */
@Slf4j
final class ArchiveExtractor {

    private ArchiveExtractor() {
    }

    /**
     * Extracts a single archive stream into {@code destDir}.
     *
     * @param input       the archive content
     * @param archiveName the archive file name, used to determine the format
     * @param destDir     the directory the archive is extracted into
     *
     * @throws ClientException if the archive format is not supported
     * @throws IOException     if the archive cannot be read or an entry escapes {@code destDir}
     */
    static void extract(final InputStream input, final String archiveName, final Path destDir) throws ClientException, IOException {
        final Format archiveFormat = Format.getFormat(archiveName);
        switch (archiveFormat) {
            case ZIP:
                extractZip(input, destDir);
                break;
            case TAR:
            case TGZ:
                extractTar(input, destDir, archiveFormat == Format.TGZ);
                break;
            default:
                throw new ClientException("Unsupported format " + archiveFormat + " for " + archiveName);
        }
    }

    /**
     * Repeatedly extracts and removes any archives found within {@code root}
     * until no archives remain, so archives nested inside the uploaded archive
     * are unpacked in place.
     *
     * @param root the directory to scan for nested archives
     *
     * @throws ClientException if a nested archive has an unsupported format
     * @throws IOException     if a nested archive cannot be read or an entry escapes its directory
     */
    static void extractNested(final Path root) throws ClientException, IOException {
        while (true) {
            final List<Path> archives;
            try (final Stream<Path> stream = Files.walk(root)) {
                archives = stream.filter(Files::isRegularFile)
                                 .filter(path -> Format.UNKNOWN != Format.getFormat(path.getFileName().toString()))
                                 .collect(Collectors.toList());
            }
            if (archives.isEmpty()) {
                return;
            }
            for (final Path archive : archives) {
                log.debug("Extracting nested archive {}", archive);
                try (final InputStream input = Files.newInputStream(archive)) {
                    extract(input, archive.getFileName().toString(), archive.getParent());
                }
                Files.delete(archive);
            }
        }
    }

    private static void extractZip(final InputStream input, final Path destDir) throws IOException {
        try (final ZipInputStream zipInputStream = new ZipInputStream(input)) {
            ZipEntry zipEntry;
            while (null != (zipEntry = zipInputStream.getNextEntry())) {
                extractEntry(zipInputStream, destDir, zipEntry.getName(), zipEntry.isDirectory());
            }
        }
    }

    private static void extractTar(final InputStream input, final Path destDir, final boolean gzipped) throws IOException {
        final InputStream buffered = new BufferedInputStream(input);
        try (final TarArchiveInputStream tarInputStream = new TarArchiveInputStream(gzipped ? new GZIPInputStream(buffered) : buffered)) {
            TarArchiveEntry tarEntry;
            while (null != (tarEntry = tarInputStream.getNextEntry())) {
                extractEntry(tarInputStream, destDir, tarEntry.getName(), tarEntry.isDirectory());
            }
        }
    }

    /**
     * Writes a single archive entry into {@code destDir}, rejecting any entry
     * whose resolved location would fall outside {@code destDir}.
     */
    static void extractEntry(final InputStream input, final Path destDir, final String name, final boolean isDirectory) throws IOException {
        final Path normalRoot = destDir.normalize();
        final Path target      = normalRoot.resolve(name).normalize();
        if (!target.startsWith(normalRoot)) {
            throw new IOException("Archive entry \"" + name + "\" escapes the extraction directory " + normalRoot);
        }
        if (isDirectory) {
            Files.createDirectories(target);
        } else {
            FileUtils.createParentDirectories(target.toFile());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
