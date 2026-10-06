/*
 * LensWhisper Object Segmenter
 * Copyright (C) 2026 Mohammad Amiri Moalla
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.appricodes.lens_whisper.segmenter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Toast

/** Links shown in the About dialog. */
object Links {
    const val SOURCE_CODE = "https://github.com/appricodes/LensWhisper-Segmenter"
    const val API_GUIDE = "https://github.com/appricodes/LensWhisper-Segmenter/blob/main/api/API.md"

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found. The address is $url", Toast.LENGTH_LONG).show()
        }
    }
}

/** Says [text] through TalkBack (or whichever screen reader is running). */
@Suppress("DEPRECATION")
fun View.say(text: String) = announceForAccessibility(text)

/** A short tick, so a finger crossing onto a new object is felt as well as heard. */
fun Context.tick() {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    } ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
}

/** "Cup", "Most likely cup", "Maybe cup" — the hedge says how sure the model is. */
fun phrase(confidence: Float, label: String, colors: List<String> = emptyList()): String {
    val hedge = when {
        confidence >= 0.6f -> ""
        confidence >= 0.4f -> "Most likely "
        else -> "Maybe "
    }
    val colorPart = if (colors.isEmpty()) "" else ", " + colors.joinToString(" and ").lowercase()
    return (hedge + label + colorPart).replaceFirstChar { it.uppercase() }
}
