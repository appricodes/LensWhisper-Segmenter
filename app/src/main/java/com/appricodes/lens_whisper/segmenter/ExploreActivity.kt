/*
 * LensWhisper Object Segmenter
 * Copyright (C) 2026 Mohammad Amiri Moalla
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.appricodes.lens_whisper.segmenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explore Photo by Touch: the photo fills the screen; moving a finger over it (with TalkBack's
 * explore-by-touch, or plain dragging without a screen reader) names the object under the finger
 * and its colours, with a tick each time the finger crosses onto a different object.
 */
class ExploreActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { ExploreScreen(path, onClose = { finish() }) }
            }
        }
    }
}

private class Explored(val segment: Segment, val description: String)

@Composable
private fun ExploreScreen(path: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var objects by remember { mutableStateOf<List<Explored>?>(null) }
    var current by remember { mutableStateOf<Explored?>(null) }

    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) { path?.let { BitmapFactory.decodeFile(it) } }
        if (loaded == null) { view.say("Could not open the photo."); onClose(); return@LaunchedEffect }
        photo = loaded
        view.say("Finding objects…")
        val found = withContext(Dispatchers.Default) {
            YoloSegmenter.detect(context, loaded).map { Explored(it, phrase(it.confidence, it.label, it.namedColors(loaded))) }
        }
        objects = found
        view.say(
            if (found.isEmpty()) "No objects found."
            else "${found.size} object${if (found.size == 1) "" else "s"} found. Move your finger over the photo."
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = "Photo. Move your finger over it to hear what is under it." }
                .pointerInput(objects) {
                    val list = objects ?: return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val p = event.changes.firstOrNull()?.position ?: continue
                            val nx = p.x / size.width
                            val ny = p.y / size.height
                            // Smallest object first, so a cup on a table names the cup.
                            val hit = list.filter { it.segment.containsNormalizedPoint(nx, ny) }
                                .minByOrNull { (it.segment.boxRight - it.segment.boxLeft) * (it.segment.boxBottom - it.segment.boxTop) }
                            if (hit != current) {
                                current = hit
                                if (hit != null) { context.tick(); view.say(hit.description) }
                            }
                        }
                    }
                }
        ) {
            photo?.let {
                // FillBounds, like the mask grid: a normalized touch point is the same normalized
                // point in the photo, with no letterbox offset to undo.
                Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            if (objects == null) Text("Finding objects…", fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val list = objects.orEmpty()
                    view.say(if (list.isEmpty()) "No objects found." else list.joinToString(". ") { it.description })
                },
                modifier = Modifier.weight(1f).height(72.dp)
            ) { Text("List all objects", fontSize = 17.sp) }
            Button(onClick = onClose, modifier = Modifier.weight(1f).height(72.dp)) { Text("Close", fontSize = 17.sp) }
        }
    }
}
