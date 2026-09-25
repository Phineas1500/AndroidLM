package org.androidlm.research

/**
 * The SQL access the retrieval code needs. Implementations must be backed by an SQLite build
 * with FTS5 (the corpus index is an FTS5 table and stemming goes through fts5vocab).
 *
 * Column values are returned as: `Long` (INTEGER), `Double` (REAL), `String` (TEXT),
 * `ByteArray` (BLOB) or `null`. Arguments are bound positionally to `?` and may be `Long`,
 * `Int`, `Double`, `String`, `ByteArray` or `null`.
 */
interface SqlDatabase {
    /** Runs a SELECT and returns all rows, in the order SQLite produced them. */
    fun query(sql: String, vararg args: Any?): List<Array<Any?>>

    /** Runs one statement that returns no rows (DDL, INSERT, DELETE). */
    fun exec(sql: String, vararg args: Any?)

    /**
     * Makes the query running on this connection, if any, stop and throw; callable from any
     * thread. Later queries are unaffected. The default does nothing (the query runs to the end).
     */
    fun interrupt() {}
}

/** Decompresses one complete zstd frame (the corpus stores article text in such frames). */
interface ZstdDecompressor {
    fun decompress(data: ByteArray): ByteArray
}
