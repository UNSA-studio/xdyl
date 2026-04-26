package www.xdyl.hygge.com

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.rar.RarArchiveInputStream
import java.io.File
import java.io.FileOutputStream

object RarExtractor {
    fun extract(rarFile: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        RarArchiveInputStream(rarFile.inputStream()).use { rar ->
            var entry: ArchiveEntry? = rar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        rar.copyTo(fos)
                    }
                }
                entry = rar.nextEntry
            }
        }
    }
}
