package org.androidlm.research.android

import android.database.Cursor
import io.requery.android.database.CursorWindow
import io.requery.android.database.sqlite.SQLiteCursor
import io.requery.android.database.sqlite.SQLiteDatabase
import org.androidlm.research.SqlDatabase
import java.io.File

/**
 * [SqlDatabase] over requery's sqlite-android, which ships its own SQLite (3.49.0, built with
 * SQLITE_ENABLE_FTS5, so FTS5, bm25() and fts5vocab are always there; the platform SQLite does
 * not promise them).
 *
 * The file is opened in place with SQLITE_OPEN_READONLY: nothing is copied, the main database
 * cannot be written, and on a read-only connection the library issues none of its usual setup
 * pragmas (page size, journal mode, android_metadata). The temp schema stays writable, which is
 * what Corpus needs for its scratch FTS5 tables; the library is built with SQLITE_TEMP_STORE=3,
 * so temp tables live in memory and never touch the disk. Write-ahead logging must stay off:
 * without it the library keeps exactly one connection, and temp tables belong to a connection.
 *
 * One instance per Corpus, used from a single thread. Rows travel through a CursorWindow that
 * is private to each query and sized by [cursorWindowBytes] (a plain malloc, not shared memory,
 * so pages that are never written cost nothing). A single row must fit in it; the largest rows
 * are the compressed blocks, a few hundred KB each.
 */
class AndroidSqlDatabase(
    path: File,
    private val cursorWindowBytes: Int = DEFAULT_CURSOR_WINDOW_BYTES,
) : SqlDatabase, AutoCloseable {

    private val label = path.name
    private val db: SQLiteDatabase

    init {
        require(path.isAbsolute) { "database path must be absolute: $path" }
        require(path.isFile) { "database file not found (or not readable): $path" }
        db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private val cursorFactory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
        SQLiteCursor(driver, editTable, query).apply { window = CursorWindow(label, cursorWindowBytes) }
    }

    private fun bind(args: Array<out Any?>): Array<Any?> = Array(args.size) { i ->
        when (val a = args[i]) {
            null, is Long, is Double, is String, is ByteArray -> a
            is Int -> a.toLong()
            else -> throw IllegalArgumentException("unsupported argument type " + a.javaClass)
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Array<Any?>> =
        db.rawQueryWithFactory(cursorFactory, sql, bind(args), null).use { c ->
            val n = c.columnCount
            val rows = ArrayList<Array<Any?>>()
            try {
                while (c.moveToNext()) {
                    rows.add(Array(n) { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> throw IllegalStateException("unknown column type " + c.getType(i))
                        }
                    })
                }
            } catch (e: IllegalStateException) {
                // what the window reports when a row was too large to be copied into it
                throw IllegalStateException(
                    "could not read row ${rows.size} of \"$sql\" (a row larger than the " +
                        "$cursorWindowBytes-byte cursor window?): ${e.message}", e,
                )
            }
            rows
        }

    override fun exec(sql: String, vararg args: Any?) {
        db.execSQL(sql, bind(args))
    }

    /** The bundled library's SQLite version, e.g. "3.49.0". */
    fun sqliteVersion(): String = query("select sqlite_version()").first()[0] as String

    override fun close() = db.close()

    companion object {
        /** Several times the largest compressed block, so a row never fails to fit. */
        const val DEFAULT_CURSOR_WINDOW_BYTES = 8 * 1024 * 1024
    }
}
