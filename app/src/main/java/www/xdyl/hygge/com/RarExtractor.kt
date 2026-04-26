package www.xdyl.hygge.com

import com.github.junrar.Junrar
import com.github.junrar.exception.RarException
import java.io.File

object RarExtractor {
    fun extract(rarFile: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        try {
            Junrar.extract(rarFile, destDir)
        } catch (e: RarException) {
            throw RuntimeException("RAR extraction failed", e)
        }
    }
}
