package com.example.baselineprofile1

import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.myurrrjj.chronicle.testRelease"

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateGalleryBaselineProfile() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand("pm grant $packageName android.permission.READ_MEDIA_IMAGES")
            device.executeShellCommand("pm grant $packageName android.permission.READ_MEDIA_VIDEO")
        } else {
            device.executeShellCommand("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
        }

        pressHome()
        startActivityAndWait()
        device.wait(Until.findObject(By.pkg(packageName)), 5_000)

        val cx = device.displayWidth / 2
        val bottom = (device.displayHeight * 0.78f).toInt()
        val top = (device.displayHeight * 0.25f).toInt()

        fun scrollUp(times: Int) = repeat(times) {
            device.swipe(cx, bottom, cx, top, 20)
            device.waitForIdle(1_000)
        }

        fun pinch(open: Boolean) {
            val node = device.findObject(By.pkg(packageName)) ?: return
            node.setGestureMargin(device.displayWidth / 10)
            if (open) {
                node.pinchOpen(0.45f, 60)
            } else {
                node.pinchClose(0.45f, 60)
            }
            device.waitForIdle(1_000)
        }

        scrollUp(2)
        pinch(open = false)
        scrollUp(2)
        pinch(open = false)
        scrollUp(3)
        pinch(open = true)
        pinch(open = true)
    }
}