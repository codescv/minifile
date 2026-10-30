package com.minifile.explorer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

class VolumeChangeReceiver(
    private val onVolumeEvent: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        onVolumeEvent()
    }

    companion object {
        fun register(context: Context, onChanged: () -> Unit): () -> Unit {
            val mediaReceiver = VolumeChangeReceiver(onChanged)
            val mediaFilter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            val usbReceiver = VolumeChangeReceiver(onChanged)
            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

            ContextCompat.registerReceiver(
                context,
                mediaReceiver,
                mediaFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                usbFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )

            return {
                runCatching { context.unregisterReceiver(mediaReceiver) }
                runCatching { context.unregisterReceiver(usbReceiver) }
            }
        }
    }
}
