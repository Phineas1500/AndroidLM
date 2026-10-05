package org.androidlm.research

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * One of the large files the app reads from the phone's storage, as assets/manifest.json lists it
 * (SetupFilesTest holds the two together). [dir] is where an import puts it under the app's files
 * dir: "models" for the model (ModelManager.internalModelsDir), "corpus" for the rest
 * (CorpusLocator.DIR).
 */
data class SetupFile(
    val name: String,
    val role: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
    val dir: String,
    /** What the file is for, in the words the setup screen uses. */
    val label: String,
) {
    /** Research mode needs the model and wiki.db; the rest add faster search, the travel guide and places. */
    val required: Boolean get() = role == "model" || name == SetupFiles.WIKI

    /** A part of the optional second model (Qwen3.8-Flash-Next): offered apart, never needed. */
    val extra: Boolean get() = role == "model-extra"
}

/**
 * The files of a working install, and the copy that puts one into app storage. The app has no
 * internet permission, so the files reach the phone some other way (the phone's browser, a USB
 * drive, adb) and an import copies each one into app storage, where the engine's direct reads
 * work, checking its size and SHA-256 as it goes.
 */
object SetupFiles {
    const val WIKI = "wiki.db"

    /** Next to an imported file, its SHA-256: an import that finds the same hash there skips the file. */
    const val STAMP = ".sha256"
    const val PART = ".part"

    val ALL: List<SetupFile> = listOf(
        SetupFile(
            "Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf", "model", 12_290_628_576L,
            "96b9c0af5c77a4ecaabe3983175112b5ece763261c1ece12b2494b692a70dad7",
            "https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/main/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf",
            "models", "The model (Qwen3.6-35B-A3B)",
        ),
        SetupFile(
            WIKI, "corpus", 21_314_895_872L,
            "736034e74f139559bc9517991a8572092c17cddbc66967ef66e4df130e4b7bee",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/wiki.db",
            "corpus", "Wikipedia",
        ),
        SetupFile(
            "wiki_df.db", "corpus", 1_748_992L,
            "c87ba297ac464ed92c5f3087bcdcf4ef81ace57bd131b626d9cbd70c762a11fb",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/wiki_df.db",
            "corpus", "Wikipedia word counts (faster search)",
        ),
        SetupFile(
            "voyage.db", "corpus-optional", 328_810_496L,
            "f59a2708b3c9bc3b96e2a9ded7fa082765e7ad1b62f3d302d50d0f75be7f7983",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/voyage.db",
            "corpus", "Wikivoyage travel guide",
        ),
        SetupFile(
            "places.db", "places", 2_900_869_120L,
            "0323f51cb99b7b7bf9288a3558371978178f88b2f2afb372b66b6c6f469746d7",
            "https://huggingface.co/datasets/rammingaway/androidlm-places/resolve/main/places.db",
            "corpus", "Places to eat, drink and stay",
        ),
        // the optional second model, in two parts that must sit side by side; the second is its
        // n-gram table, the same file in every published build of this model
        SetupFile(
            "Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00001-of-00002.gguf", "model-extra", 47_039_860_096L,
            "219ea929900dfa9ef091f3aa473fdba6874b65fcb36526d7d851ac9e95856d15",
            "https://huggingface.co/ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF/resolve/ed59f92082b1e93c0e96d60a8b11aab089b52f09/IQ3_XXS/Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00001-of-00002.gguf",
            "models", "Larger model, part 1 of 2",
        ),
        SetupFile(
            "Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf", "model-extra", 28_800_138_432L,
            "316b46f3a2dbd68c900f43136ab9449f9dcc3725dfd8c794847c204bc161e113",
            "https://huggingface.co/ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF/resolve/ed59f92082b1e93c0e96d60a8b11aab089b52f09/IQ3_XXS/Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf",
            "models", "Larger model, part 2 of 2 (its n-gram table)",
        ),
    )

    fun byName(name: String): SetupFile? = ALL.firstOrNull { it.name == name }

    /**
     * Which file a picked document is: by its name, or by its size when a browser renamed the
     * download ("wiki (1).db"). The sizes all differ, and the copy checks the SHA-256 anyway.
     */
    fun identify(name: String, size: Long): SetupFile? = byName(name) ?: ALL.firstOrNull { it.bytes == size }

    /**
     * Copies [input] to [dest] through `dest.part`, hashing as it goes, and renames it to [dest]
     * only when its size and SHA-256 are [file]'s; then writes the stamp next to it. Otherwise the
     * part is deleted and an IOException says what was wrong. [onProgress] gets the bytes copied so
     * far; [cancelled] is polled between reads (a CancellationException then, the part deleted).
     */
    fun copyVerified(
        input: InputStream,
        file: SetupFile,
        dest: File,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): File {
        val part = File(dest.path + PART)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(4 shl 20)
                while (true) {
                    if (cancelled()) throw CancellationException("import cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    copied += n
                    if (copied > file.bytes) throw IOException("${file.name} is larger than ${file.bytes} bytes: not the file this app expects")
                    onProgress(copied)
                }
                out.fd.sync()
            }
            if (copied != file.bytes) throw IOException("${file.name} has $copied bytes, not ${file.bytes}: incomplete or a different version")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != file.sha256) throw IOException("${file.name} is damaged or a different version (SHA-256 does not match)")
            if (!part.renameTo(dest)) throw IOException("could not rename ${part.name}")
            File(dest.path + STAMP).writeText(sha)
            return dest
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /**
     * Is [f] [file], as far as can be told without reading it? Its size must match, and when an
     * import's stamp is next to it, the stamp too (a file adb pushed has none: install.sh checked it).
     */
    fun matches(f: File, file: SetupFile): Boolean {
        if (!f.isFile || f.length() != file.bytes) return false
        val stamp = File(f.path + STAMP)
        return !stamp.isFile || stamp.readText().trim() == file.sha256
    }
}
