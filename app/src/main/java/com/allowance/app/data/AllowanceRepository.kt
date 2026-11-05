package com.allowance.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.allowance.core.ledger.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.time.LocalDate

class AllowanceRepository(private val db: AllowanceDatabase) : AutoCloseable {
    constructor(context: Context) : this(Room.databaseBuilder(context.applicationContext, AllowanceDatabase::class.java, "allowance.db")
        .addMigrations(MIGRATION_1_2).build())
    private val dao = db.dao()
    private val json = BackupCodec.json
    override fun close() { db.close() }

    val data: Flow<AppData> = dao.observeProfile().map { read() }

    suspend fun read(): AppData = db.withTransaction { readInsideTransaction() }

    private suspend fun readInsideTransaction(): AppData {
        val profile = dao.profiles().firstOrNull() ?: return AppData()
        return AppData(
            settings = json.decodeFromString<BudgetSettings>(profile.payload),
            bills = dao.bills().map { json.decodeFromString<PlannedBill>(it.payload) },
            accounts = dao.accounts().map { json.decodeFromString<WalletAccount>(it.payload) },
            entries = dao.entries().map { json.decodeFromString<LedgerEntry>(it.payload) },
            goals = dao.goals().map { json.decodeFromString<Goal>(it.payload) },
            funds = dao.funds().map { json.decodeFromString<ShoppingFund>(it.payload) },
            wishes = dao.wishes().map { json.decodeFromString<Wish>(it.payload) },
            budgets = dao.budgets().map { json.decodeFromString<MonthBudget>(it.payload) },
            swaps = dao.swaps().map { json.decodeFromString<Swap>(it.payload) },
        )
    }

    /** Reads, validates, and commits as one SQLite transaction, including related fund entries. */
    suspend fun update(transform: (AppData) -> AppData) = db.withTransaction {
        val previous = readInsideTransaction()
        val next = transform(previous)
        LedgerEngine.validate(next)
        if (next != previous) writeInsideTransaction(next)
    }

    suspend fun refreshMonth(today: LocalDate) = update { LedgerEngine.ensureMonths(it, today) }

    private suspend fun writeInsideTransaction(data: AppData) {
        dao.clearEntries(); dao.clearAccounts(); dao.clearBills(); dao.clearGoals()
        dao.clearFunds(); dao.clearWishes(); dao.clearBudgets(); dao.clearSwaps(); dao.clearProfile()
        dao.bills(data.bills.map { BillRow(it.id, json.encodeToString(it)) })
        dao.accounts(data.accounts.map { AccountRow(it.id, json.encodeToString(it)) })
        dao.entries(data.entries.map { EntryRow(it.id, it.date, json.encodeToString(it)) })
        dao.goals(data.goals.map { GoalRow(it.id, json.encodeToString(it)) })
        dao.funds(data.funds.map { FundRow(it.id, json.encodeToString(it)) })
        dao.wishes(data.wishes.map { WishRow(it.id, json.encodeToString(it)) })
        dao.budgets(data.budgets.map { BudgetRow(it.month, json.encodeToString(it)) })
        dao.swaps(data.swaps.map { SwapRow(it.id, json.encodeToString(it)) })
        data.settings?.let { dao.profile(ProfileRow(payload = json.encodeToString(it))) }
    }
}
