package com.itsaky.androidide.actions.editor

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class OpenCodeRuntimeInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pins the selected OpenCode and Debian ARM64 assets`() {
        assertThat(OpenCodeRuntimeInstaller.OPENCODE_URL)
            .isEqualTo("https://github.com/SuperAppMan/mix/releases/download/opencode-1.18.31-android.3/opencode-linux-arm64.tar.gz")
        assertThat(OpenCodeRuntimeInstaller.OPENCODE_SIZE).isEqualTo(60_385_103L)
        assertThat(OpenCodeRuntimeInstaller.OPENCODE_SHA256)
            .isEqualTo("195d39b9e993e2eb31d42931482b124c1d89165f14be01328fe577da9ecacaa7")
        assertThat(OpenCodeRuntimeInstaller.DEBIAN_BLOB_URL)
            .contains("registry-1.docker.io/v2/library/debian/blobs/sha256:")
        assertThat(OpenCodeRuntimeInstaller.DEBIAN_LAYER_SIZE).isEqualTo(28_137_951L)
        assertThat(OpenCodeRuntimeInstaller.DEBIAN_LAYER_SHA256)
            .isEqualTo("f791e47cf52abf7b364878f2141547e3781072e437f599f6632849d88fca8ca4")
        assertThat(OpenCodeRuntimeInstaller.DEBIAN_ARM64_MANIFEST_DIGEST)
            .isEqualTo("sha256:7ccb8b5b60c7654f5b795d94ee7d751cd0811500803f0b09cefcd555e755a55a")
    }

    @Test
    fun `discovers the sole AArch64 ELF without assuming an archive member name`() {
        val archive = archive("vendor/build-output-7" to arm64ElfHeader())
        val payload = temporaryFolder.newFolder("unfamiliar-payload")
        OpenCodeTarExtractor(setMode = { _, _ -> }).extract(archive, payload, maxExpandedBytes = 1024)
        val installer = OpenCodeRuntimeInstaller(
            prefix = temporaryFolder.newFolder("runtime-prefix"),
            cacheDirectory = temporaryFolder.newFolder("runtime-cache"),
        )

        assertThat(installer.findArm64Executable(payload).relativeTo(payload).invariantSeparatorsPath)
            .isEqualTo("vendor/build-output-7")
    }

    @Test
    fun `rejects ambiguous AArch64 ELF members`() {
        val archive = archive("bin/opencode" to arm64ElfHeader(), "lib/extra" to arm64ElfHeader())
        val payload = temporaryFolder.newFolder("ambiguous-payload")
        OpenCodeTarExtractor(setMode = { _, _ -> }).extract(archive, payload, maxExpandedBytes = 1024)
        val installer = OpenCodeRuntimeInstaller(
            prefix = temporaryFolder.newFolder("ambiguous-prefix"),
            cacheDirectory = temporaryFolder.newFolder("ambiguous-cache"),
        )

        assertThat(runCatching { installer.findArm64Executable(payload) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `extracts regular Debian tar entries under the destination`() {
        val archive = archive("usr/bin/tool" to "test executable".toByteArray())
        val destination = temporaryFolder.newFolder("rootfs")
        val extractor = OpenCodeTarExtractor(
            setMode = { file, mode -> file.setExecutable(mode and 0b001_000_000 != 0, false) },
        )

        extractor.extract(archive, destination, maxExpandedBytes = 1024)

        val extracted = File(destination, "usr/bin/tool")
        assertThat(extracted.readText()).isEqualTo("test executable")
    }

    @Test
    fun `rejects parent traversal and absolute tar paths`() {
        val extractor = OpenCodeTarExtractor()
        assertThat(runCatching { extractor.normalizeTarPath("../outside") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { extractor.normalizeTarPath("/etc/passwd") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(extractor.normalizeTarPath("./usr/bin/opencode")).isEqualTo("usr/bin/opencode")
    }

    @Test
    fun `rejects archives whose expanded payload exceeds the configured cap`() {
        val archive = archive("usr/share/blob" to ByteArray(128) { 1 })
        val destination = temporaryFolder.newFolder("limited-rootfs")
        val extractor = OpenCodeTarExtractor(
            setMode = { _, _ -> },
        )

        assertThat(runCatching { extractor.extract(archive, destination, maxExpandedBytes = 32) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `launch command is loopback-only and safely quotes app paths`() {
        val prefix = temporaryFolder.newFolder("prefix")
        val proot = File(prefix, "bin/proot").apply { parentFile?.mkdirs(); writeText("stub"); setExecutable(true) }
        val rootfs = temporaryFolder.newFolder("Debian rootfs")
        val project = temporaryFolder.newFolder("Project O'Brien")
        val resolver = File(prefix, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText("nameserver 127.0.0.1\n") }

        val command = OpenCodeRuntimeController.buildServerCommand(proot, rootfs, project, resolver, prefix)

        assertThat(command).contains("-r '${rootfs.absolutePath}'")
        assertThat(command).contains("${project.canonicalPath.replace("'", "'\\''")}:/workspace/project")
        assertThat(command).contains("'\\''")
        assertThat(command).contains("--hostname 127.0.0.1 --port 4098")
        assertThat(command).contains("-b /dev -b /proc -b /sys")
        assertThat(command).contains("/etc/resolv.conf")
        assertThat(command).contains("OPENCODE_CONFIG_CONTENT=")
        assertThat(command).contains("\"plan\":{\"permission\":{\"*\":\"deny\"")
        assertThat(command).contains("\"edit\":\"deny\"")
        assertThat(command).contains("\"write\":\"deny\"")
        assertThat(command).contains("\"patch\":\"deny\"")
        assertThat(command).contains("\"bash\":\"deny\"")
        assertThat(command).contains("\"task\":\"deny\"")
        assertThat(command).contains("\"build\":{\"permission\":{\"read\":\"allow\",\"edit\":\"allow\",\"write\":\"allow\",\"patch\":\"allow\"}")
        assertThat(command).doesNotContain("--hostname 0.0.0.0")
    }

    private fun archive(vararg files: Pair<String, ByteArray>): File {
        val archive = temporaryFolder.newFile("fixture-${System.nanoTime()}.tar.gz")
        GzipCompressorOutputStream(FileOutputStream(archive)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((path, data) in files) {
                    val entry = TarArchiveEntry(path).apply {
                        size = data.size.toLong()
                        mode = 0b111_101_101
                    }
                    tar.putArchiveEntry(entry)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return archive
    }

    private fun arm64ElfHeader(): ByteArray = ByteArray(20).apply {
        this[0] = 0x7f.toByte()
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[18] = 183.toByte()
    }
}
