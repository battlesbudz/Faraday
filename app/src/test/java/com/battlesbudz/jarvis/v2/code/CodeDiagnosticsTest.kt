package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Copy Diagnostics code block's exact layout (scope Unit 6),
 * pinned apart from any Android state: a title line, then indented
 * counters with each project's short fingerprint.
 */
class CodeDiagnosticsTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun theEmptyBlockReadsAsZeroesAndNones() {
        val block = CodeDiagnostics.format(
            projects = emptyList(),
            fingerprints = emptyMap(),
            stats = CodeStatsStore.Stats(0, 0, 0, 0, null, null),
        )
        assertEquals(
            """
            code:
              projects: 0
              rounds: 0
              retries: 0
              fix rounds: 0
              exports: 0
              last outcome: none
              last error: none
            """.trimIndent(),
            block,
        )
    }

    @Test fun theBlockListsProjectsWithTheirShortFingerprints() {
        val store = CodeProjectStore(temp.newFolder("workspace"))
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val tank = store.createProject("Tank Game", "a tank game", 2_000L)
        val block = CodeDiagnostics.format(
            projects = listOf(snake, tank),
            fingerprints = mapOf(snake.id to "0123456789ab", tank.id to "fedcba987654"),
            stats = CodeStatsStore.Stats(
                rounds = 3, retries = 2, fixRounds = 1, exports = 1,
                lastOutcome = "round applied on Snake: index.html (rollback point v2); console clean",
                lastError = "Uncaught ReferenceError: foo is not defined",
            ),
        )
        assertEquals(
            """
            code:
              projects: 2 (${snake.id} 0123456789ab, ${tank.id} fedcba987654)
              rounds: 3
              retries: 2
              fix rounds: 1
              exports: 1
              last outcome: round applied on Snake: index.html (rollback point v2); console clean
              last error: Uncaught ReferenceError: foo is not defined
            """.trimIndent(),
            block,
        )
    }

    @Test fun aProjectWithoutAFingerprintReadsUnknown() {
        val store = CodeProjectStore(temp.newFolder("workspace"))
        val snake = store.createProject("Snake", "a snake game", 1_000L)
        val block = CodeDiagnostics.format(
            projects = listOf(snake),
            fingerprints = emptyMap(),
            stats = CodeStatsStore.Stats(0, 0, 0, 0, null, null),
        )
        assertTrue(block.contains("projects: 1 (${snake.id} unknown)"))
    }
}
