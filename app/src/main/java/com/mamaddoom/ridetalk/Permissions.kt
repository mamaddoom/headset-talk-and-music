package com.mamaddoom.ridetalk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Permissions {
    fun has(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasMic(context: Context) = has(context, Manifest.permission.RECORD_AUDIO)

    fun hasBluetooth(context: Context) =
        Build.VERSION.SDK_INT < 31 || has(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** Runtime permissions the app asks for before starting the intercom. */
    fun required(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun missing(context: Context) = required().filterNot { has(context, it) }.toTypedArray()
}
