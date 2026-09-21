package com.taar.data

import android.content.Context
import com.taar.domain.FileSystem
import java.io.File

/**
 * The only Android-specific part of persistence.
 *
 * Everything else in the store is pure Kotlin and tested off-device; this exists so
 * that remains true. Files live in the app's private directory, which is not backed
 * up (see the manifest) because a board's readings belong to the site, not to a
 * cloud account.
 */
class AndroidFileSystem(context: Context) : FileSystem {

    private val root = File(context.filesDir, "taar").apply { mkdirs() }

    private fun file(path: String) = File(root, path).also { it.parentFile?.mkdirs() }

    override fun read(path: String): String? =
        file(path).takeIf { it.isFile }?.readText()

    /**
     * Writes to a temporary file and renames. A capture interrupted by the process
     * dying should lose the new reading, not the file holding every earlier one.
     */
    override fun write(path: String, contents: String) {
        val target = file(path)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            target.writeText(contents)
            tmp.delete()
        }
    }

    override fun list(prefix: String): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            .filter { it.startsWith(prefix) }
            .toList()

    override fun delete(path: String) {
        file(path).delete()
    }
}
