package com.enhikyy.girassol.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.enhikyy.girassol.core.world.WorldStateHashChain

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
        WorldStateHashChain.SQLITE_TRIGGERS.forEach { db.execSQL(it) }
        EpisodicFts.ensure(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        EXTRA_TABLES.forEach { db.execSQL(it) }
        WorldStateHashChain.SQLITE_TRIGGERS.forEach { db.execSQL(it) }
        EpisodicFts.ensure(db)
    }

    companion object {
        const val DB_NAME = "girassol.db"
        const val DB_VERSION = 5

        @Volatile private var instance: GirassolDatabase? = null
        /** Instância única (escritor + registro de modelos compartilham a mesma conexão WAL). */
        fun get(context: Context): GirassolDatabase =
            instance ?: synchronized(this) { instance ?: GirassolDatabase(context.applicationContext).also { instance = it } }

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
            """CREATE TABLE IF NOT EXISTS registered_models (
                model_key TEXT PRIMARY KEY,
                file_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                sha256_head TEXT NOT NULL,
                founder_id TEXT,
                founder_name TEXT,
                founder_sex TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                imported_utc INTEGER NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER NOT NULL,
                world_seed INTEGER NOT NULL,
                world_tick INTEGER NOT NULL,
                wall_clock_ms INTEGER NOT NULL,
                citizen_id TEXT,
                author TEXT NOT NULL,
                author_name TEXT NOT NULL,
                text TEXT NOT NULL,
                engine TEXT NOT NULL,
                PRIMARY KEY (world_seed, id)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_chat_citizen ON chat_messages(citizen_id, id)",
            // v4.1: estado serializado (GRSB + FNV-1a 64) do Orçamento Sináptico, um por dimensão D.
            """CREATE TABLE IF NOT EXISTS synaptic_budget_state (
                dim INTEGER PRIMARY KEY,
                blob BLOB NOT NULL,
                checksum TEXT NOT NULL,
                tasks INTEGER NOT NULL,
                protected_rank INTEGER NOT NULL,
                updated_utc INTEGER NOT NULL
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
