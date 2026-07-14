package www.xdyl.hygge.desktop

import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Properties

class Preferences {
    private val props = Properties()
    private val file = File(System.getProperty("user.home"), ".xdyl_config.properties")

    init {
        if (file.exists()) {
            FileReader(file).use { props.load(it) }
        }
    }

    fun getString(key: String, default: String?): String? = props.getProperty(key, default)
    fun getBoolean(key: String, default: Boolean): Boolean = props.getProperty(key)?.toBoolean() ?: default
    fun getInt(key: String, default: Int): Int = props.getProperty(key)?.toIntOrNull() ?: default

    fun putString(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    fun putBoolean(key: String, value: Boolean) {
        props.setProperty(key, value.toString())
        save()
    }

    fun putInt(key: String, value: Int) {
        props.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        FileWriter(file).use { props.store(it, null) }
    }
}
