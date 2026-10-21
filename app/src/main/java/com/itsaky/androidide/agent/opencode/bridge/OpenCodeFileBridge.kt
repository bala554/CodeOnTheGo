package com.itsaky.androidide.agent.opencode.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.itsaky.androidide.agent.opencode.model.DiffLine
import com.itsaky.androidide.agent.opencode.model.DiffType
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.projects.ProjectManagerImpl
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Thrown when the file changed on disk after the agent produced its proposal. */
class PatchConflictException(val relativePath: String) :
    IOException("File changed since the proposal was generated: $relativePath")

/**
 * Bridges OpenCode agent file operations with the local project.
 *
 * Safety properties:
 *  - every path is resolved with a real Path-based containment check (no sibling-dir escape)
 *  - writes into protected directories (.git) are refused
 *  - a write is refused if the file changed since the proposal (stale base)
 *  - every write is recorded so it can be undone with [undoLast]
 */
class OpenCodeFileBridge {

    companion object {
        private const val TAG = "OpenCodeFileBridge"
        private const val MAX_READ_BYTES = 2L * 1024 * 1024
        private const val MAX_UNDO_ENTRIES = 50
        private const val MAX_DP_CELLS = 4_000_000L

        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", "build", ".idea", ".cxx", "captures"
        )
        private val PROTECTED_WRITE_DIRECTORIES = setOf(".git")
    }

    /** previousContent == null means the file did not exist before the write. */
    private data class UndoEntry(val file: File, val previousContent: String?)

    private val undoStack = ArrayDeque<UndoEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileChangeListener: ((File, String) -> Unit)? = null

    fun setOnFileChangeListener(listener: (file: File, newContent: String) -> Unit) {
        this.fileChangeListener = listener
    }

    fun getProjectRoot(): File? = try {
        ProjectManagerImpl.getInstance().projectDir
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to get project dir from ProjectManagerImpl", e)
        null
    }

    fun canUndo(): Boolean = synchronized(undoStack) { undoStack.isNotEmpty() }

    fun listProjectFiles(maxDepth: Int = 4, maxFiles: Int = 100): List<String> {
        val root = getProjectRoot() ?: return emptyList()
        val result = mutableListOf<String>()

        fun walk(current: File, depth: Int) {
            if (depth > maxDepth || result.size >= maxFiles) return
            val children = current.listFiles() ?: return
            for (file in children) {
                if (result.size >= maxFiles) return
                if (file.isDirectory) {
                    if (file.name !in IGNORED_DIRECTORIES) walk(file, depth + 1)
                } else {
                    result.add(file.relativeToOrNull(root)?.path ?: file.name)
                }
            }
        }
        walk(root, 0)
        return result
    }

    fun readFile(relativePath: String): Result<String> {
        val root = getProjectRoot()
            ?: return Result.failure(IllegalStateException("No project currently opened in IDE"))
        return try {
            val target = resolveFileWithinRoot(root, relativePath)
            when {
                !target.exists() -> Result.failure(IOException("File does not exist: $relativePath"))
                !target.isFile -> Result.failure(IOException("Path is not a file: $relativePath"))
                target.length() > MAX_READ_BYTES ->
                    Result.failure(IOException("File too large to read (>${MAX_READ_BYTES / 1024} KB): $relativePath"))
                else -> Result.success(target.readText())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes [content] to a project file.
     *
     * @param expectedOriginal the content the proposal was based on. If non-empty and the file
     *   on disk differs, the write is refused with [PatchConflictException].
     */
    fun writeFile(
        relativePath: String,
        content: String,
        expectedOriginal: String? = null
    ): Result<File> {
        val root = getProjectRoot()
            ?: return Result.failure(IllegalStateException("No project currently opened in IDE"))
        return try {
            val target = resolveFileWithinRoot(root, relativePath)
            assertWritable(root, target)
            if (target.isDirectory) throw IOException("Path is a directory: $relativePath")

            val previous: String? = if (target.exists()) target.readText() else null
            if (!expectedOriginal.isNullOrEmpty() && previous != null && previous != expectedOriginal) {
                throw PatchConflictException(relativePath)
            }

            atomicWrite(target, content)
            pushUndo(UndoEntry(target, previous))
            notifyChanged(target, content)

            Log.i(TAG, "Modified project file: ${target.absolutePath}")
            Result.success(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write project file: $relativePath", e)
            Result.failure(e)
        }
    }

    /**
     * Converts an absolute path (as used by ACP) into a project-relative path.
     * Fails if the path is not absolute or lies outside the project root.
     */
    fun toRelativePath(absolutePath: String): Result<String> {
        val root = getProjectRoot()
            ?: return Result.failure(IllegalStateException("No project currently opened in IDE"))
        return try {
            val raw = File(absolutePath)
            if (!raw.isAbsolute) throw SecurityException("Path must be absolute: $absolutePath")
            val canonicalRoot = root.canonicalFile
            val target = raw.canonicalFile
            if (!target.toPath().startsWith(canonicalRoot.toPath())) {
                throw SecurityException("Path is outside the project root: $absolutePath")
            }
            Result.success(target.relativeTo(canonicalRoot).path)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun applyPatch(patch: MessagePart.FilePatch): Result<Unit> =
        writeFile(patch.relativeFilePath, patch.proposedContent, patch.originalContent).map { }

    /** Reverts the most recent write made through this bridge. */
    fun undoLast(): Result<File> {
        val entry = synchronized(undoStack) { undoStack.removeLastOrNull() }
            ?: return Result.failure(IllegalStateException("Nothing to undo"))
        return try {
            if (entry.previousContent == null) {
                entry.file.delete()
                notifyChanged(entry.file, "")
            } else {
                atomicWrite(entry.file, entry.previousContent)
                notifyChanged(entry.file, entry.previousContent)
            }
            Result.success(entry.file)
        } catch (e: Exception) {
            // put it back so the user can retry
            synchronized(undoStack) { undoStack.addLast(entry) }
            Result.failure(e)
        }
    }

    /** LCS-based line diff (prefix/suffix trimmed; falls back to replace-block for huge inputs). */
    fun computeDiff(oldText: String, newText: String): List<DiffLine> {
        val a = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val b = if (newText.isEmpty()) emptyList() else newText.lines()

        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val out = ArrayList<DiffLine>()
        for (i in 0 until start) out.add(DiffLine(DiffType.CONTEXT, a[i], i + 1, i + 1))

        val n = endA - start
        val m = endB - start
        if (n.toLong() * m.toLong() > MAX_DP_CELLS) {
            for (i in start until endA) out.add(DiffLine(DiffType.DELETION, a[i], i + 1, null))
            for (j in start until endB) out.add(DiffLine(DiffType.ADDITION, b[j], null, j + 1))
        } else {
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && a[start + i] == b[start + j] -> {
                        out.add(DiffLine(DiffType.CONTEXT, a[start + i], start + i + 1, start + j + 1))
                        i++; j++
                    }
                    j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                        out.add(DiffLine(DiffType.ADDITION, b[start + j], null, start + j + 1))
                        j++
                    }
                    else -> {
                        out.add(DiffLine(DiffType.DELETION, a[start + i], start + i + 1, null))
                        i++
                    }
                }
            }
        }

        var oi = endA
        var ni = endB
        while (oi < a.size && ni < b.size) {
            out.add(DiffLine(DiffType.CONTEXT, a[oi], oi + 1, ni + 1)); oi++; ni++
        }
        return out
    }

    // ---- internals -------------------------------------------------------------------------

    private fun resolveFileWithinRoot(root: File, relativePath: String): File {
        val rel = relativePath.trim().removePrefix("/")
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, rel).canonicalFile
        // Path.startsWith compares whole path segments, so "/proj-evil" is NOT inside "/proj".
        if (!target.toPath().startsWith(canonicalRoot.toPath())) {
            throw SecurityException("Access denied: path traverses outside project root: $relativePath")
        }
        return target
    }

    private fun assertWritable(root: File, target: File) {
        val rel = target.relativeTo(root.canonicalFile)
        if (rel.path.isEmpty()) throw SecurityException("Cannot write to the project root itself")
        if (rel.toPath().any { it.toString() in PROTECTED_WRITE_DIRECTORIES }) {
            throw SecurityException("Writes to protected directory are not allowed: ${rel.path}")
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val dir = target.parentFile ?: throw IOException("No parent directory for ${target.path}")
        dir.mkdirs()
        val temp = File.createTempFile("opencode_", ".tmp", dir)
        try {
            temp.writeText(content)
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun pushUndo(entry: UndoEntry) = synchronized(undoStack) {
        undoStack.addLast(entry)
        while (undoStack.size > MAX_UNDO_ENTRIES) undoStack.removeFirst()
    }

    private fun notifyChanged(file: File, content: String) {
        mainHandler.post { fileChangeListener?.invoke(file, content) }
    }
}
