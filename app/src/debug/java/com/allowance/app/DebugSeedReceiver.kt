package com.allowance.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.allowance.app.data.AllowanceRepository
import com.allowance.core.ledger.BackupCodec
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug builds only. Replaces the database with a JSON backup found at `<app external files>/seed.json`, so screens can be
 * checked against known data without typing it in. Restart the app afterwards so it reads the new data.
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = File(context.getExternalFilesDir(null), "seed.json")
        val result = runCatching {
            val data = BackupCodec.decode(file.readText())
            AllowanceRepository(context).use { repository -> runBlocking { repository.update { data } } }
        }
        Log.i("DebugSeed", result.fold({ "seeded from ${file.path}" }, { "failed: ${it.message}" }))
    }
}
