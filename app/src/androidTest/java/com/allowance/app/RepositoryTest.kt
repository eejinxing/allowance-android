package com.allowance.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.allowance.app.data.*
import com.allowance.core.ledger.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    @Test fun concurrentWritesPersistWithoutLostUpdatesAndInvalidMutationRollsBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AllowanceDatabase::class.java).build()
        AllowanceRepository(db).use { repository ->
            val today = LocalDate.now()
            repository.update { LedgerEngine.configure(it, BudgetSettings("Test", 120_000), emptyList(), today) }
            coroutineScope {
                repeat(20) { index -> launch(Dispatchers.Default) {
                    repository.update { LedgerEngine.saveEntry(it, LedgerEntry("entry-$index", today.toString(), 100), today) }
                } }
            }
            assertEquals(20, repository.read().entries.size)
            val before = repository.read()
            try {
                repository.update { it.copy(entries = it.entries + LedgerEntry("invalid", today.toString(), -1)) }
                fail("Invalid data must not be committed")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, repository.read())
            val backup = BackupCodec.encode(before)
            repository.update { AppData() }
            assertNull(repository.read().settings)
            repository.update { BackupCodec.decode(backup) }
            assertEquals(before.entries.toSet(), repository.read().entries.toSet())
        }
    }
}
