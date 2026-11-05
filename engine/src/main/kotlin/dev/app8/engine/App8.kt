package dev.app8.engine

import android.content.Context
import android.os.Build

/** Entry point, mirroring `App8` on iOS. */
public object App8 {
    /** Lowest Android API level the SDK renders on (Android 12). */
    public const val MIN_SDK: Int = Build.VERSION_CODES.S

    /** True when this device can run App8 apps. */
    public val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * Creates an instance for [dataSource] (iOS `App8.instance(dataSource:)`).
     * @throws App8Error.UnsupportedPlatform below Android 12; the host shows its own fallback.
     */
    @JvmStatic
    @JvmOverloads
    public fun instance(context: Context, dataSource: App8DataSource, config: App8Config = App8Config()): App8Instance {
        if (config.enforcePlatformFloor && Build.VERSION.SDK_INT < MIN_SDK) {
            throw App8Error.UnsupportedPlatform(Build.VERSION.SDK_INT, MIN_SDK)
        }
        return App8Instance(context.applicationContext, dataSource, config)
    }
}
