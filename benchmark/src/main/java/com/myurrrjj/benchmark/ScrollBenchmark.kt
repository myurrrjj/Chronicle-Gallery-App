package com.myurrrjj.benchmark

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.myurrrjj.chronicle.testRelease"
private const val TAG = "GalleryBenchmark"

private const val FIND_TIMEOUT_MS = 5_000L
private const val MAX_GESTURE_ATTEMPTS = 3
private const val SWIPE_STEPS = 20
private const val FLING_STEPS = 4
private const val FLING_CHAIN_DELAY_MS = 60L
private const val REGROUP_SETTLE_MS = 350L

private const val PINCH_PERCENT = 0.55f
private const val PINCH_SPEED_PX_PER_SEC = 2500

private const val IDLE_TIMEOUT_MS = 1_000L

private fun MacrobenchmarkScope.grantMediaPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        device.executeShellCommand("pm grant $packageName android.permission.READ_MEDIA_IMAGES")
        device.executeShellCommand("pm grant $packageName android.permission.READ_MEDIA_VIDEO")
    } else {
        device.executeShellCommand("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
    }
}

private fun MacrobenchmarkScope.waitForUi() {
    device.waitForIdle(IDLE_TIMEOUT_MS)
}

private fun MacrobenchmarkScope.galleryNode(): UiObject2 =
    checkNotNull(device.wait(Until.findObject(By.pkg(TARGET_PACKAGE)), FIND_TIMEOUT_MS)) {
        "No accessibility node found for $TARGET_PACKAGE within ${FIND_TIMEOUT_MS}ms"
    }

private fun MacrobenchmarkScope.pinch(open: Boolean) {
    val name = if (open) "pinchOpen" else "pinchClose"
    var lastError: Throwable? = null

    repeat(MAX_GESTURE_ATTEMPTS) { attempt ->
        try {
            galleryNode().apply {
                setGestureMargin(device.displayWidth / 10)
                if (open) {
                    pinchOpen(PINCH_PERCENT, PINCH_SPEED_PX_PER_SEC)
                } else {
                    pinchClose(PINCH_PERCENT, PINCH_SPEED_PX_PER_SEC)
                }
            }
            waitForUi()
            SystemClock.sleep(REGROUP_SETTLE_MS)
            waitForUi()
            return
        } catch (e: StaleObjectException) {
            lastError = e
            Log.w(
                TAG,
                "$name hit a stale node (attempt ${attempt + 1}/$MAX_GESTURE_ATTEMPTS), re-resolving"
            )
            waitForUi()
        }
    }

    throw AssertionError("$name failed after $MAX_GESTURE_ATTEMPTS attempts: node kept going stale", lastError)
}

private fun MacrobenchmarkScope.scrollVerticalCycle(upSwipes: Int, downSwipes: Int) {
    galleryNode()
    val centerX = device.displayWidth / 2
    val bottomY = (device.displayHeight * 0.78f).toInt()
    val topY = (device.displayHeight * 0.25f).toInt()

    repeat(upSwipes) {
        device.swipe(centerX, bottomY, centerX, topY, SWIPE_STEPS)
        waitForUi()
    }

    repeat(downSwipes) {
        device.swipe(centerX, topY, centerX, bottomY, SWIPE_STEPS)
        waitForUi()
    }
}

private fun MacrobenchmarkScope.sustainedFling(up: Boolean, bursts: Int) {
    galleryNode()
    val centerX = device.displayWidth / 2
    val startY = if (up) (device.displayHeight * 0.78f).toInt() else (device.displayHeight * 0.22f).toInt()
    val endY = if (up) (device.displayHeight * 0.22f).toInt() else (device.displayHeight * 0.78f).toInt()

    repeat(bursts) {
        device.swipe(centerX, startY, centerX, endY, FLING_STEPS)
        SystemClock.sleep(FLING_CHAIN_DELAY_MS)
    }
    device.waitForIdle(2_000L)
}

@RunWith(AndroidJUnit4::class)
class GalleryCombinedPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun scrollAndPinchFullJourney() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
        }
    ) {
        scrollVerticalCycle(3, 3)
        pinch(false)
        pinch(false)
        scrollVerticalCycle(2, 2)
        pinch(true)
        pinch(true)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun scrollMonthOnly() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
            pinch(false)
        }
    ) {
        scrollVerticalCycle(4, 4)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun scrollYearCold() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
            pinch(open = false)
            pinch(open = false)
        }
    ) {
        scrollVerticalCycle(upSwipes = 5, downSwipes = 5)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun scrollYearAfterDay() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
            scrollVerticalCycle(3, 3)
            pinch(false)
            pinch(false)
        }
    ) {
        scrollVerticalCycle(5, 5)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun sustainedFlingYearUp() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
            pinch(open = false)
            pinch(open = false)
        }
    ) {
        sustainedFling(up = true, bursts = 8)
        sustainedFling(up = false, bursts = 8)
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun sustainedFlingMonthUp() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = null,
        iterations = 5,
        setupBlock = {
            killProcess()
            pressHome()
            grantMediaPermissions()
            startActivityAndWait()
            waitForUi()
            pinch(open = false)
        }
    ) {
        sustainedFling(up = true, bursts = 8)
        sustainedFling(up = false, bursts = 8)
    }
}