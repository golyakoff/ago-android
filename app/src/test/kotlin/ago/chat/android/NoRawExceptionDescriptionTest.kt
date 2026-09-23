package ago.chat.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `26-59`: the whole promise was "a failure this app cannot explain never puts an exception class or a
 * hostname on the screen", and the only thing that ever enforced it before this test existed was four
 * people remembering not to write the same private extension function a fifth time. This walks every
 * Kotlin source file in the repository and fails if that function's own declaration line reappears
 * anywhere — the identical shape [ago.chat.android.core.domain.net.NetworkFailure] now replaces.
 *
 * [KNOWN_SURVIVORS] is deliberately not empty. `TeamChatViewModel.kt`'s own copy (`26-54`) is real and
 * unfixed, but it is not one of the four files `docs/backlog/26-59-*.md` names as this item's own scope,
 * and this item's own brief explicitly warns against touching a file another background worker (`26-55`)
 * may be editing at the same time. Rather than silently exclude it from what this test can see, it is
 * named here so the next reader knows exactly what is still owed, to whom, and why it was left standing.
 */
class NoRawExceptionDescriptionTest {
    @Test
    fun `no source file rebuilds the old class-name-plus-message shape outside a known, tracked survivor`() {
        val root = repoRoot()
        val offenders =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> SKIPPED_PATH_SEGMENTS.none { segment -> segment in file.path.replace('\\', '/') } }
                .filter { OLD_DESCRIBE_SIGNATURE in it.readText() }
                .map { it.relativeTo(root).path.replace('\\', '/') }
                .filterNot { it in KNOWN_SURVIVORS }
                .toList()

        assertTrue(
            "found the old exception-class-name-plus-message shape outside the known, tracked survivors " +
                "(see this test's own doc comment): $offenders",
            offenders.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private companion object {
        // The exact declaration every one of the four copies this item removed shared, character for
        // character — deliberately the function's own signature line, not a shorter fragment like
        // `class.simpleName` alone, which would also match this file's own doc comment above and every
        // other place in this codebase that merely *talks about* the shape rather than reproducing it.
        const val OLD_DESCRIBE_SIGNATURE =
            "private fun Exception.describe(): String = \"\${this::class.simpleName}: \${message ?: \"no detail\"}\""

        val SKIPPED_PATH_SEGMENTS = listOf("/build/", "/.gradle/")

        val KNOWN_SURVIVORS =
            setOf(
                // `26-54`, predates this item, out of its own named scope - see this test's own doc comment.
                "app/src/main/kotlin/ago/chat/android/team/TeamChatViewModel.kt",
            )
    }
}
