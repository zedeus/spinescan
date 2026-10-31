package spinescan.core

import java.io.File

/** Shared fixture-file discovery: walk up from the working directory. */
object TestFixtures {
    fun find(relativePath: String): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            val f = File(dir, relativePath)
            if (f.exists()) return f
            dir = dir.parentFile ?: error("$relativePath not found above ${System.getProperty("user.dir")}")
        }
    }
}
