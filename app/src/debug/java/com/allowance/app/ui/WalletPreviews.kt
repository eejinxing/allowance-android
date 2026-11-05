package com.allowance.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.*
import java.time.LocalDate

internal fun walletDesignState() = AllowanceState(loading = false, locked = false,
    today = LocalDate.of(2026, 10, 25), data = AppData(
        accounts = listOf(WalletAccount("bank", "Daily account", openingMinor = 245050),
            WalletAccount("ewallet", "E-wallet", "EWALLET", openingMinor = 32000),
            WalletAccount("cash", "Pocket cash", "CASH", openingMinor = 15000)),
        funds = listOf(ShoppingFund("short", "Sony Headphones", 50000, color = 0),
            ShoppingFund("over", "Spotify", 31000, color = 3),
            ShoppingFund("ready", "Weekend trip", 120000, color = 2)),
        entries = listOf(
            LedgerEntry("fund-short", "2026-10-25", 20000, EntryType.FUND_ALLOCATION, targetId = "short", createdAt = 1),
            LedgerEntry("fund-over", "2026-10-25", 33000, EntryType.FUND_ALLOCATION, targetId = "over", createdAt = 2),
            LedgerEntry("fund-ready", "2026-10-25", 120000, EntryType.FUND_ALLOCATION, targetId = "ready", createdAt = 3))))

@Preview(name = "Wallet · stacked cards and funds", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun WalletDesignPreview() {
    WalletPreviewFrame(walletDesignState())
}

@Preview(name = "Wallet · first account", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun WalletEmptyPreview() {
    WalletPreviewFrame(AllowanceState(loading = false, locked = false))
}

@Composable
private fun WalletPreviewFrame(state: AllowanceState) {
    MaterialTheme(colorScheme = lightColorScheme(primary = WalletAccent)) {
        Scaffold(containerColor = WalletHeaderBase, bottomBar = {
            NavigationBar(containerColor = WalletBackground) {
                listOf("Home" to AppIcons.WbSunny, "History" to AppIcons.ReceiptLong,
                    "Analytics" to AppIcons.BarChart, "Wallet" to AppIcons.AccountBalanceWallet,
                    "Goals" to AppIcons.Flag, "AI" to AppIcons.AutoAwesome).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(selected = index == 3, onClick = {}, icon = { Icon(icon, null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = WalletAccent, selectedTextColor = WalletAccent,
                            indicatorColor = androidx.compose.ui.graphics.Color(0xFFE8EDFF)))
                }
            }
        }) { padding ->
            Surface(Modifier.padding(padding), color = WalletBackground) { WalletPage(state, open = {}) }
        }
    }
}
