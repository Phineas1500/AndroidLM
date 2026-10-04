package io.bigmoeonedge.example

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AndroidLM: what a model needs from the session that the global settings cannot know.
 *
 * Qwen3.8-Flash-Next (architecture `qwen4exp`) keeps 3.1GB of dense weights resident, against
 * about 2GB for Qwen3.6-35B-A3B, and reserves a 1.3GB compute buffer. On a 12GB phone the 5,000 MiB
 * expert cache the settings give Qwen3.6 would leave it no memory, so its cache is capped.
 */
object ModelProfile {
    const val FLASH_NEXT_ARCH = "qwen4exp"
    const val FLASH_NEXT_CACHE_MB = 2000

    // Keyed by path: the header is read once per model file, also from the main thread.
    private val archByPath = ConcurrentHashMap<String, String>()

    fun architecture(modelPath: String): String =
        archByPath.getOrPut(modelPath) { GgufHeader.architecture(File(modelPath)) ?: "" }

    /** The largest expert cache this model can have on this phone, or null for no cap. */
    fun cacheCapMb(modelPath: String): Int? =
        if (architecture(modelPath) == FLASH_NEXT_ARCH) FLASH_NEXT_CACHE_MB else null
}
