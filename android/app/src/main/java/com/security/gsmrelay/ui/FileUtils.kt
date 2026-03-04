package com.security.gsmrelay.ui

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

fun readTextFromUri(context: android.content.Context, uri: android.net.Uri): String {
    val builder = StringBuilder()
    context.contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input)).useLines { lines ->
            lines.forEach { builder.append(it).append("\n") }
        }
    }
    return builder.toString().trim()
}

fun writeTextToUri(context: android.content.Context, uri: android.net.Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        OutputStreamWriter(output).use { it.write(text) }
    }
}
