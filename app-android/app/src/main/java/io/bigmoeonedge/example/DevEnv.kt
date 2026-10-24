package io.bigmoeonedge.example

import java.io.File

/**
 * Dev switches for A/B timing runs: KEY=VALUE lines of RunService.DEV_ENGINE_ENV
 * (/data/local/tmp/androidlm-engine.env, written with adb). Absent on a user's phone, so every
 * switch is off.
 */
object DevEnv {
    /** The file's KEY=VALUE lines, read now; empty if it does not exist or cannot be read. */
    fun read(): Map<String, String> = try {
        File(RunService.DEV_ENGINE_ENV).takeIf { it.canRead() }?.readLines().orEmpty()
            .map { it.trim() }
            .filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    } catch (e: Exception) {
        emptyMap()
    }

    // read once per app start, for switches the screen reads as it draws
    private val atStart: Map<String, String> by lazy { read() }

    /** [key]=1 in the file as it was when the app started. */
    fun flag(key: String): Boolean = atStart[key] == "1"
}
