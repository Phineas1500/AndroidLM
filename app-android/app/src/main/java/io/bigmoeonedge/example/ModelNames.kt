package io.bigmoeonedge.example

/**
 * A readable name for a model file: "Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf" becomes
 * "Qwen3.6-35B-A3B · 2-bit". The quantization suffix is dropped for its bit width; shard suffixes
 * ("-00001-of-00002") go; a name that does not follow the usual pattern is shown as it is.
 */
fun friendlyModelName(fileName: String): String {
    val base = fileName.removeSuffix(".gguf").replace(SHARD, "")
    val quant = QUANT.find(base) ?: return base
    val name = base.substring(0, quant.range.first).trimEnd('-', '_', '.')
    val q = quant.groupValues[2].uppercase()
    val bits = when {
        q.startsWith("MXFP4") -> "4-bit"
        q == "F16" || q == "BF16" -> "16-bit"
        q == "F32" -> "32-bit"
        else -> BITS.find(q)?.groupValues?.get(1)?.let { "$it-bit" }
    }
    return if (name.isNotEmpty() && bits != null) "$name · $bits" else base
}

/** Display names for a list of files, falling back to the file name where two would collide. */
fun friendlyModelNames(fileNames: List<String>): List<String> {
    val names = fileNames.map { friendlyModelName(it) }
    return names.mapIndexed { i, n -> if (names.count { it == n } > 1) fileNames[i] else n }
}

private val SHARD = Regex("""-\d{5}-of-\d{5}$""")
private val QUANT = Regex("""[-_.](UD-)?(I?Q\d_[A-Z0-9_]+|Q\d_\d|MXFP4[A-Z0-9_]*|BF16|F16|F32)$""", RegexOption.IGNORE_CASE)
private val BITS = Regex("""Q(\d)""")
