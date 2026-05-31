package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Architecture guard: the `inat` and `inference` packages must not depend on
 * `app.ui.*`. Enforced by scanning source files for the forbidden import prefix.
 * Phase 4 fixes the existing violation (ClipSpectrogram/INatSubmitter → app.ui).
 */
class NoUiImportsGuardTest {

    private val srcRoot = File("src/main/java/com/sound2inat")

    private fun ktFilesUnder(pkgDir: String): List<File> =
        File(srcRoot, pkgDir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun assertNoImportPrefix(pkgDir: String, forbiddenPrefix: String) {
        val offenders = ktFilesUnder(pkgDir).filter { f ->
            f.readLines().any { it.trimStart().startsWith("import $forbiddenPrefix") }
        }
        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun `inat package has no app_ui imports`() {
        assertNoImportPrefix("inat", "com.sound2inat.app.ui")
    }

    @Test
    fun `inference package has no app_ui imports`() {
        assertNoImportPrefix("inference", "com.sound2inat.app.ui")
    }
}
