package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.example.service.PackageInstallReceiver
import com.example.service.PackageInstallerHelper
import com.example.service.StorageService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageInstallerTest {

    @Test
    fun testAppReceivedDirectory_usesCacheReceivedFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = StorageService.getAppReceivedDirectory(context)
        assertEquals(File(context.cacheDir, "received").absolutePath, dir.absolutePath)
        assertTrue(dir.exists())
    }

    @Test
    fun testAppFileNaming_apkAndApks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(StorageService.isAppFileName("MyApp.apk"))
        assertTrue(StorageService.isAppFileName("SplitApp.apks"))
        assertFalse(StorageService.isAppFileName("document.pdf"))

        val apkFinal = StorageService.getUniqueFinalFile("MyApp.apk", context)
        assertEquals("MyApp.apk", apkFinal.name)
        assertEquals(File(context.cacheDir, "received").absolutePath, apkFinal.parentFile?.absolutePath)

        val apksFinal = StorageService.getUniqueFinalFile("SplitApp.apks", context)
        assertEquals("SplitApp.apks", apksFinal.name)
        assertEquals(File(context.cacheDir, "received").absolutePath, apksFinal.parentFile?.absolutePath)
    }

    @Test
    fun testStatusMessageMapping() {
        val receiver = PackageInstallReceiver()
        val mapMethod = PackageInstallReceiver::class.java.getDeclaredMethod(
            "mapStatusToMessage",
            Int::class.javaPrimitiveType,
            String::class.java
        )
        mapMethod.isAccessible = true

        val conflict = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE_CONFLICT, "") as String
        assertEquals("Already installed with a different signature. Uninstall it first.", conflict)

        val storage = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE_STORAGE, "") as String
        assertEquals("Not enough storage", storage)

        val invalid = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE_INVALID, "") as String
        assertEquals("The file is damaged or incomplete", invalid)

        val incompatible = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, "") as String
        assertEquals("This app needs a newer Android version", incompatible)

        val cpu = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_NO_MATCHING_ABIS") as String
        assertEquals("This app doesn't fit this phone (CPU)", cpu)

        val custom = mapMethod.invoke(receiver, PackageInstaller.STATUS_FAILURE, "Unknown custom error") as String
        assertEquals("Unknown custom error", custom)
    }
}
