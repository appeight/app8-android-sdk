package dev.app8.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.app8.engine.App8
import dev.app8.engine.App8Instance

/** Runs the Facetheory demo app (shoppu-web `packages/app-dsl/demo/facetheory`) from the sample's assets. */
class MainActivity : ComponentActivity() {
    private val instance: App8Instance by lazy {
        App8.instance(this, AssetAppDataSource(assets, "facetheory")).also { i ->
            i.eventBus.subscribe { Log.i("App8Sample", "event ${it.name} ${it.payload}") }
            i.analyticsBus.observe { Log.i("App8Sample", "analytics ${it.name} ${it.properties}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { instance.App(onError = { Log.e("App8Sample", "app failed", it) }) }
    }
}
