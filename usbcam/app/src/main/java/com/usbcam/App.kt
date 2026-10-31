package com.usbcam

import android.app.Application
import com.serenegiant.utils.UVCUtils

/**
 * 只是把 Application 交给 libuvc 库，库内部需要用 Context 做 USB 权限/文件操作。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
    }
}
