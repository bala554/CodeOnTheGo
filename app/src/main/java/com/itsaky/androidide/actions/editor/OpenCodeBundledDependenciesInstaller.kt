package com.itsaky.androidide.actions.editor

import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Installs checksum-pinned ARM64 Proot dependencies bundled in the APK, without invoking the terminal package manager. */
internal class OpenCodeBundledDependenciesInstaller(
    private val prefix: File,
    private val cacheDirectory: File,
    private val openAsset: (String) -> InputStream,
) {
    suspend fun installIfNeeded(onProgress: suspend (OpenCodeInstallProgress) -> Unit = {}) {
        if (hasRuntimeDependencies(prefix)) return
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create a private dependency cache")
        }
        val stage = File(cacheDirectory, ".opencode-dependencies-${UUID.randomUUID()}")
        val downloads = File(stage, "downloads")
        val prootFiles = File(stage, "proot")
        val tallocFiles = File(stage, "libtalloc")
        if (!downloads.mkdirs() || !prootFiles.mkdirs() || !tallocFiles.mkdirs()) {
            throw IOException("Could not create temporary dependency installation directories")
        }
        try {
            val prootArchive = File(downloads, PROOT_ASSET.substringAfterLast('/'))
            copyVerifiedAsset(PROOT_ASSET, prootArchive, PROOT_SIZE, PROOT_SHA256, "Proot", onProgress)
            extractRequiredFiles(prootArchive, prootFiles, PROOT_FILES)

            val tallocArchive = File(downloads, TALLOC_ASSET.substringAfterLast('/'))
            copyVerifiedAsset(TALLOC_ASSET, tallocArchive, TALLOC_SIZE, TALLOC_SHA256, "libtalloc", onProgress)
            extractRequiredFiles(tallocArchive, tallocFiles, TALLOC_FILES)

            installFiles(prootFiles, PROOT_FILES)
            installFiles(tallocFiles, TALLOC_FILES)
            createTallocAliases()
            if (!hasRuntimeDependencies(prefix)) {
                throw IOException("The bundled Proot dependencies could not be installed")
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    private suspend fun copyVerifiedAsset(
        assetName: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        label: String,
        onProgress: suspend (OpenCodeInstallProgress) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastPercent = -1
        openAsset(assetName).use { input ->
            FileOutputStream(destination).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > expectedSize) throw IOException("Bundled $label package exceeded its pinned size")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = ((copied * 100L) / expectedSize).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(OpenCodeInstallProgress(copied, expectedSize, label))
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
        if (copied != expectedSize) throw IOException("Bundled $label package was incomplete")
        if (!digest.digest().toHex().equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Bundled $label package failed SHA-256 verification")
        }
    }

    /** Reads only the listed regular files from a pinned Debian data archive. */
    private fun extractRequiredFiles(debFile: File, destination: File, requiredFiles: Set<String>) {
        val extracted = mutableSetOf<String>()
        var expandedBytes = 0L
        var dataArchiveFound = false
        ArArchiveInputStream(BufferedInputStream(debFile.inputStream())).use { ar ->
            while (true) {
                val member = ar.nextEntry ?: break
                if (member.name != "data.tar.xz") continue
                dataArchiveFound = true
                XZCompressorInputStream(ar).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val normalized = OpenCodeTarExtractor().normalizeTarPath(entry.name)
                            if (normalized.isEmpty()) continue
                            if (entry.size < 0L || entry.size > MAX_EXPANDED_BYTES - expandedBytes) {
                                throw IOException("A bundled dependency archive expands beyond its safety limit")
                            }
                            expandedBytes += entry.size
                            val relativePath = pathUnderPackagePrefix(normalized) ?: continue
                            if (relativePath !in requiredFiles) continue
                            if (!entry.isFile || !extracted.add(relativePath)) {
                                throw IOException("The bundled dependency archive contains an invalid or duplicate file")
                            }
                            val output = File(destination, relativePath)
                            if (!output.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                                throw IOException("The bundled dependency archive contains an unsafe path")
                            }
                            val parent = output.parentFile ?: throw IOException("The bundled dependency archive has an invalid path")
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("Could not prepare a bundled dependency file")
                            }
                            FileOutputStream(output).use { fileOutput ->
                                BufferedOutputStream(fileOutput).use { stream ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var copied = 0L
                                    while (copied < entry.size) {
                                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - copied).toInt())
                                        if (count < 0) throw IOException("The bundled dependency archive contains a truncated file")
                                        stream.write(buffer, 0, count)
                                        copied += count
                                    }
                                    stream.flush()
                                    fileOutput.fd.sync()
                                }
                            }
                        }
                    }
                }
                break
            }
        }
        if (!dataArchiveFound) throw IOException("The bundled Debian package has no supported data archive")
        if (extracted != requiredFiles) {
            throw IOException("The bundled Debian package is missing required Proot runtime files")
        }
    }

    private fun pathUnderPackagePrefix(path: String): String? {
        val marker = "/files/usr/"
        val markerIndex = path.indexOf(marker)
        if (markerIndex >= 0) return path.substring(markerIndex + marker.length)
        return path.takeIf { it.startsWith("files/usr/") }?.removePrefix("files/usr/")
    }

    private fun installFiles(sourceRoot: File, files: Set<String>) {
        for (relativePath in files) {
            val source = File(sourceRoot, relativePath)
            if (!source.isFile) throw IOException("A required bundled dependency file is missing")
            val destination = File(prefix, relativePath)
            val parent = destination.parentFile ?: throw IOException("Invalid dependency installation path")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create a dependency directory")
            ensureSafeDestination(destination)
            source.copyTo(destination, overwrite = true)
            if (!destination.setReadable(true, false) || !destination.setExecutable(true, false)) {
                throw IOException("Could not set the required permissions on a bundled dependency")
            }
        }
    }

    private fun createTallocAliases() {
        val libraryDirectory = File(prefix, "lib")
        ensureSafeDestination(File(libraryDirectory, TALLOC_LIBRARY))
        for (alias in TALLOC_ALIASES) {
            val link = File(libraryDirectory, alias)
            if (link.exists() || isSymbolicLink(link)) {
                if (!link.delete()) throw IOException("Could not replace a libtalloc alias")
            }
            Os.symlink(TALLOC_LIBRARY, link.absolutePath)
        }
    }

    private fun ensureSafeDestination(destination: File) {
        val root = prefix.canonicalFile
        val rootPath = root.path + File.separator
        if (!destination.canonicalPath.startsWith(rootPath)) {
            throw IOException("A bundled dependency destination escapes the app prefix")
        }
        var parent = destination.parentFile
        while (parent != null && parent.canonicalFile != root) {
            if (parent.exists() && isSymbolicLink(parent)) {
                throw IOException("A bundled dependency destination is beneath a symbolic link")
            }
            parent = parent.parentFile
        }
        if (destination.exists() && isSymbolicLink(destination)) {
            throw IOException("A bundled dependency destination is a symbolic link")
        }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        Os.lstat(file.absolutePath).st_mode and 0xf000 == 0xa000
    }.getOrDefault(false)

    companion object {
        const val PROOT_ASSET = "opencode-runtime/arm64/proot_5.1.107-70_aarch64.deb"
        const val TALLOC_ASSET = "opencode-runtime/arm64/libtalloc_2.4.3_aarch64.deb"
        const val PROOT_SHA256 = "29b3c942ce36549771d189cdac6edee14ba4636a187afc6d6482eb6b063d8a92"
        const val TALLOC_SHA256 = "4857a7faab1a179804e07fa9538e8c026387c1b6a05d0bb7bc8deb755eaded8b"
        const val PROOT_SIZE = 84_396L
        const val TALLOC_SIZE = 33_132L
        const val TALLOC_LIBRARY = "libtalloc.so.2.4.3"

        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_EXPANDED_BYTES = 2L * 1024L * 1024L
        private val PROOT_FILES = setOf("bin/proot", "libexec/proot/loader", "libexec/proot/loader32")
        private val TALLOC_FILES = setOf("lib/$TALLOC_LIBRARY")
        private val TALLOC_ALIASES = setOf("libtalloc.so.2", "libtalloc.so")

        fun hasRuntimeDependencies(prefix: File): Boolean {
            val proot = File(prefix, "bin/proot")
            val loader = File(prefix, "libexec/proot/loader")
            val loader32 = File(prefix, "libexec/proot/loader32")
            val talloc = File(prefix, "lib/$TALLOC_LIBRARY")
            return proot.canExecute() && loader.canExecute() && loader32.canExecute() && talloc.isFile &&
                TALLOC_ALIASES.all { File(prefix, "lib/$it").exists() }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
