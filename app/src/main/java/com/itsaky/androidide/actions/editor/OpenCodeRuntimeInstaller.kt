package com.itsaky.androidide.actions.editor

import android.os.Build
import android.system.Os
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class OpenCodeInstallProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val assetName: String = "",
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

internal data class OpenCodeInstallation(
    val version: String,
    val runtimeDirectory: File,
    val versionOutput: String,
)

/** Installs a checksum-pinned Linux ARM64 OpenCode binary inside a Debian rootfs under the app prefix. */
internal class OpenCodeRuntimeInstaller(
    private val prefix: File = Environment.PREFIX,
    private val cacheDirectory: File,
    private val openDependencyAsset: (String) -> InputStream = {
        throw IOException("The bundled OpenCode dependency asset is unavailable")
    },
) {
    val isSupportedDevice: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.contains(TARGET_ABI)

    fun runtimeDirectory(): File = File(File(prefix, RUNTIME_PATH), VERSION)

    fun hasRuntimeDependencies(): Boolean {
        return OpenCodeBundledDependenciesInstaller.hasRuntimeDependencies(prefix)
    }

    suspend fun installedVersion(): String? = withContext(Dispatchers.IO) {
        if (!isSupportedDevice || !hasRuntimeDependencies()) return@withContext null
        val directory = runtimeDirectory()
        val marker = File(directory, INSTALL_MARKER)
        val binary = File(directory, "rootfs/usr/local/bin/opencode")
        if (!marker.isFile || !binary.canExecute()) return@withContext null
        runCatching {
            val json = JSONObject(marker.readText())
            if (json.optString("version") != VERSION ||
                json.optString("opencode_sha256") != OPENCODE_SHA256 ||
                json.optString("debian_layer_sha256") != DEBIAN_LAYER_SHA256
            ) {
                null
            } else {
                verifyArm64Elf(binary, "opencode")
                VERSION
            }
        }.getOrNull()
    }

    suspend fun install(
        onProgress: suspend (OpenCodeInstallProgress) -> Unit = {},
    ): OpenCodeInstallation = withContext(Dispatchers.IO) {
        if (!isSupportedDevice) throw IOException("The OpenCode runtime requires a 64-bit ARM Android device")
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create a private download cache")
        }
        OpenCodeBundledDependenciesInstaller(prefix, cacheDirectory, openDependencyAsset).installIfNeeded(onProgress)
        installedVersion()?.let {
            return@withContext OpenCodeInstallation(VERSION, runtimeDirectory(), "OpenCode $VERSION")
        }
        if (cacheDirectory.usableSpace < MINIMUM_FREE_BYTES || prefix.usableSpace < MINIMUM_FREE_BYTES) {
            throw IOException("At least 512 MiB of free app storage is required to prepare the Debian runtime")
        }

        val token = dockerPullToken()
        val opencodeArchive = File.createTempFile("opencode-$VERSION-", ".tar.gz", cacheDirectory)
        val debianLayer = File.createTempFile("debian-bookworm-arm64-", ".tar.gz", cacheDirectory)
        try {
            downloadPinned(
                url = URL(OPENCODE_URL),
                destination = opencodeArchive,
                expectedSize = OPENCODE_SIZE,
                expectedSha256 = OPENCODE_SHA256,
                label = "OpenCode",
                allowedHosts = OPENCODE_DOWNLOAD_HOSTS,
                headers = emptyMap(),
                onProgress = onProgress,
            )
            downloadPinned(
                url = URL(DEBIAN_BLOB_URL),
                destination = debianLayer,
                expectedSize = DEBIAN_LAYER_SIZE,
                expectedSha256 = DEBIAN_LAYER_SHA256,
                label = "Debian bookworm-slim ARM64",
                allowedHosts = DEBIAN_DOWNLOAD_HOSTS,
                headers = mapOf("Authorization" to "Bearer $token"),
                onProgress = onProgress,
            )
            installVerifiedArchives(opencodeArchive, debianLayer)
        } finally {
            opencodeArchive.delete()
            debianLayer.delete()
        }
    }

    private suspend fun dockerPullToken(): String = withContext(Dispatchers.IO) {
        val connection = openHttpsConnection(URL(DOCKER_TOKEN_URL))
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Could not authorize a read-only download from the official Debian image registry")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            json.optString("token").takeIf { it.isNotBlank() }
                ?: throw IOException("The Debian image registry did not return a pull token")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadPinned(
        url: URL,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        label: String,
        allowedHosts: Set<String>,
        headers: Map<String, String>,
        onProgress: suspend (OpenCodeInstallProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = openHttpsConnection(url)
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("$label download failed (HTTP $responseCode)")
            }
            val finalUrl = connection.url
            if (finalUrl.protocol != "https" || finalUrl.host.lowercase(Locale.ROOT) !in allowedHosts) {
                throw IOException("$label download redirected to an untrusted HTTPS host")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPercent = -1
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { fileOutput ->
                    BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > expectedSize) throw IOException("$label download exceeded its pinned size")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = ((downloaded * 100L) / expectedSize).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(OpenCodeInstallProgress(downloaded, expectedSize, label))
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                    }
                }
            }
            if (downloaded != expectedSize) throw IOException("$label download was incomplete")
            if (!digest.digest().toHex().equals(expectedSha256, ignoreCase = true)) {
                throw IOException("$label SHA-256 verification failed")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installVerifiedArchives(opencodeArchive: File, debianLayer: File): OpenCodeInstallation {
        val parent = runtimeDirectory().parentFile ?: throw IOException("Invalid runtime path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create the private runtime directory")
        val stage = File(parent, ".stage-${UUID.randomUUID()}")
        val backup = File(parent, ".backup-${UUID.randomUUID()}")
        if (!stage.mkdirs()) throw IOException("Could not create a temporary runtime directory")
        var oldMoved = false
        try {
            val rootfs = File(stage, "rootfs")
            val payload = File(stage, "opencode-payload")
            if (!rootfs.mkdirs() || !payload.mkdirs()) throw IOException("Could not create the Debian staging directories")
            OpenCodeTarExtractor().extract(debianLayer, rootfs, MAX_ROOTFS_BYTES)
            ensurePrivateDirectory(rootfs, File(rootfs, "workspace"))
            ensurePrivateDirectory(rootfs, File(rootfs, "workspace/project"))
            OpenCodeTarExtractor().extract(opencodeArchive, payload, MAX_OPENCODE_BYTES)
            val opencodeExecutable = findArm64Executable(payload)
            val installBin = File(rootfs, "usr/local/bin")
            if (!installBin.exists() && !installBin.mkdirs()) throw IOException("Could not create Debian's /usr/local/bin")
            val binary = File(installBin, "opencode")
            opencodeExecutable.copyTo(binary, overwrite = false)
            if (!binary.setExecutable(true, false)) throw IOException("Could not mark the OpenCode binary executable")
            verifyArm64Elf(binary, "opencode")
            payload.deleteRecursively()

            val versionOutput = runVersion(stage)
            if (!VERSION_TOKEN_REGEX.containsMatchIn(versionOutput)) {
                throw IOException("The installed binary did not report OpenCode $VERSION")
            }
            File(stage, INSTALL_MARKER).writeText(
                JSONObject()
                    .put("version", VERSION)
                    .put("opencode_sha256", OPENCODE_SHA256)
                    .put("debian_layer_sha256", DEBIAN_LAYER_SHA256)
                    .put("debian_manifest", DEBIAN_ARM64_MANIFEST_DIGEST)
                    .put("installed_at_ms", System.currentTimeMillis())
                    .toString(),
            )
            val finalDirectory = runtimeDirectory()
            if (finalDirectory.exists()) {
                if (!finalDirectory.renameTo(backup)) throw IOException("Could not replace the incomplete OpenCode runtime")
                oldMoved = true
            }
            if (!stage.renameTo(finalDirectory)) {
                if (oldMoved) backup.renameTo(finalDirectory)
                throw IOException("Could not activate the verified OpenCode runtime")
            }
            if (oldMoved) backup.deleteRecursively()
            return OpenCodeInstallation(VERSION, finalDirectory, versionOutput)
        } catch (error: Exception) {
            stage.deleteRecursively()
            if (oldMoved && !runtimeDirectory().exists() && backup.exists()) backup.renameTo(runtimeDirectory())
            if (error is IOException) throw error
            throw IOException("Could not install the OpenCode Debian runtime", error)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun ensurePrivateDirectory(root: File, directory: File) {
        val parent = directory.parentFile ?: throw IOException("Invalid Debian mountpoint")
        if (directory.exists()) {
            val canonicalPath = directory.canonicalPath
            if (!directory.isDirectory || isSymbolicLink(directory) ||
                !canonicalPath.startsWith(root.canonicalPath + File.separator)
            ) {
                throw IOException("The Debian project mountpoint is not a safe private directory")
            }
            return
        }
        if (!parent.isDirectory || isSymbolicLink(parent) || !directory.mkdir()) {
            throw IOException("Could not prepare the Debian project mountpoint")
        }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        (Os.lstat(file.absolutePath).st_mode and 0xf000) == 0xa000
    }.getOrDefault(false)

    private fun runVersion(stagedRuntime: File): String {
        val proot = File(prefix, "bin/proot")
        val rootfs = File(stagedRuntime, "rootfs")
        val process = ProcessBuilder(
            proot.absolutePath,
            "--link2symlink",
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "/usr/bin/env",
            "-u",
            "LD_LIBRARY_PATH",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "TMPDIR=/tmp",
            "/usr/local/bin/opencode",
            "--version",
        ).apply {
            environment()["PREFIX"] = prefix.absolutePath
            environment()["PATH"] = "${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin"
            environment()["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath
            environment()["HOME"] = File(prefix, "home").absolutePath
            environment()["TMPDIR"] = File(prefix, "tmp").absolutePath
            environment()["PROOT_TMP_DIR"] = File(prefix, "tmp").absolutePath
            redirectErrorStream(true)
        }.start()
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("OpenCode did not start inside the Debian rootfs before the verification timeout")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_VERSION_OUTPUT_CHARS) }.trim()
        if (process.exitValue() != 0) throw IOException("The OpenCode executable could not start in Debian: ${output.take(240)}")
        return output
    }

    private fun openHttpsConnection(url: URL): HttpURLConnection {
        if (url.protocol != "https") throw IOException("Only HTTPS runtime downloads are allowed")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECTION_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
    }

    internal fun verifyArm64Elf(file: File, name: String) {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) throw IOException("$name is not a valid ARM64 ELF file")
                offset += count
            }
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() || header[5] != 1.toByte() || machine != ELF_MACHINE_AARCH64
        ) {
            throw IOException("$name is not a 64-bit little-endian AArch64 ELF executable")
        }
    }

    internal fun findArm64Executable(directory: File): File {
        val candidates = directory.walkTopDown()
            .filter { it.isFile }
            .filter { file -> runCatching { verifyArm64Elf(file, file.name) }.isSuccess }
            .toList()
        if (candidates.size != 1) {
            throw IOException("The pinned OpenCode archive must contain exactly one 64-bit AArch64 ELF binary")
        }
        return candidates.single()
    }

    internal fun isExpectedVersion(output: String): Boolean = VERSION_TOKEN_REGEX.containsMatchIn(output)

    companion object {
        const val VERSION = "1.18.31"
        const val TARGET_ABI = "arm64-v8a"
        const val OPENCODE_URL = "https://github.com/SuperAppMan/mix/releases/download/opencode-1.18.31-android.3/opencode-linux-arm64.tar.gz"
        const val OPENCODE_SHA256 = "195d39b9e993e2eb31d42931482b124c1d89165f14be01328fe577da9ecacaa7"
        const val OPENCODE_SIZE = 60_385_103L
        const val DEBIAN_BLOB_URL = "https://registry-1.docker.io/v2/library/debian/blobs/sha256:f791e47cf52abf7b364878f2141547e3781072e437f599f6632849d88fca8ca4"
        const val DEBIAN_LAYER_SHA256 = "f791e47cf52abf7b364878f2141547e3781072e437f599f6632849d88fca8ca4"
        const val DEBIAN_LAYER_SIZE = 28_137_951L
        const val DEBIAN_ARM64_MANIFEST_DIGEST = "sha256:7ccb8b5b60c7654f5b795d94ee7d751cd0811500803f0b09cefcd555e755a55a"
        const val RUNTIME_PATH = "opt/opencode-proot-runtime"
        const val HOST = "127.0.0.1"
        const val PORT = 4098
        private const val DOCKER_TOKEN_URL = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull"
        private const val INSTALL_MARKER = "installed.json"
        private const val USER_AGENT = "CodeOnTheGo-OpenCode-Runtime"
        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECTION_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val VERSION_TIMEOUT_SECONDS = 30L
        private const val MAX_VERSION_OUTPUT_CHARS = 8 * 1024
        private const val MINIMUM_FREE_BYTES = 512L * 1024L * 1024L
        private const val MAX_ROOTFS_BYTES = 1_500L * 1024L * 1024L
        private const val MAX_OPENCODE_BYTES = 400L * 1024L * 1024L
        private const val ELF_MACHINE_AARCH64 = 183
        private val VERSION_TOKEN_REGEX = Regex("(?<![0-9])${Regex.escape(VERSION)}(?![0-9])")
        private val OPENCODE_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
        )
        private val DEBIAN_DOWNLOAD_HOSTS = setOf("registry-1.docker.io", "production.cloudflare.docker.com")
    }
}

