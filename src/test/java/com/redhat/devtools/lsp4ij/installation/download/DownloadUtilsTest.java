/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.installation.download;

import com.intellij.openapi.util.io.NioFiles;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the extraction of the downloaded archives: a {@code .gz}, as rust-analyzer's on Linux and macOS, extracts over
 * the file of an earlier install, a {@code .tar.xz} or {@code .txz} extracts the tar that it holds, and the
 * {@code __MACOSX} folder of a zip made on macOS is neither extracted nor a root folder.
 */
public class DownloadUtilsTest {

    private Path dir;

    @BeforeEach
    void createDir() throws IOException {
        dir = Files.createTempDirectory("lsp4ij-download");
    }

    @AfterEach
    void deleteDir() throws IOException {
        NioFiles.deleteRecursively(dir);
    }

    @Test
    void aGzExtractsOverTheFileOfAnEarlierInstall() throws IOException {
        Path outputDir = Files.createDirectories(dir.resolve("rust-analyzer"));
        Path earlier = Files.writeString(outputDir.resolve("rust-analyzer-x86_64-apple-darwin"), "2025-05-05");
        Path asset = dir.resolve("rust-analyzer-x86_64-apple-darwin.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(asset))) {
            out.write("2025-05-12".getBytes(StandardCharsets.UTF_8));
        }

        Path extracted = decompress(asset, outputDir);

        assertEquals(earlier, extracted);
        assertEquals("2025-05-12", Files.readString(extracted), "the server of the new download must replace the earlier one");
    }

    @Test
    void aTarXzExtractsItsFiles() throws IOException {
        Path asset = dir.resolve("server-1.0.tar.xz");
        Files.write(asset, xz(tar(Map.of("server-1.0/bin/server", "server", "server-1.0/README", "readme"))));

        assertExtracts(asset, "server-1.0", Map.of("bin/server", "server", "README", "readme"));
    }

    @Test
    void aTxzExtractsItsFiles() throws IOException {
        Path asset = dir.resolve("server-1.0.txz");
        Files.write(asset, xz(tar(Map.of("server-1.0/bin/server", "server"))));

        assertExtracts(asset, "server-1.0", Map.of("bin/server", "server"));
    }

    /**
     * The tar of a .tar.xz made of two xz streams, one after the other, as xz decompresses them: IntelliJ's tar
     * extraction, which would decompress the .tar.xz by itself, stops at the end of the first stream.
     */
    @Test
    void aTarXzExtractsTheTarThatItsXzStreamsHold() throws IOException {
        byte[] tar = tar(Map.of("server-1.0/a", "a", "server-1.0/b", "b"));
        // The first stream holds the first entry, a header and a block of content
        int split = 1024;
        Path asset = dir.resolve("server-1.0.tar.xz");
        try (OutputStream out = Files.newOutputStream(asset)) {
            out.write(xz(Arrays.copyOfRange(tar, 0, split)));
            out.write(xz(Arrays.copyOfRange(tar, split, tar.length)));
        }

        assertExtracts(asset, "server-1.0", Map.of("a", "a", "b", "b"));
    }

    /**
     * The zip of netcoredbg 3.2.0 for macOS has the folder netcoredbg, and the folder __MACOSX of the metadata of its
     * files, which macOS adds to the zips it makes: netcoredbg is the root folder, whose content stripRootFolder moves.
     */
    @Test
    void theMacOSMetadataFolderOfAZipIsNeitherExtractedNorARootFolder() throws IOException {
        Path asset = dir.resolve("netcoredbg-osx-arm64.zip");
        Files.write(asset, zip(Map.of("netcoredbg/netcoredbg", "netcoredbg", "netcoredbg/libdbgshim.dylib", "dbgshim",
                "__MACOSX/netcoredbg/._netcoredbg", "metadata")));

        assertExtracts(asset, "netcoredbg", Map.of("netcoredbg", "netcoredbg", "libdbgshim.dylib", "dbgshim"));
        assertFalse(Files.exists(dir.resolve("output").resolve("__MACOSX")), "the metadata of macOS is not extracted");
    }

    @Test
    void aZipWithTwoFoldersHasNoRootFolder() throws IOException {
        Path asset = dir.resolve("server.zip");
        Files.write(asset, zip(Map.of("bin/server", "server", "lib/server.jar", "jar")));
        Path outputDir = Files.createDirectories(dir.resolve("output"));

        assertNull(decompress(asset, outputDir));
        assertEquals("server", Files.readString(outputDir.resolve("bin/server")));
    }

    private void assertExtracts(@NotNull Path asset,
                                @NotNull String root,
                                @NotNull Map<String, String> files) throws IOException {
        Path outputDir = Files.createDirectories(dir.resolve("output"));

        Path extracted = decompress(asset, outputDir);

        assertEquals(outputDir.resolve(root), extracted);
        for (var file : files.entrySet()) {
            Path path = extracted.resolve(file.getKey());
            assertEquals(file.getValue(), Files.isRegularFile(path) ? Files.readString(path) : null, path.toString());
        }
    }

    private static Path decompress(@NotNull Path asset, @NotNull Path outputDir) throws IOException {
        var decompressor = DownloadUtils.getDecompressor(asset);
        assertNotNull(decompressor, asset.toString());
        return decompressor.decompress(asset, outputDir);
    }

    /**
     * Returns a tar of the given files, sorted by name, with their content.
     */
    private static byte[] tar(@NotNull Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            for (String name : files.keySet().stream().sorted().toList()) {
                byte[] content = files.get(name).getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(name);
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Returns a zip of the given files, sorted by name, with their content.
     */
    private static byte[] zip(@NotNull Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : files.keySet().stream().sorted().toList()) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(files.get(name).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] xz(byte[] content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var xz = new XZOutputStream(bytes, new LZMA2Options())) {
            xz.write(content);
        }
        return bytes.toByteArray();
    }
}
