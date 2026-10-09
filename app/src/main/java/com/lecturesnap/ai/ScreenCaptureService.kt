
package com.lecturesnap.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START =
            "com.lecturesnap.ai.START_CAPTURE"

        const val ACTION_STOP =
            "com.lecturesnap.ai.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "lecture_capture"
        private const val NOTIFICATION_ID = 1001

        // Resolution used for comparing frames.
        private const val SAMPLE_SIZE = 48

        // Ignore small pixel differences caused by flicker,
        // cursor movement, and minor animation.
        private const val COLOR_DIFF_THRESHOLD = 100

        // A change must affect at least this many sample pixels.
        private const val CHANGED_PIXEL_THRESHOLD = 220

        // A candidate slide must settle for this long.
        private const val SETTLE_DELAY_MS = 350L

        // Minimum time between saved screenshots.
        private const val SAVE_COOLDOWN_MS = 900L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var lastSavedSample: IntArray? = null
    private var candidateSample: IntArray? = null
    private var candidateBitmap: Bitmap? = null

    private var lastSavedTime = 0L
    private var firstFrame = true
    private var isReleasing = false

    private var saveCandidateRunnable: Runnable? = null

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (projection != null) {
                    return START_NOT_STICKY
                }

                isReleasing = false
                createNotificationChannel()

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )

                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE, 0
                )

                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )
                    }

                if (resultCode == 0 || resultData == null) {
                    stopCapture()
                    return START_NOT_STICKY
                }

                try {
                    val manager = getSystemService(
                        MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    projection = manager.getMediaProjection(
                        resultCode,
                        resultData
                    )

                    startCapture()
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopCapture()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        val activeProjection = projection ?: return

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        captureThread = HandlerThread(
            "LectureSnapCapture"
        ).also {
            it.start()
        }

        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )

        imageReader!!.setOnImageAvailableListener(
            { reader ->
                var image: Image? = null
                var bitmap: Bitmap? = null

                try {
                    image = reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                    bitmap = imageToBitmap(image)
                    processFrame(bitmap)

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    bitmap?.recycle()
                    image?.close()
                }
            },
            captureHandler
        )

        activeProjection.registerCallback(
            projectionCallback,
            captureHandler
        )

        virtualDisplay = activeProjection.createVirtualDisplay(
            "LectureSnapDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding =
            rowStride - pixelStride * image.width

        val paddedWidth =
            image.width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val croppedBitmap = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            image.width,
            image.height
        )

        paddedBitmap.recycle()
        return croppedBitmap
    }

    private fun processFrame(bitmap: Bitmap) {
        val sample = makeSample(bitmap)

        // Save the first screen immediately.
        if (firstFrame) {
            if (saveScreenshot(bitmap)) {
                lastSavedSample = sample
                lastSavedTime = System.currentTimeMillis()
                firstFrame = false
            }
            return
        }

        val savedSample = lastSavedSample ?: return

        // If the screen is still essentially the last saved slide,
        // cancel any pending duplicate.
        if (!hasMeaningfulChange(savedSample, sample)) {
            cancelPendingCandidate()
            return
        }

        val existingCandidate = candidateSample

        if (
            existingCandidate == null ||
            hasMeaningfulChange(existingCandidate, sample)
        ) {
            // A genuinely different screen appeared.
            // Start a new stability timer.
            cancelPendingCandidate()

            candidateSample = sample
            candidateBitmap = bitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )

            scheduleCandidateSave()
        } else {
            // Only minor changes from the candidate were detected.
            // Update the screenshot to the newest frame without
            // restarting the timer.
            candidateBitmap?.recycle()

            candidateBitmap = bitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )
        }
    }

    private fun scheduleCandidateSave() {
        val handler = captureHandler ?: return

        val task = Runnable {
            savePendingCandidate()
        }

        saveCandidateRunnable = task
        handler.postDelayed(task, SETTLE_DELAY_MS)
    }

    private fun savePendingCandidate() {
        saveCandidateRunnable = null

        val handler = captureHandler ?: return
        val bitmap = candidateBitmap ?: return
        val sample = candidateSample ?: return

        // If the cooldown has not finished, wait only for the
        // remaining cooldown time.
        val elapsed = System.currentTimeMillis() - lastSavedTime

        if (elapsed < SAVE_COOLDOWN_MS) {
            val remaining = SAVE_COOLDOWN_MS - elapsed

            val task = Runnable {
                savePendingCandidate()
            }

            saveCandidateRunnable = task
            handler.postDelayed(task, remaining)
            return
        }

        val savedSample = lastSavedSample

        // Recheck just before saving, in case this candidate
        // is effectively identical to the previous screenshot.
        if (
            savedSample != null &&
            !hasMeaningfulChange(savedSample, sample)
        ) {
            cancelPendingCandidate()
            return
        }

        if (saveScreenshot(bitmap)) {
            lastSavedSample = sample
            lastSavedTime = System.currentTimeMillis()
        }

        cancelPendingCandidate()
    }

    private fun cancelPendingCandidate() {
        saveCandidateRunnable?.let { task ->
            captureHandler?.removeCallbacks(task)
        }

        saveCandidateRunnable = null

        candidateBitmap?.recycle()
        candidateBitmap = null
        candidateSample = null
    }

    private fun makeSample(bitmap: Bitmap): IntArray {
        val smallBitmap = Bitmap.createScaledBitmap(
            bitmap,
            SAMPLE_SIZE,
            SAMPLE_SIZE,
            true
        )

        val pixels = IntArray(
            SAMPLE_SIZE * SAMPLE_SIZE
        )

        smallBitmap.getPixels(
            pixels,
            0,
            SAMPLE_SIZE,
            0,
            0,
            SAMPLE_SIZE,
            SAMPLE_SIZE
        )

        smallBitmap.recycle()
        return pixels
    }

    private fun hasMeaningfulChange(
        old: IntArray,
        current: IntArray
    ): Boolean {
        var changedPixels = 0

        for (i in current.indices) {
            val oldPixel = old[i]
            val newPixel = current[i]

            val redDifference = abs(
                ((oldPixel shr 16) and 0xFF) -
                        ((newPixel shr 16) and 0xFF)
            )

            val greenDifference = abs(
                ((oldPixel shr 8) and 0xFF) -
                        ((newPixel shr 8) and 0xFF)
            )

            val blueDifference = abs(
                (oldPixel and 0xFF) -
                        (newPixel and 0xFF)
            )

            val totalDifference =
                redDifference +
                        greenDifference +
                        blueDifference

            if (totalDifference > COLOR_DIFF_THRESHOLD) {
                changedPixels++

                if (changedPixels >= CHANGED_PIXEL_THRESHOLD) {
                    return true
                }
            }
        }

        return false
    }

    private fun saveScreenshot(bitmap: Bitmap): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(bitmap)
            } else {
                saveToAppFolder(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveToMediaStore(bitmap: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "LectureSnap_${System.currentTimeMillis()}.jpg"
            )
            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
            )
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/LectureSnap"
            )
            put(
                MediaStore.Images.Media.IS_PENDING,
                1
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return false

        return try {
            val output = contentResolver.openOutputStream(uri)
                ?: return false

            val compressed = output.use {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    it
                )
            }

            if (!compressed) {
                contentResolver.delete(uri, null, null)
                return false
            }

            val completedValues = ContentValues().apply {
                put(
                    MediaStore.Images.Media.IS_PENDING,
                    0
                )
            }

            contentResolver.update(
                uri,
                completedValues,
                null,
                null
            )

            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppFolder(bitmap: Bitmap): Boolean {
        val folder = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "LectureSnap"
        )

        if (!folder.exists() && !folder.mkdirs()) {
            return false
        }

        val file = File(
            folder,
            "LectureSnap_${System.currentTimeMillis()}.jpg"
        )

        return try {
            FileOutputStream(file).use {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    it
                )
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun stopCapture() {
        if (isReleasing) return

        isReleasing = true
        releaseCaptureResources()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCaptureResources() {
        cancelPendingCandidate()

        val activeProjection = projection
        projection = null

        imageReader?.setOnImageAvailableListener(null, null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        if (activeProjection != null) {
            try {
                activeProjection.unregisterCallback(
                    projectionCallback
                )
            } catch (_: Exception) {
                // Projection may already have stopped.
            }

            try {
                activeProjection.stop()
            } catch (_: Exception) {
                // Projection may already have stopped.
            }
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        lastSavedSample = null
        firstFrame = true
        lastSavedTime = 0L
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lecture screen capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LectureSnap AI")
                .setContentText("Lecture capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LectureSnap AI")
                .setContentText("Lecture capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!isReleasing) {
            isReleasing = true
            releaseCaptureResources()
        }

        super.onDestroy()
    }
}
