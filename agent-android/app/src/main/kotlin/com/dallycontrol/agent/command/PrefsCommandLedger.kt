package com.dallycontrol.agent.command

import android.annotation.SuppressLint
import android.content.Context
import com.dallycontrol.core.command.CommandLedger
import com.dallycontrol.core.command.StoredCommandLedger

/**
 * The agent's [CommandLedger], in its own SharedPreferences file. Written with commit(), not apply(): a reboot
 * command is recorded right before the device goes down, and that write has to be on disk by then.
 */
@SuppressLint("ApplySharedPref")
class PrefsCommandLedger(context: Context) : CommandLedger by StoredCommandLedger(
    load = { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) },
    save = { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, it).commit() },
) {
    private companion object {
        const val FILE = "mdm_command_ledger"
        const val KEY = "results"
    }
}
