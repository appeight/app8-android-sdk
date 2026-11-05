package dev.app8.engine

/** Errors thrown by the SDK (iOS `App8.Error`). */
public sealed class App8Error(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The device is below the SDK floor (Android 12, API 31) or below the app's own
     * `platforms.android.minSdk`. The host shows its own native fallback.
     */
    public class UnsupportedPlatform(public val deviceSdk: Int, public val requiredSdk: Int) :
        App8Error("App8 needs Android API $requiredSdk or newer; this device runs API $deviceSdk.")

    public class AppInitFailed : App8Error("App8 initialization failed.")
    public class InvalidUtf8(public val name: String) : App8Error("Resource is not valid UTF-8: $name")
    public class AppDecodeFailed(cause: Throwable) : App8Error("Failed to decode app model: ${cause.message}", cause)
    public class AppMissingInitialScreenId : App8Error("App is missing required initialScreenId.")
    public class ScreenDecodeFailed(public val id: String, cause: Throwable) :
        App8Error("Failed to decode screen '$id': ${cause.message}", cause)
    public class ComponentDecodeFailed(public val id: String, cause: Throwable) :
        App8Error("Failed to decode component '$id': ${cause.message}", cause)
    public class MissingRequiredParams(public val params: List<String>) :
        App8Error("Missing required screen parameters: ${params.joinToString(", ")}")
    public class ScreenNotFound(public val screenId: String) : App8Error("Screen '$screenId' not found.")
    public class NotImplemented(public val api: String) : App8Error("$api is not implemented yet.")
}
