package dev.app8.engine.navigation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.app8.engine.actions.PhotoCaptureRequest
import dev.app8.engine.actions.PhotoPick
import dev.app8.engine.icons.MaterialSymbols
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import java.io.File
import java.util.UUID

/** A `capturePhoto` waiting for its UI, and where its answer goes. */
internal class PendingPhoto(val request: PhotoCaptureRequest, val onResult: (PhotoPick) -> Unit)

/**
 * The photo UI of `capturePhoto` (app8-dsl `docs/photos.md`):
 * - `cameraOrLibrary`: a Material 3 modal bottom sheet with two rows (straight to the library on
 *   a device without a camera);
 * - camera: the system camera app (`ACTION_IMAGE_CAPTURE`) writing into the engine's
 *   FileProvider; no permission, unless the host declares `CAMERA` (then Android requires it
 *   and it is asked first; a refusal is `permissionDenied`). `front` is passed as the camera
 *   apps' front-camera extras (a hint);
 * - library: the system Photo Picker (no permission).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoCaptureHost(effects: ComposeHostEffects) {
    val pending = effects.photoRequest.value
    val context = LocalContext.current
    var sheet by remember(pending) { mutableStateOf(false) }
    // The camera writes here; the file is deleted once the photo is stored.
    val output = remember(pending) { if (pending == null) null else cameraOutput(context) }

    fun finish(pick: PhotoPick) {
        val p = effects.photoRequest.value ?: return
        effects.photoRequest.value = null
        if (pick !is PhotoPick.Image) output?.first?.delete()
        p.onResult(pick)
    }

    val takePicture = rememberLauncherForActivityResult(TakePictureHint()) { ok ->
        val out = output
        if (ok && out != null && out.first.length() > 0) finish(PhotoPick.Image(out.second, "camera") { out.first.delete() })
        else finish(PhotoPick.Cancel)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        finish(if (uri != null) PhotoPick.Image(uri, "library") else PhotoPick.Cancel)
    }

    fun launchCamera() {
        val out = output ?: return finish(PhotoPick.Error("failed", "no output file"))
        TakePictureHint.front = pending?.request?.front == true
        try {
            takePicture.launch(out.second)
        } catch (e: ActivityNotFoundException) {
            finish(PhotoPick.Error("unavailable", "no camera app"))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else finish(PhotoPick.Error("permissionDenied", "camera access was denied"))
    }

    fun openCamera() {
        if (!hasCamera(context)) return finish(PhotoPick.Error("unavailable", "this device has no camera"))
        // A host that declares CAMERA must hold it for the system camera too.
        if (declaresCamera(context) && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        } else {
            launchCamera()
        }
    }

    fun openLibrary() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    LaunchedEffect(pending) {
        val request = pending?.request ?: return@LaunchedEffect
        when (request.source) {
            PhotoCaptureRequest.Source.camera -> openCamera()
            PhotoCaptureRequest.Source.library -> openLibrary()
            PhotoCaptureRequest.Source.cameraOrLibrary -> if (hasCamera(context)) sheet = true else openLibrary()
        }
    }

    val request = pending?.request ?: return
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false; finish(PhotoPick.Cancel) }) {
            ChooserRow("photo_camera", request.cameraLabel) { sheet = false; openCamera() }
            ChooserRow("photo_library", request.libraryLabel) { sheet = false; openLibrary() }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun ChooserRow(icon: String, label: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        MaterialSymbols.material(LocalContext.current, icon)?.let { glyph ->
            Text(glyph.text, style = TextStyle(fontFamily = MaterialSymbols.family(filled = false), fontSize = 24.sp, color = color))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** `TakePicture` with the front-camera extras most camera apps read. */
private class TakePictureHint : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (front) {
            putExtra("android.intent.extras.CAMERA_FACING", 1)
            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
    }

    companion object {
        var front: Boolean = false
    }
}

private fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private fun declaresCamera(context: Context): Boolean = runCatching {
    val pm = context.packageManager
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true
}.getOrDefault(false)

/** A new file in `cacheDir/app8-photos/` and its FileProvider uri (`<package>.app8.photos`). */
private fun cameraOutput(context: Context): Pair<File, Uri> {
    val dir = File(context.cacheDir, "app8-photos").apply { mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    return file to FileProvider.getUriForFile(context, "${context.packageName}.app8.photos", file)
}
