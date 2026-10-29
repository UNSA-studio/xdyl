package www.xdyl.hygge.com

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedStagingKeepsOldFilesAndObsoleteFiles() {
        val dir = temp.newFolder()
        File(dir, "a.jar").writeText("old")
        File(dir, "obsolete.jar").writeText("keep until success")
        ModUpdateTransaction(dir).use { tx ->
            try { tx.commit(listOf("a.jar"), listOf("obsolete.jar")); fail("Missing staging must fail") }
            catch (_: java.io.IOException) { }
        }
        assertEquals("old", File(dir, "a.jar").readText())
        assertTrue(File(dir, "obsolete.jar").exists())
    }

    @Test fun commitPublishesValidatedFilesAndRemovesObsoleteFiles() {
        val dir = temp.newFolder()
        File(dir, "a.jar").writeText("old")
        File(dir, "obsolete.jar").writeText("obsolete")
        ModUpdateTransaction(dir).use { tx ->
            tx.staged("a.jar").writeText("new")
            tx.commit(listOf("a.jar"), listOf("obsolete.jar"))
        }
        assertEquals("new", File(dir, "a.jar").readText())
        assertFalse(File(dir, "obsolete.jar").exists())
    }

    @Test fun failedCommitRestoresAlreadyBackedUpFiles() {
        val dir = temp.newFolder()
        File(dir, "a.jar").writeText("old")
        File(dir, "b.jar").mkdir()
        ModUpdateTransaction(dir).use { tx ->
            tx.staged("a.jar").writeText("new")
            tx.staged("b.jar").writeText("new")
            try { tx.commit(listOf("a.jar", "b.jar"), emptyList()); fail("A directory is not a live jar") }
            catch (_: java.io.IOException) { }
        }
        assertEquals("old", File(dir, "a.jar").readText())
        assertTrue(File(dir, "b.jar").isDirectory)
    }

    @Test fun removalOnlyUpdateIsCommitted() {
        val dir = temp.newFolder()
        File(dir, "obsolete.jar").writeText("old")
        ModUpdateTransaction(dir).use { it.commit(emptyList(), listOf("obsolete.jar")) }
        assertFalse(File(dir, "obsolete.jar").exists())
    }

    @Test fun interruptedCommitIsRecoveredBeforePlanningNextUpdate() {
        val dir = temp.newFolder()
        val work = File(dir, ".nu-update-interrupted")
        File(work, "backup").mkdirs()
        File(work, "backup/a.jar").writeText("old")
        File(work, "plan.txt").writeText("1\ta.jar\n0\tnew.jar")
        File(dir, "a.jar").writeText("half-new")
        File(dir, "new.jar").writeText("half-installed")
        ModUpdateTransaction.recoverInterrupted(dir)
        assertEquals("old", File(dir, "a.jar").readText())
        assertFalse(File(dir, "new.jar").exists())
        assertFalse(work.exists())
    }

    @Test fun completedCommitIsNotRolledBackDuringCleanupRecovery() {
        val dir = temp.newFolder()
        val work = File(dir, ".nu-update-completed")
        File(work, "backup").mkdirs()
        File(work, "backup/a.jar").writeText("old")
        File(work, "plan.txt").writeText("1\ta.jar")
        File(work, ".committed").writeText("1")
        File(dir, "a.jar").writeText("new")
        ModUpdateTransaction.recoverInterrupted(dir)
        assertEquals("new", File(dir, "a.jar").readText())
        assertFalse(work.exists())
    }

    @Test fun manifestAcceptsSmallValidListsAndQuotedFileNames() {
        val csv = "name,date,size,md5,sha256\n\"mod,name.jar\",today,5,${"a".repeat(32)},${"b".repeat(64)}"
        val row = ModManifest.parse(csv).single()
        assertEquals("mod,name.jar", row.name)
        assertEquals(5L, row.size)
    }

    @Test fun manifestRejectsEmptyListsMalformedRowsAndDuplicateNames() {
        val valid = "a.jar,today,5,${"a".repeat(32)},${"b".repeat(64)}"
        listOf("name,date,size,md5,sha256", "header\nmalformed", "header\n$valid\n$valid",
            "header\n../$valid", "header\na.jar,today,-1,md5,sha").forEach { csv ->
            try { ModManifest.parse(csv); fail("Invalid manifest was accepted: $csv") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun fileNamesCannotEscapeStagingOrRecoveryDirectories() {
        listOf("../a.jar", "a/b.jar", "a\\b.jar", "..", "a\nb.jar").forEach { name ->
            try { FileSafety.checkedName(name); fail("Unsafe name accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}