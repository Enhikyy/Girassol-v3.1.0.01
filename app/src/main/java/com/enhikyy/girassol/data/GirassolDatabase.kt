package com.enhikyy.girassol.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Banco SQLite em modo WAL. O esquema canônico vem de `assets/schema.sql`
 * (especificação do projeto) acrescido das tabelas de eventos e snapshots.
 * Apenas o [PersistenceWriter] escreve (escritor único).
 */
class GirassolDatabase(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val schema = context.assets.open("schema.sql").bufferedReader().use { it.readText() }
        splitStatements(schema)
            .filterNot { it.startsWith("PRAGMA", ignoreCase = true) }
            .forEach { db.execSQL(it) }
        EXTRA_TABLES.forEach { db.execSQL(it) }
        EpisodicFts.ensure(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        EXTRA_TABLES.forEach { db.execSQL(it) }
        EpisodicFts.ensure(db)
    }

    companion object {
        const val DB_NAME = "girassol.db"
        const val DB_VERSION = 2

        fun splitStatements(sql: String): List<String> = sql
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }

        val EXTRA_TABLES = listOf(
            """CREATE TABLE IF NOT EXISTS canonical_events (
                seq INTEGER PRIMARY KEY,
                world_tick INTEGER NOT NULL,
                rules_version INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                actor_id TEXT,
                payload TEXT NOT NULL,
                prev_hash TEXT NOT NULL,
                event_hash TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_events_tick ON canonical_events(world_tick)",
            """CREATE TABLE IF NOT EXISTS world_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world_tick INTEGER NOT NULL,
                seed INTEGER NOT NULL,
                rules_version INTEGER NOT NULL,
                state_sha256 TEXT NOT NULL,
                last_event_hash TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                created_utc INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS stress_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world_tick INTEGER NOT NULL,
                kind TEXT NOT NULL,
                description TEXT NOT NULL,
                created_utc INTEGER NOT NULL
            )"""
        )
    }
}
