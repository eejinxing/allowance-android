package com.allowance.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.allowance.app.data.AllowanceRepository
import com.allowance.app.data.AppPreferences
import com.allowance.app.data.Preferences
import com.allowance.core.ledger.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Turns parser and requirement failures into messages people can act on. */
fun Throwable.userMessage(fallback: String = "Couldn’t save. Please try again"): String = when (this) {
    is DateTimeParseException -> "Dates should look like YYYY-MM-DD"
    is NumberFormatException, is ArithmeticException -> "Enter a valid amount with at most two decimals"
    is SerializationException -> "This file isn’t a valid Allowance backup"
    else -> message?.takeUnless { it.isBlank() || it == "Failed requirement." || it.startsWith("Collection contains no element") }
        ?.take(180) ?: if (this is IllegalArgumentException) "Something doesn’t look right. Check what you entered" else fallback
}

data class AllowanceState(
    val loading: Boolean = true, val data: AppData = AppData(),
    val preferences: Preferences = Preferences(), val today: LocalDate = LocalDate.now(),
    val busy: Boolean = false, val locked: Boolean = true, val error: String? = null,
)

class AllowanceViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AllowanceRepository(app)
    private val preferences = AppPreferences(app)
    private val mutable = MutableStateFlow(AllowanceState())
    val state = mutable.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val mutex = Mutex()
    override fun onCleared() { repository.close(); super.onCleared() }

    init {
        viewModelScope.launch {
            try {
                repository.refreshMonth(LocalDate.now())
                combine(repository.data, preferences.preferences) { data, prefs -> data to prefs }
                    .collect { (data, prefs) -> mutable.update {
                        it.copy(loading = false, data = data, preferences = prefs, error = null,
                            locked = if (!prefs.pinEnabled) false else it.locked)
                    } }
            } catch (e: Exception) { mutable.update { it.copy(loading = false, error = e.message ?: "Couldn’t read local data") } }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val today = LocalDate.now()
                if (today != state.value.today) {
                    runCatching { repository.refreshMonth(today) }.onFailure { messages.send("Couldn’t roll over the month: ${it.message}") }
                    mutable.update { it.copy(today = today) }
                }
            }
        }
    }

    fun change(message: String = "Saved", onSuccess: () -> Unit = {}, transform: (AppData) -> AppData) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.update { it.copy(busy = true) }
                try { repository.update { LedgerEngine.ensureMonths(transform(it), LocalDate.now()) }; onSuccess(); if (message.isNotEmpty()) messages.send(message) }
                catch (e: Exception) { messages.send(e.userMessage()) }
                finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }

    /**
     * Checks the change against the current snapshot first and throws, so an editor sheet can
     * show the problem inline; snackbars render underneath modal sheets.
     */
    fun submit(message: String = "Saved", onSuccess: () -> Unit = {}, transform: (AppData) -> AppData) {
        LedgerEngine.validate(LedgerEngine.ensureMonths(transform(state.value.data), LocalDate.now()))
        change(message, onSuccess, transform)
    }

    fun notify(message: String) { viewModelScope.launch { messages.send(message) } }
    fun setTheme(theme: String) { viewModelScope.launch { runCatching { preferences.theme(theme) }.onFailure { notify("Couldn’t save the theme") } } }
    fun markBackup(date: LocalDate) { viewModelScope.launch { runCatching { preferences.markBackup(date.toString()) } } }
    fun lock() { if (state.value.preferences.pinEnabled) mutable.update { it.copy(locked = true) } }

    // System file pickers stop this activity; keep the session open while the person uses one.
    private var externalSince = 0L
    fun beginExternalTask() { externalSince = SystemClock.elapsedRealtime() }
    fun onAppStopped() { if (externalSince == 0L) lock() }
    fun onAppStarted() {
        if (externalSince != 0L && SystemClock.elapsedRealtime() - externalSince > 120_000) lock()
        externalSince = 0L
    }
    private fun security(action: suspend () -> Unit) { viewModelScope.launch {
        mutex.withLock {
            mutable.update { it.copy(busy = true) }
            try { action() } catch (e: Exception) { notify(e.userMessage("PIN action failed")) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    } }
    fun unlock(pin: String) = security {
        if (preferences.verify(pin)) mutable.update { it.copy(locked = false) } else notify("Incorrect PIN")
    }
    fun setPin(pin: String, confirmation: String, current: String = "", onSuccess: () -> Unit = {}) = security {
            require(pin == confirmation) { "The two PINs don’t match" }
            if (state.value.preferences.pinEnabled) require(preferences.verify(current)) { "Current PIN is incorrect" }
            preferences.setPin(pin); mutable.update { it.copy(locked = false) }; notify("App lock turned on"); onSuccess()
    }
    fun removePin(pin: String, onSuccess: () -> Unit = {}) = security {
        preferences.removePin(pin); notify("App lock turned off"); onSuccess()
    }
}
