/*
 * LensWhisper Object Segmenter
 * Copyright (C) 2026 Mohammad Amiri Moalla
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.appricodes.lens_whisper.segmenter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_CAPTURE_DIMENSION = 1280

/**
 * The app's own screen: a camera view and three buttons — Detect Objects, Identify Objects and
 * Colors, Explore Photo by Touch — plus About. Everything else this app does is the
 * [SegmenterService] other apps call.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (!granted) view.say("Camera permission is needed to take photos.")
    }
    LaunchedEffect(Unit) { if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    LaunchedEffect(hasCamera) { if (hasCamera) controller.bindToLifecycle(lifecycleOwner) }

    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    fun announce(text: String) {
        result = text
        view.say(text)
    }

    fun runWithPhoto(block: suspend (Bitmap) -> Unit) {
        if (busy) { view.say("Please wait for the current photo to finish."); return }
        if (!hasCamera) { permissionLauncher.launch(Manifest.permission.CAMERA); return }
        busy = true
        scope.launch {
            try {
                val photo = controller.capture(context)
                block(photo)
            } catch (e: Exception) {
                announce("Could not take a photo.")
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Text(
            text = result.ifEmpty { "Point the camera at something and choose a button." },
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Detect Objects", "Detect Objects. Names the objects in view.", Modifier.weight(1f)) {
                runWithPhoto { photo ->
                    val found = withContext(Dispatchers.Default) { YoloSegmenter.detect(context, photo) }
                    announce(if (found.isEmpty()) "No objects detected." else found.joinToString(", ") { phrase(it.confidence, it.label) })
                }
            }
            BigButton("Identify Objects and Colors", "Identify objects and each one's main colors.", Modifier.weight(1f)) {
                runWithPhoto { photo ->
                    val text = withContext(Dispatchers.Default) {
                        val found = YoloSegmenter.detect(context, photo)
                        if (found.isEmpty()) "No objects detected."
                        else found.joinToString(", ") { phrase(it.confidence, it.label, it.namedColors(photo)) }
                    }
                    announce(text)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(
                "Explore Photo by Touch",
                "Explore by touch. Takes a photo and opens it full screen — move your finger over it to hear the object under it.",
                Modifier.weight(1f)
            ) {
                runWithPhoto { photo ->
                    val file = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "explore.jpg").also { f -> f.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                    }
                    context.startActivity(Intent(context, ExploreActivity::class.java).putExtra(ExploreActivity.EXTRA_PATH, file.absolutePath))
                }
            }
            BigButton("About", "About this app, its source code and its API for other apps.", Modifier.weight(1f)) { showAbout = true }
        }
    }

    if (showAbout) AboutDialog(onClose = { showAbout = false })
}

@Composable
private fun BigButton(label: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(96.dp).semantics { contentDescription = description }) {
        Text(label, fontSize = 17.sp)
    }
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("About LensWhisper Object Explorer") },
        text = {
            Text(
                "Finds the objects in a photo — their names, colours and exact outlines — entirely on " +
                    "this phone. It has no internet permission, so nothing it sees can leave the device.\n\n" +
                    "Other apps, such as LensWhisper, can send it a photo and get the objects back; the " +
                    "API guide explains how.\n\n" +
                    "Object detection uses YOLOv8m-seg by Ultralytics (AGPL-3.0). This app is free " +
                    "software under the GNU Affero General Public License v3.0; its complete source code is " +
                    "on GitHub.\n\n" +
                    "Results are AI guesses and can be wrong. Never rely on them for safety, health or money."
            )
        },
        confirmButton = { TextButton(onClick = { Links.open(context, Links.SOURCE_CODE) }) { Text("Source code") } },
        dismissButton = {
            Row {
                TextButton(onClick = { Links.open(context, Links.API_GUIDE) }) { Text("API guide") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

/** Takes one photo, upright, scaled down to [MAX_CAPTURE_DIMENSION]. */
private suspend fun LifecycleCameraController.capture(context: android.content.Context): Bitmap =
    suspendCancellableCoroutine { cont ->
        takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val raw = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    val scale = minOf(1f, MAX_CAPTURE_DIMENSION.toFloat() / maxOf(raw.width, raw.height))
                    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
                    cont.resume(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
        })
    }
