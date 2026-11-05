package com.allowance.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.allowance.core.ledger.BackupCodec
import com.allowance.core.ledger.LedgerEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

// Each collection has its own table. Payloads are versioned domain records;
// transactions additionally index date for future SQL paging/filtering.
@Entity(tableName = "profile") data class ProfileRow(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "bills") data class BillRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "accounts") data class AccountRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "entries", indices = [Index("date")])
data class EntryRow(@PrimaryKey val id: String, val date: String, val payload: String)
@Entity(tableName = "goals") data class GoalRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "funds") data class FundRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "wishes") data class WishRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "budgets") data class BudgetRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "swaps") data class SwapRow(@PrimaryKey val id: String, val payload: String)

@Dao
interface AllowanceDao {
    @Query("SELECT * FROM profile") fun observeProfile(): Flow<List<ProfileRow>>
    @Query("SELECT * FROM profile") suspend fun profiles(): List<ProfileRow>
    @Query("SELECT * FROM bills") suspend fun bills(): List<BillRow>
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<AccountRow>
    @Query("SELECT * FROM entries ORDER BY date DESC") suspend fun entries(): List<EntryRow>
    @Query("SELECT * FROM goals") suspend fun goals(): List<GoalRow>
    @Query("SELECT * FROM funds") suspend fun funds(): List<FundRow>
    @Query("SELECT * FROM wishes") suspend fun wishes(): List<WishRow>
    @Query("SELECT * FROM budgets") suspend fun budgets(): List<BudgetRow>
    // Swaps keep the order they were saved in, because that is the order of the tiles.
    @Query("SELECT * FROM swaps ORDER BY rowid") suspend fun swaps(): List<SwapRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun profile(row: ProfileRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bills(rows: List<BillRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun accounts(rows: List<AccountRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun entries(rows: List<EntryRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun goals(rows: List<GoalRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun funds(rows: List<FundRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun wishes(rows: List<WishRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun budgets(rows: List<BudgetRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun swaps(rows: List<SwapRow>)

    @Query("DELETE FROM profile") suspend fun clearProfile()
    @Query("DELETE FROM bills") suspend fun clearBills()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM goals") suspend fun clearGoals()
    @Query("DELETE FROM funds") suspend fun clearFunds()
    @Query("DELETE FROM wishes") suspend fun clearWishes()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM swaps") suspend fun clearSwaps()
}

@Database(entities = [ProfileRow::class, BillRow::class, AccountRow::class, EntryRow::class,
    GoalRow::class, FundRow::class, WishRow::class, BudgetRow::class, SwapRow::class], version = 2, exportSchema = true)
abstract class AllowanceDatabase : RoomDatabase() { abstract fun dao(): AllowanceDao }

/**
 * Version 2 adds the cheaper swaps. Calendar marks and paybacks live inside each month's budget record, which simply gains
 * fields with defaults, so only the new table is needed. Everyone starts with the standard swaps, like a new install.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `swaps` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
        for (swap in LedgerEngine.defaultSwaps())
            db.execSQL("INSERT OR REPLACE INTO swaps (id, payload) VALUES (?, ?)", arrayOf<Any>(swap.id, BackupCodec.json.encodeToString(swap)))
    }
}
