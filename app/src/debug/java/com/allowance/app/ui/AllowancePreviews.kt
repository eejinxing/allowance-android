package com.allowance.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.allowance.app.AllowanceState
import com.allowance.core.ledger.AppData
import com.allowance.core.ledger.BudgetSettings
import com.allowance.core.ledger.LedgerEntry
import java.time.LocalDate

// Debug-only fixtures: no database, ViewModel, or personal ledger data is read.
private fun previewState(): AllowanceState {
    val date = LocalDate.of(2026, 10, 25)
    return AllowanceState(
        loading = false,
        locked = false,
        today = date,
        data = AppData(
            settings = BudgetSettings(nickname = "Demo", incomeMinor = 300000,
                savingsMinor = 60000, started = "2026-10-01"),
            entries = listOf(LedgerEntry(id = "preview-lunch", date = date.toString(),
                amountMinor = 1500, note = "Lunch", createdAt = 1L)),
        ),
    )
}

@Preview(name = "Today - Light", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayLightPreview() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Mint, background = androidx.compose.ui.graphics.Color(0xFFF5F5F0))) {
        Surface { TodayPage(previewState(), open = {}) }
    }
}

@Preview(name = "Today - Dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint)) {
        Surface { TodayPage(previewState(), open = {}) }
    }
}
