package www.xdyl.hygge.desktop

import java.io.File
import java.security.MessageDigest

class FileVerifier {
    fun verifyFile(file: File, expectedMd5: String, expectedSha256: String): Boolean {
        val md5 = calculateHash(file, "MD5")
        val sha256 = calculateHash(file, "SHA-256")
        return md5.equals(expectedMd5, ignoreCase = true) && sha256.equals(expectedSha256, ignoreCase = true)
    }

    private fun calculateHash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { fis ->
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
