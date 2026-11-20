package com.example.baselineprofile1

import android.graphics.Point
import android.os.Build
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PINCH_STEPS = 12
private const val FLING_STEPS = 8

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateGalleryBaselineProfile() {
        val targetPackage = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "com.myurrrjj.chronicle"

        rule.collect(
            packageName = targetPackage,
            maxIterations = 5,
            stableIterations = 2,
            includeInStartupProfile = true,
        ) {
            grantMediaPermissions(targetPackage)
            device.executeShellCommand("cmd statusbar collapse")
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), 5_000L)
            Thread.sleep(1_500L)

            flingVerticalCycle(upCount = 3, downCount = 2)

            pinchZoom(open = false)
            flingVerticalCycle(upCount = 3, downCount = 2)

            pinchZoom(open = false)
            flingVerticalCycle(upCount = 4, downCount = 3)

            pinchZoom(open = true)
            pinchZoom(open = true)
            flingVerticalCycle(upCount = 1, downCount = 1)
        }
    }

    private fun MacrobenchmarkScope.grantMediaPermissions(targetPackage: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand("pm grant $targetPackage android.permission.READ_MEDIA_IMAGES")
            device.executeShellCommand("pm grant $targetPackage android.permission.READ_MEDIA_VIDEO")
        } else {
            device.executeShellCommand("pm grant $targetPackage android.permission.READ_EXTERNAL_STORAGE")
        }
    }

    private fun MacrobenchmarkScope.pinchZoom(open: Boolean) {
        val cx = device.displayWidth / 2
        val cy = device.displayHeight / 2
        val far = (device.displayWidth * 0.30f).toInt()
        val near = (device.displayWidth * 0.08f).toInt()

        val startOffset = if (open) near else far
        val endOffset = if (open) far else near

        device.findObject(UiSelector()).performTwoPointerGesture(
            Point(cx - startOffset, cy - startOffset),
            Point(cx + startOffset, cy + startOffset),
            Point(cx - endOffset, cy - endOffset),
            Point(cx + endOffset, cy + endOffset),
            PINCH_STEPS
        )
        Thread.sleep(600L)
    }

    private fun MacrobenchmarkScope.flingVerticalCycle(upCount: Int, downCount: Int) {
        val cx = device.displayWidth / 2
        val topY = (device.displayHeight * 0.30f).toInt()
        val bottomY = (device.displayHeight * 0.72f).toInt()

        repeat(upCount) {
            device.swipe(cx, bottomY, cx, topY, FLING_STEPS)
            Thread.sleep(350L)
        }

        repeat(downCount) {
            device.swipe(cx, topY, cx, bottomY, FLING_STEPS)
            Thread.sleep(350L)
        }
    }
}