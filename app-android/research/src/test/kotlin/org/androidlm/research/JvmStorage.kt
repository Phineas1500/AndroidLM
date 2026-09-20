package org.androidlm.research

import com.github.luben.zstd.Zstd
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement

/** [SqlDatabase] over sqlite-jdbc (its bundled SQLite has FTS5). Opens the file read-only. */
class JdbcSqlDatabase(path: File) : SqlDatabase, AutoCloseable {
    private val conn: Connection = SQLiteConfig().apply { setReadOnly(true) }
        .createConnection("jdbc:sqlite:" + path.absolutePath)

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val st = conn.prepareStatement(sql)
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is Int -> st.setLong(i + 1, a.toLong())
                is Long -> st.setLong(i + 1, a)
                is Double -> st.setDouble(i + 1, a)
                is String -> st.setString(i + 1, a)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> throw IllegalArgumentException("unsupported argument type " + a.javaClass)
            }
        }
        return st
    }

    override fun query(sql: String, vararg args: Any?): List<Array<Any?>> = prepare(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            val rows = ArrayList<Array<Any?>>()
            while (rs.next()) {
                rows.add(Array(n) { c ->
                    when (val v = rs.getObject(c + 1)) {
                        is Int -> v.toLong()
                        is Short -> v.toLong()
                        is Byte -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v // Long, Double, String, ByteArray, null
                    }
                })
            }
            rows
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        prepare(sql, args).use { it.execute() }
    }

    override fun close() = conn.close()
}

/** [ZstdDecompressor] over zstd-jni; corpus frames carry their content size. */
class JniZstdDecompressor : ZstdDecompressor {
    override fun decompress(data: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(data)
        require(size in 0..Int.MAX_VALUE) { "zstd frame without a usable content size: $size" }
        return Zstd.decompress(data, size.toInt())
    }
}
