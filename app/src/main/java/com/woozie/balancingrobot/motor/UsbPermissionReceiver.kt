package com.woozie.balancingrobot.motor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Permission result is observed by the next explicit USB refresh. */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
