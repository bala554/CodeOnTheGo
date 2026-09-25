package com.itsaky.androidide.agent.opencode.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.itsaky.androidide.agent.opencode.model.DiffLine
import com.itsaky.androidide.agent.opencode.model.DiffType
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.PatchStatus
import com.itsaky.androidide.projects.ProjectManagerImpl
import java.io.File
import java.io.IOException

/**
 * Bridges OpenCode agent file operations with the local Android IDE project.
 * Responsible for reading project files, computing diffs, modifying files on disk,
 * and synchronizing open buffers in the IDE.
 */
class OpenCodeFileBridge {

    companion object {
        private const val TAG = "OpenCodeFileBridge"
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", "build", ".idea", ".cxx", "captures"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileChangeListener: ((File, String) -> Unit)? = null

    /**
     * Registers a listener to be notified when a file is modified by the agent,
     * allowing open editor tabs to refresh their buffers.
     */
    fun setOnFileChangeListener(listener: (file: File, newContent: String) -> Unit) {
        this.fileChangeListener = listener
    }

    /**
     * Gets the currently opened project root directory in the IDE.
     */
    fun getProjectRoot(): File? {
        return try {
            ProjectManagerImpl.getInstance().projectDir
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get project dir from ProjectManagerImpl", e)
            null
        }
    }

    /**
     * Lists project files up to a maximum depth and count, excluding build & version-control artifacts.
     */
    fun listProjectFiles(maxDepth: Int = 4, maxFiles: Int = 100): List<String> {
        val root = getProjectRoot() ?: return emptyList()
        val result = mutableListOf<String>()

        fun walk(current: File, depth: Int) {
            if (depth > maxDepth || result.size >= maxFiles) return
            val children = current.listFiles() ?: return

            for (file in children) {
                if (file.isDirectory) {
                    if (!IGNORED_DIRECTORIES.contains(file.name)) {
                        walk(file, depth + 1)
                    }
                } else {
                    val relPath = file.relativeToOrNull(root)?.path ?: file.name
                    result.add(relPath)
                    if (result.size >= maxFiles) return
                }
            }
        }

        walk(root, 0)
        return result
    }

    /**
     * Reads a project file securely, ensuring no path traversal escapes the project root.
     */
    fun readFile(relativePath: String): Result<String> {
        val root = getProjectRoot() ?: return Result.failure(IllegalStateException("No project currently opened in IDE"))
        return try {
            val targetFile = resolveFileWithinRoot(root, relativePath)
            if (!targetFile.exists()) {
                Result.failure(IOException("File does not exist: $relativePath"))
            } else if (!targetFile.isFile) {
                Result.failure(IOException("Path is not a file: $relativePath"))
            } else {
                Result.success(targetFile.readText())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes proposed content to a file inside the project and notifies the editor.
     */
    fun writeFile(relativePath: String, content: String): Result<File> {
        val root = getProjectRoot() ?: return Result.failure(IllegalStateException("No project currently opened in IDE"))
        return try {
            val targetFile = resolveFileWithinRoot(root, relativePath)
            targetFile.parentFile?.mkdirs()

            // Write atomically using a temporary file
            val tempFile = File.createTempFile("opencode_", ".tmp", targetFile.parentFile)
            tempFile.writeText(content)
            if (!tempFile.renameTo(targetFile)) {
                // Fallback direct write
                targetFile.writeText(content)
                tempFile.delete()
            }

            // Notify UI on main thread
            mainHandler.post {
                fileChangeListener?.invoke(targetFile, content)
            }

            Log.i(TAG, "Successfully modified project file: ${targetFile.absolutePath}")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write project file: $relativePath", e)
            Result.failure(e)
        }
    }

    /**
     * Applies a file patch proposed by OpenCode.
     */
    fun applyPatch(patch: MessagePart.FilePatch): Result<Unit> {
        return writeFile(patch.relativeFilePath, patch.proposedContent).map { Unit }
    }

    /**
     * Computes line-by-line diff between two strings for visual inspection.
     */
    fun computeDiff(oldText: String, newText: String): List<DiffLine> {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()

        val diff = mutableListOf<DiffLine>()
        var oldIdx = 0
        var newIdx = 0

        while (oldIdx < oldLines.size || newIdx < newLines.size) {
            if (oldIdx < oldLines.size && newIdx < newLines.size && oldLines[oldIdx] == newLines[newIdx]) {
                diff.add(DiffLine(DiffType.CONTEXT, oldLines[oldIdx], oldIdx + 1, newIdx + 1))
                oldIdx++
                newIdx++
            } else if (newIdx < newLines.size && (oldIdx >= oldLines.size || !oldLines.contains(newLines[newIdx]))) {
                diff.add(DiffLine(DiffType.ADDITION, newLines[newIdx], null, newIdx + 1))
                newIdx++
            } else if (oldIdx < oldLines.size) {
                diff.add(DiffLine(DiffType.DELETION, oldLines[oldIdx], oldIdx + 1, null))
                oldIdx++
            }
        }

        return diff
    }

    /**
     * Resolves a relative path safely within the project root to prevent path traversal.
     */
    private fun resolveFileWithinRoot(root: File, relativePath: String): File {
        val normalizedRelative = relativePath.trim().removePrefix("/")
        val target = File(root, normalizedRelative).canonicalFile
        val canonicalRoot = root.canonicalFile

        if (!target.path.startsWith(canonicalRoot.path)) {
            throw SecurityException("Access denied: path traverses outside project root: $relativePath")
        }
        return target
    }
}
