package dev.app8.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.app8.engine.debug.App8StyleGallery

/**
 * Debug screen with every style primitive the renderer draws.
 * `adb shell am start -n dev.app8.sample/.StyleGalleryActivity`
 */
class StyleGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App8StyleGallery() }
    }
}
