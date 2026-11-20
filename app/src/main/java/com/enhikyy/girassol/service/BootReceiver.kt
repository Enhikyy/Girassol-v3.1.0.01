package com.enhikyy.girassol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Após reinicialização do aparelho, retoma o mundo a partir do último snapshot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (GirassolContinuousService.shouldAutoStart(context)) {
            runCatching { GirassolContinuousService.start(context) }
        }
    }
}
