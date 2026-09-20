package org.androidlm.research.android

import com.github.luben.zstd.Zstd
import org.androidlm.research.ZstdDecompressor

/**
 * [ZstdDecompressor] over zstd-jni's Android artifact (the AAR carries libzstd-jni for the
 * packaged ABI). Same logic as the JVM test implementation: the corpus blocks were written by
 * python-zstandard's ZstdCompressor.compress(), which records the content size in the frame
 * header, so the output buffer is sized from the frame. Stateless and thread-safe.
 */
class AndroidZstd : ZstdDecompressor {
    override fun decompress(data: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(data)
        require(size in 0..Int.MAX_VALUE) { "zstd frame without a usable content size: $size" }
        return Zstd.decompress(data, size.toInt())
    }
}
