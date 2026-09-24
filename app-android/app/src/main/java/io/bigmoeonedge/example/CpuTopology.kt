package io.bigmoeonedge.example

import java.io.File

/**
 * Which cores the engine's compute threads should run on. Measured on a Pixel 8 Pro: with the
 * threads left to the scheduler the engine decoded at 0.9-1.3 tok/s; pinned to the four big
 * cores (cpus 4-7) it did 3.9 tok/s, and the flash lanes, no longer sharing cores with spinning
 * compute threads, tripled their throughput. Adding the X3 prime core as a fifth thread gave 4.4.
 * The mask goes to the engine as BMOE_CPUMASK.
 */
object CpuTopology {
    private data class Core(val id: Int, val maxKhz: Long)

    private fun cores(): List<Core> =
        (File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: emptyArray())
            .mapNotNull { dir ->
                val khz = runCatching { File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
                khz?.let { Core(dir.name.substring(3).toInt(), it) }
            }
            .sortedBy { it.id }

    /** Cores above the little (slowest) cluster, or null on a CPU with a single cluster. */
    fun fastCoreCount(): Int? {
        val all = cores()
        val clusters = all.groupBy { it.maxKhz }.toSortedMap()
        if (clusters.size < 2) return null
        return all.count { it.maxKhz != clusters.firstKey() }.takeIf { it > 0 }
    }

    /**
     * Hex mask of the cores to pin [threads] compute threads to, or null to leave placement to
     * the scheduler. Cores are grouped into clusters by maximum frequency; the slowest cluster
     * (the little cores) is excluded, and the cluster whose size matches the thread count is
     * preferred so that every barrier stays within one cluster. Otherwise the fastest cores
     * above the little cluster are taken. Homogeneous CPUs get no mask.
     */
    fun computeMask(threads: Int): String? {
        val all = cores()
        if (all.size < 2 || threads < 1) return null
        val clusters = all.groupBy { it.maxKhz }.toSortedMap()
        if (clusters.size < 2) return null
        val little = clusters.firstKey()
        val fast = all.filter { it.maxKhz != little }
        if (fast.size < threads) return null
        val exact = clusters.filterKeys { it != little }.values.firstOrNull { it.size == threads }
        val chosen = exact ?: fast.sortedByDescending { it.maxKhz }.take(threads)
        val mask = chosen.fold(0L) { m, c -> m or (1L shl c.id) }
        return java.lang.Long.toHexString(mask)
    }
}
