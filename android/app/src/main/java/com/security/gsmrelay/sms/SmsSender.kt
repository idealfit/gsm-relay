package com.security.gsmrelay.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object SmsSender {
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val smsManager = SmsManager.getDefault()
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        return true
    }
}
