package com.allowance.core.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class BudgetSettings(
    val nickname: String = "",
    val incomeMinor: Long = 0,
    val incomeFrequency: String = "MONTHLY",
    val savingsMinor: Long = 0,
    val started: String = LocalDate.now().toString(),
    val weights: List<Int> = List(7) { 1 },
    /** "EVEN" uses [weights]; "HABIT" weights each weekday by recent real spending. */
    val weightMode: String = "EVEN",
)

@Serializable
data class PlannedBill(
    val id: String, val name: String, val amountMinor: Long,
    val frequency: String = "MONTHLY", val family: Boolean = false,
)

@Serializable
data class WalletAccount(
    val id: String, val name: String, val type: String = "BANK",
    val openingMinor: Long = 0, val color: Int = 0, val archived: Boolean = false,
)

@Serializable
enum class EntryType {
    EXPENSE, INCOME, SCHEDULED_INCOME, TRANSFER, ADJUSTMENT, BILL_PAYMENT,
    FUND_ALLOCATION, FUND_PURCHASE, FUND_RELEASE, GOAL_DEPOSIT, GOAL_WITHDRAWAL,
}

@Serializable
data class LedgerEntry(
    val id: String, val date: String, val amountMinor: Long,
    val type: EntryType = EntryType.EXPENSE,
    val category: String = "FOOD", val note: String = "",
    val accountId: String? = null, val toAccountId: String? = null,
    val family: Boolean = false, val targetId: String? = null,
    val reservedMinor: Long = 0, val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Goal(val id: String, val name: String, val targetMinor: Long, val deadline: String = "", val color: Int = 0, val archived: Boolean = false)

@Serializable
data class ShoppingFund(
    val id: String, val name: String, val priceMinor: Long,
    val deadline: String = "", val color: Int = 0, val boughtOn: String? = null, val archived: Boolean = false,
)

@Serializable
data class Wish(val id: String, val name: String, val priceMinor: Long, val date: String)

/**
 * A cheaper way to do something you already do, e.g. the bus instead of the train. Not tied to a month: one list for the whole app.
 * [icon] is kept only so backups stay identical to the web app's; the screens draw their own icon from the names.
 */
@Serializable
data class Swap(
    val id: String, val category: String = "FOOD", val icon: String = "",
    val usualName: String, val usualMinor: Long, val cheaperName: String, val cheaperMinor: Long,
)

/** What one swap did on one day of a payback, by name so deleting a swap later doesn't change history. */
@Serializable
data class PaybackSwap(val name: String, val usual: String = "", val icon: String = "", val savingMinor: Long = 0)

@Serializable
data class PaybackDay(val date: String, val cutMinor: Long, val swaps: List<PaybackSwap> = emptyList())

/** An overspend that was bought and then paid back over the following days. */
@Serializable
data class Payback(val id: String, val date: String, val name: String, val amountMinor: Long, val days: List<PaybackDay> = emptyList())

@Serializable
data class MonthBudget(
    val month: String, val startDate: String, val incomeMinor: Long,
    val billsMinor: Long, val savingsMinor: Long, val weights: List<Int>,
    /** Planned spend for specific dates in this month, ISO date to sen. */
    val plans: Map<String, Long> = emptyMap(),
    /** Overspends paid back in this month, so the swaps can be shown again on those days. */
    val paybacks: List<Payback> = emptyList(),
    /** Calendar marks by ISO date: RED (nothing spent), TEAL (only what you spent) or YELLOW (daily bills counted too). */
    val marks: Map<String, String> = emptyMap(),
)

@Serializable
data class AppData(
    val version: Int = 1,
    val settings: BudgetSettings? = null,
    val bills: List<PlannedBill> = emptyList(),
    val accounts: List<WalletAccount> = emptyList(),
    val entries: List<LedgerEntry> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val funds: List<ShoppingFund> = emptyList(),
    val wishes: List<Wish> = emptyList(),
    val budgets: List<MonthBudget> = emptyList(),
    /** Older backups have none, and then start with the standard set. */
    val swaps: List<Swap> = LedgerEngine.defaultSwaps(),
)

object BackupCodec {
    /** Lenient on read, so a backup from the web app (or a newer version) with extra fields still opens. */
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)
    fun decode(text: String): AppData {
        require(text.length <= 10_000_000) { "Backup is larger than 10 MB" }
        return json.decodeFromString(AppData.serializer(), text).also(LedgerEngine::validate)
    }
}
