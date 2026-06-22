package org.nrg.xnatx.plugins.structimport.importer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ArchiveExtractorTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private Path root;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("archive-extractor-test");
    }

    @After
    public void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                final List<Path> reverse = new ArrayList<>();
                stream.forEach(reverse::add);
                Collections.reverse(reverse);
                for (final Path path : reverse) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void extractsZipEntriesIncludingNestedDirectories() throws Exception {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("top.txt", "top".getBytes(StandardCharsets.UTF_8));
        entries.put("dir/nested.txt", "nested".getBytes(StandardCharsets.UTF_8));
        ArchiveExtractor.extract(new ByteArrayInputStream(zip(entries)), "payload.zip", root);

        assertThat(new String(Files.readAllBytes(root.resolve("top.txt")), StandardCharsets.UTF_8), equalTo("top"));
        assertThat(new String(Files.readAllBytes(root.resolve("dir/nested.txt")), StandardCharsets.UTF_8), equalTo("nested"));
    }

    @Test
    public void extractsTarEntries() throws Exception {
        final Map<String, byte[]> entries = Collections.singletonMap("data.txt", "tar-content".getBytes(StandardCharsets.UTF_8));
        ArchiveExtractor.extract(new ByteArrayInputStream(tar(entries, false)), "payload.tar", root);

        assertThat(new String(Files.readAllBytes(root.resolve("data.txt")), StandardCharsets.UTF_8), equalTo("tar-content"));
    }

    @Test
    public void extractsTgzEntries() throws Exception {
        final Map<String, byte[]> entries = Collections.singletonMap("data.txt", "tgz-content".getBytes(StandardCharsets.UTF_8));
        ArchiveExtractor.extract(new ByteArrayInputStream(tar(entries, true)), "payload.tgz", root);

        assertThat(new String(Files.readAllBytes(root.resolve("data.txt")), StandardCharsets.UTF_8), equalTo("tgz-content"));
    }

    @Test
    public void unsupportedFormatThrowsClientException() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("Unsupported format"));
        ArchiveExtractor.extract(new ByteArrayInputStream(new byte[0]), "payload.txt", root);
    }

    @Test
    public void zipWithTraversalEntryIsRejected() throws Exception {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../escape.txt", "owned".getBytes(StandardCharsets.UTF_8));
        try {
            ArchiveExtractor.extract(new ByteArrayInputStream(zip(entries)), "evil.zip", root);
            org.junit.Assert.fail("Expected the traversal entry to be rejected");
        } catch (IOException e) {
            assertThat(e.getMessage(), containsString("escapes the extraction directory"));
        }
        assertThat("no file should have been written outside the extraction root",
                   Files.exists(root.getParent().resolve("escape.txt")), is(false));
    }

    @Test
    public void extractEntryRejectsParentTraversal() throws Exception {
        thrown.expect(IOException.class);
        thrown.expectMessage(containsString("escapes the extraction directory"));
        ArchiveExtractor.extractEntry(new ByteArrayInputStream(new byte[0]), root, "../escape.txt", false);
    }

    @Test
    public void extractEntryRejectsDeepTraversalThatNormalizesOutsideRoot() throws Exception {
        thrown.expect(IOException.class);
        thrown.expectMessage(containsString("escapes the extraction directory"));
        ArchiveExtractor.extractEntry(new ByteArrayInputStream(new byte[0]), root, "a/b/../../../escape.txt", false);
    }

    @Test
    public void extractEntryAllowsLegitimateNestedPath() throws Exception {
        ArchiveExtractor.extractEntry(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)), root, "a/b/c.txt", false);
        assertThat(new String(Files.readAllBytes(root.resolve("a/b/c.txt")), StandardCharsets.UTF_8), equalTo("ok"));
    }

    @Test
    public void extractEntryCreatesDirectoryEntries() throws Exception {
        ArchiveExtractor.extractEntry(new ByteArrayInputStream(new byte[0]), root, "just-a-dir/", true);
        assertThat(Files.isDirectory(root.resolve("just-a-dir")), is(true));
    }

    @Test
    public void extractNestedUnpacksAndRemovesInnerArchives() throws Exception {
        final Map<String, byte[]> inner = Collections.singletonMap("inner/data.txt", "deep".getBytes(StandardCharsets.UTF_8));
        final Path innerArchive = root.resolve("payload.zip");
        Files.write(innerArchive, zip(inner));

        ArchiveExtractor.extractNested(root);

        assertThat(new String(Files.readAllBytes(root.resolve("inner/data.txt")), StandardCharsets.UTF_8), equalTo("deep"));
        assertThat("the nested archive should be removed after extraction", Files.exists(innerArchive), is(false));
    }

    private static byte[] zip(final Map<String, byte[]> entries) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (final ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static byte[] tar(final Map<String, byte[]> entries, final boolean gzip) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final OutputStream          sink = gzip ? new GZIPOutputStream(baos) : baos;
        try (final TarArchiveOutputStream tar = new TarArchiveOutputStream(sink)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                final TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }
}
