package com.example.blekmp.platform

import platform.UIKit.UIDevice
import com.example.blekmp.Platform

actual class IosPlatform : Platform {
    actual override val name: String
        get() = UIDevice.currentDevice.name + " (iOS)"
}

actual fun getPlatform(): Platform = IosPlatform()