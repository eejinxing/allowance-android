package com.allowance.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allowance.app.data.*
import com.allowance.core.ledger.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A phone that already has the first version of the database must open the new one with everything it had, plus the standard swaps. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    /** Builds a version 1 database by hand: the tables and identity hash Room wrote for version 1 (see app/schemas/.../1.json). */
    private fun createVersionOne(context: Context) {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val table = "(`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))"
        db.execSQL("CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
        for (t in listOf("bills", "accounts", "goals", "funds", "wishes", "budgets")) db.execSQL("CREATE TABLE IF NOT EXISTS `$t` $table")
        db.execSQL("CREATE TABLE IF NOT EXISTS `entries` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_date` ON `entries` (`date`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '42ad9a5fac1f233ec0092b6e3c8db457')")
        db.execSQL("INSERT INTO profile (id, payload) VALUES (1, ?)", arrayOf<Any>(
            """{"nickname":"Jin","incomeMinor":120000,"incomeFrequency":"MONTHLY","savingsMinor":20000,"started":"2026-10-01","weights":[1,1,1,1,1,1,1],"weightMode":"EVEN"}"""))
        // A month budget as version 1 wrote it: no marks and no paybacks.
        db.execSQL("INSERT INTO budgets (id, payload) VALUES ('2026-10', ?)", arrayOf<Any>(
            """{"month":"2026-10","startDate":"2026-10-01","incomeMinor":120000,"billsMinor":0,"savingsMinor":20000,"weights":[1,1,1,1,1,1,1],"plans":{"2026-10-28":500}}"""))
        db.execSQL("INSERT INTO entries (id, date, payload) VALUES ('e1', '2026-10-05', ?)", arrayOf<Any>(
            """{"id":"e1","date":"2026-10-05","amountMinor":450,"type":"EXPENSE","category":"FOOD","note":"Breakfast","accountId":null,"toAccountId":null,"family":false,"targetId":null,"reservedMinor":0,"createdAt":0}"""))
        db.version = 1
        db.close()
    }

    @Test fun versionOneUpgradesKeepsItsDataAndGainsTheStandardSwaps() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        createVersionOne(context)
        val room = Room.databaseBuilder(context, AllowanceDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            AllowanceRepository(room).use { repository ->
                val data = repository.read()
                assertEquals("Jin", data.settings?.nickname)
                assertEquals(listOf("Breakfast"), data.entries.map { it.note })
                val october = data.budgets.single()
                assertEquals(mapOf("2026-10-28" to 500L), october.plans)
                assertTrue(october.marks.isEmpty() && october.paybacks.isEmpty())
                assertEquals(LedgerEngine.defaultSwaps(), data.swaps)
                // New swaps keep their place in the list after a save, and the old data survives it.
                repository.update { it.copy(swaps = it.swaps + Swap("mine", "FOOD", "", "Dinner out", 1_200, "Cook at home", 400)) }
                val again = repository.read()
                assertEquals("mine", again.swaps.last().id)
                assertEquals(LedgerEngine.defaultSwaps().size + 1, again.swaps.size)
                assertEquals(1, again.entries.size)
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
