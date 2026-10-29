package www.xdyl.hygge.com

/** Validates every row before an updater is allowed to stage or remove anything. */
object ModManifest {
    data class Entry(val name: String, val size: Long, val md5: String, val sha256: String)

    fun parse(csv: String): List<Entry> {
        val lines = csv.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size >= 2) { "Empty mod manifest" }
        val rows = lines.drop(1).mapIndexed { index, line ->
            val p = fields(line)
            require(p.size >= 5) { "CSV row ${index + 2}: missing columns" }
            val name = FileSafety.checkedName(p[0].trim().removePrefix("./"))
            val size = p[2].trim().toLongOrNull()
            require(size != null && size > 0) { "CSV row ${index + 2}: invalid size" }
            val md5 = p[3].trim()
            val sha = p[4].trim()
            require(md5.matches(Regex("[0-9a-fA-F]{32}")) && sha.matches(Regex("[0-9a-fA-F]{64}"))) {
                "CSV row ${index + 2}: invalid hashes"
            }
            Entry(name, size, md5, sha)
        }
        require(rows.map { it.name }.distinct().size == rows.size) { "Duplicate manifest file" }
        return rows
    }

    private fun fields(line: String): List<String> {
        val result = mutableListOf<String>()
        val part = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { part.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { result.add(part.toString()); part.setLength(0) }
                else -> part.append(c)
            }
            i++
        }
        require(!quoted) { "Unterminated CSV quote" }
        result.add(part.toString())
        return result
    }
}