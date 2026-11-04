package com.itsaky.androidide.actions.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OpenCodeRuntimeControllerTest {
    @Test
    fun `server command stays loopback-only and preserves project and chat safeguards`() {
        val sandbox = Files.createTempDirectory("opencode-controller-test").toFile()
        try {
            val prefix = File(sandbox, "prefix").apply { mkdirs() }
            File(prefix, "bin").mkdirs()
            File(prefix, "lib").mkdirs()
            File(prefix, "tmp").mkdirs()
            val proot = File(prefix, "bin/proot").apply {
                writeText("test executable")
                setExecutable(true)
            }
            val rootfs = File(sandbox, "rootfs").apply { mkdirs() }
            val project = File(sandbox, "project with spaces").apply { mkdirs() }
            val resolver = File(sandbox, "resolv.conf").apply { writeText("nameserver 127.0.0.1\n") }

            val command = OpenCodeRuntimeController.buildServerCommand(
                proot = proot,
                rootfs = rootfs,
                projectDirectory = project,
                resolvConf = resolver,
                prefix = prefix,
            )

            assertThat(command).contains("--hostname 127.0.0.1 --port 4098")
            assertThat(command).doesNotContain("--hostname 0.0.0.0")
            assertThat(command).contains("${project.canonicalPath}:/workspace/project")
            assertThat(command).contains("OPENCODE_CONFIG_CONTENT=")
            assertThat(command).contains("\"*\":\"deny\"")
            assertThat(command).contains("\"bash\":\"deny\"")
            assertThat(command).contains("--link2symlink -0 -r")
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
