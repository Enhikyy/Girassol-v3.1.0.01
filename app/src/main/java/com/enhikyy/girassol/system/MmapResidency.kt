package com.enhikyy.girassol.system

import java.io.File

/**
 * v3.9 — quanto de cada .gguf está realmente residente (páginas mmap em RAM), lido de
 * /proc/self/smaps: soma o campo `Rss:` de todos os mapeamentos cujo caminho é o arquivo.
 */
object MmapResidency {
    fun residentKb(paths: Collection<String>, smaps: File = File("/proc/self/smaps")): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val wanted = paths.toHashSet()
        val out = HashMap<String, Long>()
        runCatching {
            var current: String? = null
            smaps.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val c = line[0]
                if ((c in '0'..'9' || c in 'a'..'f') && line.contains('-')) {
                    val p = line.substringAfterLast(' ').trim()
                    current = if (p in wanted) p else null
                } else if (current != null && line.startsWith("Rss:")) {
                    val kb = line.removePrefix("Rss:").trim().substringBefore(' ').toLongOrNull() ?: 0L
                    out[current!!] = (out[current!!] ?: 0L) + kb
                }
            }
        }
        return out
    }
}
