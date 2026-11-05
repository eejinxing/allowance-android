package com.allowance.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*
import java.time.LocalDate

// Debug-only fixtures with enough data to exercise charts, rings and the card deck.
private fun richState(): AllowanceState {
    val today = LocalDate.of(2026, 10, 25)
    fun entry(id: String, day: Int, minor: Long, category: String = "FOOD", family: Boolean = false, type: EntryType = EntryType.EXPENSE,
        target: String? = null, account: String? = "bank") = LedgerEntry(id, "2026-10-%02d".format(day), minor, type, category, id,
        accountId = account, family = family, targetId = target, createdAt = day * 100L + id.hashCode().mod(90))
    val spends = listOf(1 to 1850L, 2 to 900L, 3 to 4500L, 5 to 2300L, 6 to 1200L, 8 to 6800L, 9 to 3100L, 11 to 2500L, 12 to 1500L,
        14 to 5200L, 15 to 2100L, 17 to 4000L, 19 to 1800L, 21 to 7500L, 22 to 2600L, 24 to 3300L, 25 to 2950L)
    val categories = listOf("FOOD", "TRANSPORT", "SHOPPING", "FUN", "LIVING", "HEALTH")
    val entries = spends.mapIndexed { i, (day, minor) -> entry("e$i", day, minor, categories[i % categories.size]) } +
        listOf(entry("f1", 4, 3000, "FOOD", true), entry("f2", 10, 4500, "LIVING", true), entry("f3", 18, 2500, "FUN", true), entry("f4", 24, 6000, "TRANSPORT", true)) +
        listOf(entry("g1", 2, 180000, type = EntryType.GOAL_DEPOSIT, target = "travel"), entry("g2", 3, 400000, type = EntryType.GOAL_DEPOSIT, target = "car"),
            entry("g3", 4, 100000, type = EntryType.GOAL_DEPOSIT, target = "laptop"), entry("g4", 5, 35000, type = EntryType.GOAL_DEPOSIT, target = "concert"))
    return AllowanceState(loading = false, locked = false, today = today, data = AppData(
        settings = BudgetSettings(nickname = "Jin", incomeMinor = 243243, savingsMinor = 30000, started = "2026-10-01"),
        accounts = listOf(WalletAccount("bank", "Bank account", openingMinor = 250000), WalletAccount("cash", "Cash", "CASH", 73600),
            WalletAccount("ewallet", "TNG eWallet", "EWALLET", 41250)),
        entries = entries, goals = listOf(Goal("travel", "Travel fund", 300000, color = 0), Goal("car", "Car fund", 1000000, color = 1),
            Goal("laptop", "New laptop", 200000, color = 2), Goal("concert", "Concert tickets", 35000, color = 4)),
        funds = listOf(ShoppingFund("shoes", "New sneakers", 38000)), wishes = emptyList()))
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Color.White, secondary = Yellow, background = Color.White,
        surfaceContainer = Color(0xFFF5F5F2), surfaceContainerLowest = Color.White, surfaceVariant = Color(0xFFEFEFEA), outline = Color(0xFF87897E),
        outlineVariant = Color(0xFFDADBD2), onSurface = Ink, onSurfaceVariant = Color(0xFF6B6B63), secondaryContainer = Yellow, onSecondaryContainer = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) { content() }
    }
}

@Preview(name = "Insights", showBackground = true, widthDp = 393, heightDp = 852) @Composable fun InsightsRichPreview() = Frame { InsightsPage(richState()) }
@Preview(name = "Goals", showBackground = true, widthDp = 393, heightDp = 852) @Composable fun GoalsRichPreview() = Frame { GoalsPage(richState(), {}, removeWish = {}) }
@Preview(name = "Wallet", showBackground = true, widthDp = 393, heightDp = 852) @Composable fun WalletRichPreview() = Frame { WalletPage(richState(), {}) }
@Preview(name = "Today", showBackground = true, widthDp = 393, heightDp = 852) @Composable fun TodayRichPreview() = Frame { TodayPage(richState(), {}) }
@Preview(name = "Advisor", showBackground = true, widthDp = 393, heightDp = 852) @Composable fun AdvisorRichPreview() = Frame { AdvisorPage(richState(), {}, addWish = { _, _ -> }) }