/** Extracts only safe tar paths and delays link creation until regular files are in place. */
internal class OpenCodeTarExtractor(
    private val createSymlink: (String, File) -> Unit = { target, link -> Os.symlink(target, link.absolutePath) },
    private val createHardlink: (File, File) -> Unit = { target, link -> Os.link(target.absolutePath, link.absolutePath) },
    private val setMode: (File, Int) -> Unit = { file, mode -> Os.chmod(file.absolutePath, mode and 0x0fff) },
) {
    fun extract(archive: File, destination: File, maxExpandedBytes: Long) {
        if (!destination.exists() && !destination.mkdirs()) throw IOException("Could not create the extraction directory")
        val root = destination.canonicalFile
        val createdPaths = mutableSetOf<String>()
        val directoryModes = mutableListOf<Pair<File, Int>>()
        val fileModes = mutableListOf<Pair<File, Int>>()
        val hardlinks = mutableListOf<LinkRecord>()
        val symlinks = mutableListOf<LinkRecord>()
        var expandedBytes = 0L
        var entryCount = 0
        GzipCompressorInputStream(BufferedInputStream(archive.inputStream()), true).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.getNextEntry() ?: break
                    entryCount++
                    if (entryCount > MAX_TAR_ENTRIES) throw IOException("The runtime archive contains too many entries")
                    val path = normalizeTarPath(entry.name)
                    if (path.isEmpty()) continue
                    val leaf = path.substringAfterLast('/')
                    if (leaf.startsWith(".wh.")) continue
                    val output = File(root, path)
                    if (!output.canonicalPath.startsWith(root.path + File.separator)) {
                        throw IOException("The runtime archive contains a path outside its private destination")
                    }
                    when {
                        entry.isDirectory -> {
                            if (output.exists() && !output.isDirectory) throw IOException("The runtime archive has conflicting entries")
                            if (!output.exists() && !output.mkdirs()) throw IOException("Could not create a Debian rootfs directory")
                            directoryModes += output to entry.mode
                        }
                        entry.isSymbolicLink -> {
                            if (!createdPaths.add(path) || output.exists()) throw IOException("The runtime archive contains a duplicate path")
                            validateSymlinkTarget(path, entry.linkName)
                            symlinks += LinkRecord(path, entry.linkName, entry.mode)
                        }
                        entry.isLink -> {
                            if (!createdPaths.add(path) || output.exists()) throw IOException("The runtime archive contains a duplicate path")
                            hardlinks += LinkRecord(path, normalizeTarPath(entry.linkName), entry.mode)
                        }
                        entry.isFile -> {
                            if (!createdPaths.add(path) || output.exists()) throw IOException("The runtime archive contains a duplicate path")
                            if (entry.size < 0L || entry.size > maxExpandedBytes - expandedBytes) {
                                throw IOException("The runtime archive expands beyond its safety limit")
                            }
                            val parent = output.parentFile ?: throw IOException("The runtime archive has an invalid path")
                            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create an extracted file's parent directory")
                            FileOutputStream(output).use { fileOutput ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var copied = 0L
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    expandedBytes += count
                                    if (copied > entry.size || expandedBytes > maxExpandedBytes) {
                                        throw IOException("The runtime archive expands beyond its safety limit")
                                    }
                                    fileOutput.write(buffer, 0, count)
                                }
                                if (copied != entry.size) throw IOException("The runtime archive contains a truncated file")
                                fileOutput.fd.sync()
                            }
                            fileModes += output to entry.mode
                        }
                    }
                }
            }
        }
        hardlinks.forEach { link ->
            val target = File(root, link.target)
            val output = File(root, link.path)
            if (!target.isFile || target.isSymbolicLinkCompat()) throw IOException("The runtime archive contains an invalid hard link")
            val parent = output.parentFile ?: throw IOException("The runtime archive has an invalid hard link")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create a hard link directory")
            createHardlink(target, output)
        }
        symlinks.forEach { link ->
            val output = File(root, link.path)
            val parent = output.parentFile ?: throw IOException("The runtime archive has an invalid symbolic link")
            ensureNoSymlinkParents(root, output)
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create a symbolic link directory")
            createSymlink(link.target, output)
        }
        fileModes.forEach { (file, mode) -> setMode(file, mode) }
        directoryModes.asReversed().forEach { (directory, mode) -> setMode(directory, mode) }
    }

    internal fun normalizeTarPath(value: String): String {
        if (value.isEmpty() || value.startsWith('/') || value.startsWith('\\') || value.contains('\u0000')) {
            if (value == "" || value == "." || value == "./") return ""
            throw IOException("The runtime archive contains an absolute or invalid path")
        }
        val parts = value.split('/')
        if (parts.any { it == ".." || it.contains('\\') }) throw IOException("The runtime archive contains a path traversal entry")
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    private fun validateSymlinkTarget(path: String, target: String) {
        if (target.isEmpty() || target.contains('\u0000') || target.contains('\\')) {
            throw IOException("The runtime archive contains an invalid symbolic link")
        }
        if (target.startsWith('/')) return
        val stack = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) throw IOException("The runtime archive contains a symbolic link outside its root") else stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
    }

    private fun ensureNoSymlinkParents(root: File, output: File) {
        var parent = output.parentFile
        while (parent != null && parent != root) {
            if (parent.exists() && parent.isSymbolicLinkCompat()) {
                throw IOException("The runtime archive places a link beneath a symbolic link")
            }
            parent = parent.parentFile
        }
    }

    private data class LinkRecord(val path: String, val target: String, val mode: Int)

    private fun File.isSymbolicLinkCompat(): Boolean = runCatching {
        Os.lstat(absolutePath).st_mode and 0xf000 == 0xa000
    }.getOrDefault(false)

    private companion object {
        const val MAX_TAR_ENTRIES = 100_000
        const val BUFFER_SIZE = 64 * 1024
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
