package com.battlesbudz.jarvis.v2.code

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Store round-trips, id rules, path safety, and the persisted round log. */
class CodeProjectStoreTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private lateinit var store: CodeProjectStore

    @Before fun setUp() {
        store = CodeProjectStore(File(tmp.root, "workspace"))
    }

    @Test fun createSeedsStarterPageBriefAndState() {
        val project = store.createProject("Snake Game", "A snake game", nowMillis = 1_000)
        assertEquals("snake-game", project.id)
        assertEquals(1_000L, project.createdAtMillis)
        assertEquals(0, project.rounds)

        val dir = store.projectDir(project.id)
        val starter = File(dir, CodeProjectStore.INDEX_FILE_NAME)
        assertTrue(starter.isFile)
        assertEquals(CodeProjectStore.STARTER_HTML, starter.readText())

        val brief = store.readBrief(project)!!
        assertEquals("A snake game", brief.goal)
        assertEquals(
            listOf(ProjectFileEntry("index.html", starter.length())),
            brief.files
        )
        assertTrue(brief.decisions.isEmpty())
        assertTrue(brief.knownProblems.isEmpty())
        assertTrue(brief.nextSteps.isEmpty())

        assertEquals(project, store.loadProject(project.id))
    }

    @Test fun exportPackageNameIsDerivedDeterministically() {
        val project = store.createProject("Snake Game", "goal", nowMillis = 1)
        // The slug's dash is sanitized out: a dash is not a legal
        // package-name character and Android's installer refuses such
        // a package outright (device-proven 2026-11-09).
        assertTrue(project.exportPackageName.startsWith("com.battlesbudz.faraday.app.snake_game_"))
        assertTrue(CodeProjectStore.isValidPackageName(project.exportPackageName))
        assertTrue("package names must fit the 64-char export placeholder",
            project.exportPackageName.length <= 64)
        val otherStore = CodeProjectStore(File(tmp.root, "other-workspace"))
        val same = otherStore.createProject("Snake Game", "goal", nowMillis = 2)
        assertEquals(project.exportPackageName, same.exportPackageName)
        val different = store.createProject("Other Game", "goal", nowMillis = 3)
        assertTrue(different.exportPackageName != project.exportPackageName)
    }

    @Test fun derivedPackageNamesAreAlwaysInstallable() {
        // Every name shape Battles might give a project must derive a
        // package Android accepts: dashes, leading digits, symbols.
        for (name in listOf("Snake Game", "2049 Tank Battle", "a", "My Cool App!", "x_y", "Café Racer")) {
            val project = store.createProject(name, "goal", nowMillis = 1)
            assertTrue(
                "\"$name\" derived ${project.exportPackageName}",
                CodeProjectStore.isValidPackageName(project.exportPackageName)
            )
        }
    }

    @Test fun sanitizingAStoredDashedNameIsDeterministic() {
        val legacy = "com.battlesbudz.faraday.app.snake-game_c4f42a10"
        assertEquals(
            "com.battlesbudz.faraday.app.snake_game_c4f42a10",
            CodeProjectStore.sanitizePackageName(legacy)
        )
        assertEquals(
            CodeProjectStore.sanitizePackageName(legacy),
            CodeProjectStore.sanitizePackageName(legacy)
        )
        assertTrue(CodeProjectStore.isValidPackageName(CodeProjectStore.sanitizePackageName(legacy)))
    }

    @Test fun idsAreSlugsMadeUnique() {
        assertEquals("my-cool-app", store.createProject("My Cool App!", "g", 1).id)
        assertEquals("my-cool-app-2", store.createProject("My Cool App!", "g", 2).id)
        assertEquals("my-cool-app-3", store.createProject("my cool app", "g", 3).id)
    }

    @Test fun unusableNamesFallBackToTheAppSlug() {
        assertEquals("app", store.createProject("", "g", 1).id)
        assertEquals("app-2", store.createProject("!!!", "g", 2).id)
    }

    @Test fun listOrdersByCreationAndSkipsUnreadableState() {
        val later = store.createProject("Later", "g", nowMillis = 2_000)
        val earlier = store.createProject("Earlier", "g", nowMillis = 1_000)
        val brokenDir = File(store.projectsDir, "broken")
        brokenDir.mkdirs()
        File(brokenDir, CodeProjectStore.PROJECT_JSON_FILE_NAME).writeText("not json")
        assertEquals(listOf(earlier, later), store.listProjects())
        assertNull(store.loadProject("broken"))
    }

    @Test fun loadRejectsUnknownAndUnsafeIds() {
        assertNull(store.loadProject("no-such-project"))
        assertNull(store.loadProject("../outside"))
        assertNull(store.loadProject(""))
        assertNull(store.loadProject("Has Caps"))
    }

    @Test fun namesNeedingEscapesRoundTripByteForByte() {
        val name = "Bob's \"big\" app \\ path\nsecond line 🎮"
        val project = store.createProject(name, "goal", nowMillis = 7)
        val loaded = store.loadProject(project.id)!!
        assertEquals(name, loaded.name)
        assertEquals(project, loaded)
    }

    @Test fun saveProjectPersistsUpdates() {
        val project = store.createProject("Snake", "g", nowMillis = 1)
        store.saveProject(project.copy(rounds = 3, updatedAtMillis = 99))
        val loaded = store.loadProject(project.id)!!
        assertEquals(3, loaded.rounds)
        assertEquals(99L, loaded.updatedAtMillis)
    }

    @Test fun deleteRemovesTheWholeTree() {
        val project = store.createProject("Snake", "g", nowMillis = 1)
        val dir = store.projectDir(project.id)
        File(dir, "extra.txt").writeText("x")
        assertTrue(store.deleteProject(project.id))
        assertFalse(dir.exists())
        assertTrue(store.listProjects().isEmpty())
        assertFalse(store.deleteProject(project.id))
        assertFalse(store.deleteProject("../outside"))
    }

    @Test fun projectDirRejectsUnsafeIds() {
        assertThrows(IllegalArgumentException::class.java) { store.projectDir("../outside") }
        assertThrows(IllegalArgumentException::class.java) { store.projectDir("a/b") }
        assertThrows(IllegalArgumentException::class.java) { store.projectDir("") }
    }

    @Test fun resolvePathRejectsEscapes() {
        val dir = store.projectDir(store.createProject("Snake", "g", 1).id)
        assertNull(store.resolvePath(dir, "../secret.txt"))
        assertNull(store.resolvePath(dir, "/etc/passwd"))
        assertNull(store.resolvePath(dir, "a/../../b"))
        assertNull(store.resolvePath(dir, "..\\windows\\system"))
        assertNull(store.resolvePath(dir, "sub/../index.html"))
        assertNull(store.resolvePath(dir, ""))
        assertNull(store.resolvePath(dir, "/"))
        assertNull(store.resolvePath(dir, "."))
    }

    @Test fun resolvePathAcceptsNestedRelativePaths() {
        val dir = store.projectDir(store.createProject("Snake", "g", 1).id)
        val root = dir.canonicalFile
        assertEquals(
            File(root, "index.html").path,
            store.resolvePath(dir, "index.html")!!.path
        )
        assertEquals(
            File(root, "index.html").path,
            store.resolvePath(dir, "./index.html")!!.path
        )
        assertEquals(
            File(root, "css/style.css").path,
            store.resolvePath(dir, "css/style.css")!!.path
        )
        assertEquals(
            File(root, "css/style.css").path,
            store.resolvePath(dir, "css\\style.css")!!.path
        )
        assertEquals(
            File(root, "a/b.txt").path,
            store.resolvePath(dir, "a//b.txt")!!.path
        )
    }

    @Test fun roundLogRoundTripsEscapedFieldsInOrder() {
        val project = store.createProject("Snake", "g", nowMillis = 1)
        store.appendRoundLog(project, "make it\tfast\nnow", "written \\ done", nowMillis = 111)
        store.appendRoundLog(project, "second", "fixed", nowMillis = 222)
        val log = store.readRoundLog(project)
        assertEquals(2, log.size)
        assertEquals(RoundLogEntry(111, "make it\tfast\nnow", "written \\ done"), log[0])
        assertEquals(RoundLogEntry(222, "second", "fixed"), log[1])
    }

    @Test fun briefFilesSectionRegeneratesFromTheRealFolder() {
        val project = store.createProject("Snake", "g", nowMillis = 1)
        val dir = store.projectDir(project.id)
        // A model-shaped write puts a bogus entry in the machine-owned section.
        val brief = store.readBrief(project)!!
        store.writeBrief(project, brief.withFilesSection(listOf(ProjectFileEntry("ghost.txt", 1))))
        File(dir, "style.css").writeText("body {}")
        val updated = store.regenerateBriefFilesSection(project)!!
        assertEquals(
            listOf("BRIEF.md", "index.html", "style.css"),
            updated.files.map { it.path }
        )
        assertEquals(updated, store.readBrief(project))
    }
}
