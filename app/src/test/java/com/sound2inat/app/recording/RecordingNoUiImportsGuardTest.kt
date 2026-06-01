package com.sound2inat.app.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Architecture guard (single-module survivor): app.recording is the domain
 * layer of recording and must not depend on app.ui. The other layering rules
 * (inat/inference/storage) are now enforced by Gradle module boundaries; this
 * one isn't, because app.recording and app.ui still share the :app module.
 */
class RecordingNoUiImportsGuardTest {
    @Test
    fun `app recording package has no app_ui imports`() {
        val dir = File("src/main/java/com/sound2inat/app/recording")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import com.sound2inat.app.ui") } }
            .map { it.name }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
