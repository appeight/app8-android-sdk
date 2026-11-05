package dev.app8.engine

import android.os.Build

/** Entry point, mirroring `App8` on iOS. */
public object App8 {
    /** Lowest Android API level the SDK renders on (Android 12). */
    public const val MIN_SDK: Int = Build.VERSION_CODES.S

    /** True when this device can run App8 apps. */
    public val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
}
