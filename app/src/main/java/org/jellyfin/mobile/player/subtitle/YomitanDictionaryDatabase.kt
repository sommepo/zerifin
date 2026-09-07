package org.jellyfin.mobile.player.subtitle

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class YomitanDictionaryDatabase(context: Context) :
    SQLiteOpenHelper(context, "japanese_dictionary.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE term(id INTEGER PRIMARY KEY, expression TEXT NOT NULL, reading TEXT NOT NULL, " +
                "definition TEXT NOT NULL, score INTEGER NOT NULL, dictionary_id INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX term_expression ON term(expression)")
        db.execSQL("CREATE INDEX term_reading ON term(reading)")
        createDictionaryTables(db)
        addEnabledColumn(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Preserve all installed definitions when adding independent metadata dictionaries.
            db.execSQL("ALTER TABLE term ADD COLUMN dictionary_id INTEGER NOT NULL DEFAULT 1")
            db.execSQL("DROP TABLE IF EXISTS term_staging")
            createDictionaryTables(db)
            db.execSQL(
                "INSERT INTO dictionary(id, title, occurrence_based) " +
                    "SELECT 1, COALESCE((SELECT value FROM metadata WHERE key = 'title'), 'Imported dictionary'), 0 " +
                    "WHERE EXISTS (SELECT 1 FROM term)",
            )
            db.execSQL("DROP TABLE IF EXISTS metadata")
        }
        if (oldVersion < 3) addEnabledColumn(db)
    }

    private fun addEnabledColumn(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE dictionary ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
    }

    private fun createDictionaryTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dictionary(id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, " +
                "occurrence_based INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE term_staging(expression TEXT NOT NULL, reading TEXT NOT NULL, " +
                "definition TEXT NOT NULL, score INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE frequency(id INTEGER PRIMARY KEY, expression TEXT NOT NULL, reading TEXT, " +
                "value REAL, display_value TEXT NOT NULL, dictionary_id INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX frequency_expression ON frequency(expression)")
        db.execSQL("CREATE INDEX frequency_dictionary ON frequency(dictionary_id)")
        db.execSQL("CREATE INDEX term_dictionary ON term(dictionary_id)")
        db.execSQL(
            "CREATE TABLE frequency_staging(expression TEXT NOT NULL, reading TEXT, " +
                "value REAL, display_value TEXT NOT NULL)",
        )
    }
}
