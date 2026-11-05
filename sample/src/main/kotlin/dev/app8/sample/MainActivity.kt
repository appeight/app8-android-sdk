package dev.app8.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.app8.engine.App8
import dev.app8.engine.core.EngineVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("App8 ${EngineVersion.CURRENT} supported=${App8.isSupported}")
            }
        }
    }
}
