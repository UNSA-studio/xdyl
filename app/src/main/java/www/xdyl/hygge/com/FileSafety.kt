package www.xdyl.hygge.com

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Files are published only after validation. Temporary files stay on the same filesystem. */
object FileSafety {
    fun checkedName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && name.none { it.code < 32 }) { "Invalid file name" }
        return name
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun publish(staged: File, destination: File) {
        destination.parentFile?.mkdirs()
        Files.move(staged.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            publish(tmp, file)
        } finally { tmp.delete() }
    }
}

/** A failed download never touches live jars. Commit rolls back all replacements/removals on error. */
class ModUpdateTransaction(private val modsDir: File) : AutoCloseable {
    private val work = File(modsDir, ".nu-update-${UUID.randomUUID()}")
    private val stagedDir = File(work, "staged").apply { mkdirs() }
    private val backupDir = File(work, "backup").apply { mkdirs() }
    private var preserveRecovery = false

    companion object {
        /** Recover an interrupted commit before starting another update. Unfinished downloads never touched live jars. */
        fun recoverInterrupted(modsDir: File) {
            modsDir.listFiles().orEmpty().filter {
                it.isDirectory && it.name.startsWith(".nu-update-") && !Files.isSymbolicLink(it.toPath())
            }.forEach { work ->
                if (!File(work, ".committed").exists()) {
                    val plan = File(work, "plan.txt")
                    if (plan.isFile) plan.readLines().filter { it.isNotBlank() }.asReversed().forEach { row ->
                        val fields = row.split('\t', limit = 2)
                        require(fields.size == 2 && fields[0] in listOf("0", "1")) { "Invalid recovery journal" }
                        val name = FileSafety.checkedName(fields[1])
                        val backup = File(work, "backup/$name")
                        if (backup.isFile) FileSafety.publish(backup, File(modsDir, name))
                        else if (fields[0] == "0") Files.deleteIfExists(File(modsDir, name).toPath())
                    }
                }
                check(work.deleteRecursively()) { "Cannot clean recovered update: ${work.absolutePath}" }
            }
        }
    }

    fun staged(name: String): File = File(stagedDir, FileSafety.checkedName(name))

    fun commit(replacements: List<String>, removals: List<String>) {
        val names = replacements.distinct().map(FileSafety::checkedName)
        val removeNames = removals.distinct().map(FileSafety::checkedName).filterNot { it in names }
        names.forEach { if (!staged(it).isFile) throw IOException("Staged file missing: $it") }
        val plan = (names + removeNames).joinToString("\n") { name ->
            (if (File(modsDir, name).exists()) "1" else "0") + "\t" + name
        }
        FileSafety.writeAtomic(File(work, "plan.txt"), plan)
        val backedUp = mutableListOf<String>()
        val installed = mutableListOf<String>()
        try {
            (names + removeNames).forEach { name ->
                val live = File(modsDir, name)
                if (live.exists()) {
                    if (!live.isFile) throw IOException("Target is not a file: $name")
                    FileSafety.publish(live, File(backupDir, name))
                    backedUp.add(name)
                }
            }
            names.forEach { name ->
                FileSafety.publish(staged(name), File(modsDir, name))
                installed.add(name)
            }
            FileSafety.writeAtomic(File(work, ".committed"), "1")
        } catch (failure: Exception) {
            installed.asReversed().forEach { name ->
                runCatching { Files.deleteIfExists(File(modsDir, name).toPath()) }
                    .onFailure { preserveRecovery = true; failure.addSuppressed(it) }
            }
            backedUp.asReversed().forEach { name ->
                runCatching { FileSafety.publish(File(backupDir, name), File(modsDir, name)) }
                    .onFailure { preserveRecovery = true; failure.addSuppressed(it) }
            }
            if (preserveRecovery) throw IOException("Rollback incomplete; recovery files retained in ${work.absolutePath}", failure)
            throw failure
        }
    }

    override fun close() {
        if (!preserveRecovery) work.deleteRecursively()
    }
}
