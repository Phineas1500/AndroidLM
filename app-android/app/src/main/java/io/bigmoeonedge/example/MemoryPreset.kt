package io.bigmoeonedge.example

import android.content.Context
import android.os.Build

/**
 * AndroidLM: how much of the phone's memory the model may use, as one choice. A preset sets the
 * expert cache and how many prompt tokens are read at once (the compute buffer reserved for that
 * width); Auto picks the preset that matches the phone's RAM.
 *
 * The cache is lossless: a bigger one only means fewer expert reads from flash, so it is the
 * first thing to give back on a smaller phone. Reading prompts 1,280 tokens at a time instead of
 * 512 reads them faster (a 1,216-token prompt 36.7 s against 45.5 s on a Pixel 8 Pro,
 * notes/2026-09-25-iqk-port.md) for a compute buffer of 1,256 MiB instead of 502.
 */
enum class MemoryPreset(
    val key: String, val label: String, val cacheMb: Int, val ubatch: Int, val dense: DenseWeights, val blurb: String,
) {
    // Dense weights in plain app memory: pinning them copies all 1.2GB once more while the model
    // loads, and on a phone with 4GB free that peak is what the low-memory killer stops.
    PHONE_8(
        "8", "8 GB phone", 1500, 512, DenseWeights.ANON,
        "A 1,500 MiB expert cache (the smallest that does not thrash), prompts read 512 tokens at a time: the app takes about 4 GB.",
    ),
    PHONE_12(
        "12", "12 GB phone", 5000, 1280, DenseWeights.AHWB,
        "A 5,000 MiB expert cache, prompts read 1,280 tokens at a time: the app takes about 8 GB.",
    ),
    PHONE_16(
        "16", "16 GB phone or more", 8000, 1280, DenseWeights.AHWB,
        "An 8,000 MiB expert cache, prompts read 1,280 tokens at a time: the app takes about 11 GB.",
    );

    companion object {
        const val AUTO = "auto"

        /** The phone's RAM as the system reports it: a "12GB" phone shows about 11.2-11.6 GiB. */
        fun totalGib(ctx: Context): Double {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0.0
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        }

        fun forRam(gib: Double): MemoryPreset = when {
            gib >= 14.5 -> PHONE_16
            gib >= 11.0 -> PHONE_12
            else -> PHONE_8
        }

        /** The preset [key] names, or for [AUTO] (or an unknown key) the one this phone's RAM calls for. */
        fun resolve(ctx: Context, key: String): MemoryPreset =
            values().firstOrNull { it.key == key } ?: forRam(totalGib(ctx))

        /** The Android emulator: its RAM is the host computer's, so its size is the user's choice. */
        val isEmulator: Boolean by lazy {
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_") ||
                Build.FINGERPRINT.startsWith("generic")
        }
    }
}
