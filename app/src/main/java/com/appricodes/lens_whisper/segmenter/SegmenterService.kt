/*
 * LensWhisper Object Segmenter
 * Copyright (C) 2026 Mohammad Amiri Moalla
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.appricodes.lens_whisper.segmenter

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.appricodes.lens_whisper.segmenter.client.SegmenterContract
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The service other apps bind to (see api/API.md). Open to every app by design, so it defends
 * itself instead of trusting callers:
 * - the app holds no INTERNET permission at all, so nothing it is given can leave the phone;
 * - images are read into memory only — never written to storage, never logged;
 * - an encoded image larger than [SegmenterContract.MAX_IMAGE_BYTES] is refused before decoding,
 *   and decoding is downsampled to [SegmenterContract.MAX_DECODED_DIMENSION], so a hostile image
 *   can't exhaust memory;
 * - each calling app (by UID) may have one request running, and at most [MAX_PENDING] requests
 *   are accepted at once across all apps; the rest get [SegmenterContract.STATUS_BUSY] at once.
 */
class SegmenterService : Service() {

    private companion object {
        const val TAG = "SegmenterService"
        const val MAX_PENDING = 4
        const val MODEL_NAME = "yolov8m-seg-coco-int8"
        const val MIN_ALLOWED_CONFIDENCE = 0.05f
    }

    private val callersInFlight = ConcurrentHashMap.newKeySet<Int>()
    private val pending = AtomicInteger(0)

    private val binder = object : ISegmenterService.Stub() {
        override fun getApiVersion(): Int = SegmenterContract.API_VERSION

        override fun segment(image: ParcelFileDescriptor?, options: Bundle?): Bundle {
            val uid = Binder.getCallingUid()
            try {
                if (image == null) return error(SegmenterContract.STATUS_BAD_IMAGE, "No image")
                if (!callersInFlight.add(uid)) return error(SegmenterContract.STATUS_BUSY, "A request from this app is already running")
                try {
                    if (pending.incrementAndGet() > MAX_PENDING) return error(SegmenterContract.STATUS_BUSY, "Too many requests")
                    return run(image, options)
                } finally {
                    pending.decrementAndGet()
                    callersInFlight.remove(uid)
                }
            } finally {
                runCatching { image?.close() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun error(status: Int, message: String) = Bundle().apply {
        putInt(SegmenterContract.KEY_STATUS, status)
        putString(SegmenterContract.KEY_MESSAGE, message)
    }

    private fun run(image: ParcelFileDescriptor, options: Bundle?): Bundle {
        val bytes = readLimited(image) ?: return error(SegmenterContract.STATUS_TOO_LARGE, "Image larger than ${SegmenterContract.MAX_IMAGE_BYTES} bytes")
        val bitmap = decode(bytes) ?: return error(SegmenterContract.STATUS_BAD_IMAGE, "Not a readable image")
        val minConfidence = (options?.getFloat(SegmenterContract.OPTION_MIN_CONFIDENCE, YoloSegmenter.DEFAULT_MIN_CONFIDENCE)
            ?: YoloSegmenter.DEFAULT_MIN_CONFIDENCE).coerceIn(MIN_ALLOWED_CONFIDENCE, 1f)
        val maxInstances = (options?.getInt(SegmenterContract.OPTION_MAX_INSTANCES, YoloSegmenter.MAX_DETECTIONS)
            ?: YoloSegmenter.MAX_DETECTIONS).coerceIn(1, YoloSegmenter.MAX_DETECTIONS)
        return try {
            val segments = YoloSegmenter.detect(this, bitmap, minConfidence, maxInstances)
            Bundle().apply {
                putInt(SegmenterContract.KEY_STATUS, SegmenterContract.STATUS_OK)
                putString(SegmenterContract.KEY_MODEL, MODEL_NAME)
                putInt(SegmenterContract.KEY_IMAGE_WIDTH, bitmap.width)
                putInt(SegmenterContract.KEY_IMAGE_HEIGHT, bitmap.height)
                putParcelableArrayList(SegmenterContract.KEY_INSTANCES, ArrayList(segments.map { s ->
                    Bundle().apply {
                        putInt(SegmenterContract.KEY_CLASS_ID, s.classId)
                        putString(SegmenterContract.KEY_LABEL, s.label)
                        putFloat(SegmenterContract.KEY_CONFIDENCE, s.confidence)
                        putFloatArray(SegmenterContract.KEY_BOX, floatArrayOf(s.boxLeft, s.boxTop, s.boxRight, s.boxBottom))
                        putInt(SegmenterContract.KEY_MASK_WIDTH, s.maskWidth)
                        putInt(SegmenterContract.KEY_MASK_HEIGHT, s.maskHeight)
                        putByteArray(SegmenterContract.KEY_MASK, SegmenterContract.packMask(s.mask))
                    }
                }))
            }
        } catch (e: Exception) {
            // The exception only — never anything about the image itself.
            Log.e(TAG, "Segmentation failed", e)
            error(SegmenterContract.STATUS_INTERNAL_ERROR, e.javaClass.simpleName)
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads the whole descriptor, or returns null once it passes the size limit. Works for pipes
     *  and sockets too, whose size isn't known up front. */
    private fun readLimited(fd: ParcelFileDescriptor): ByteArray? {
        val out = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > SegmenterContract.MAX_IMAGE_BYTES) return null
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > SegmenterContract.MAX_DECODED_DIMENSION || bounds.outHeight / sample > SegmenterContract.MAX_DECODED_DIMENSION) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
