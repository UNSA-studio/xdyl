package www.xdyl.hygge.com

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class FileVerifier {
    fun verifyFile(file: File, expectedMd5: String, expectedSha256: String): Boolean {
        val md5 = calculateHash(file, "MD5")
        val sha256 = calculateHash(file, "SHA-256")
        return md5.equals(expectedMd5, ignoreCase = true) &&
               sha256.equals(expectedSha256, ignoreCase = true)
    }

    fun verifyModsFromCsv(modsDir: File, csvContent: String): Boolean {
        val lines = csvContent.lines().drop(1) // skip header
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split(",")
            if (parts.size < 5) continue
            val filename = parts[0].trim('"').removePrefix("./")
            val expectedSize = parts[2].toLongOrNull() ?: continue
            val expectedMd5 = parts[3].trim('"')
            val expectedSha256 = parts[4].trim('"')
            val modFile = File(modsDir, filename)
            if (!modFile.exists() || modFile.length() != expectedSize) return false
            val md5 = calculateHash(modFile, "MD5")
            val sha = calculateHash(modFile, "SHA-256")
            if (!md5.equals(expectedMd5, true) || !sha.equals(expectedSha256, true)) return false
        }
        return true
    }

    private fun calculateHash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
