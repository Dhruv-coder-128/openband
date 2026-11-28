package com.openband.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

/** The user's profile photo, stored inside the app (nothing leaves the phone). */
object ProfilePhoto {
    /** Bumped whenever the photo changes so avatars redraw. */
    val version = MutableStateFlow(0)

    private fun file(ctx: Context) = File(ctx.filesDir, "profile.jpg")

    fun exists(ctx: Context) = file(ctx).exists()

    /** Reads the picked image, centre-crops it to a square, shrinks it to 512 px and stores it. */
    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's rotation (EXIF) for us
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val big = maxOf(info.size.width, info.size.height)
                if (big > 1600) d.setTargetSampleSize(big / 1000)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
        }
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, 512, 512, true)
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        version.value += 1
        true
    }.getOrDefault(false)

    fun remove(ctx: Context) { file(ctx).delete(); version.value += 1 }

    /** The stored photo file, for backups. */
    fun bytes(ctx: Context): ByteArray? = runCatching { file(ctx).takeIf { it.exists() }?.readBytes() }.getOrNull()

    /** Puts a photo from a backup in place, if it really is a picture. */
    fun restore(ctx: Context, data: ByteArray) {
        if (BitmapFactory.decodeByteArray(data, 0, data.size) == null) return
        file(ctx).writeBytes(data)
        version.value += 1
    }

    fun load(ctx: Context): Bitmap? = runCatching { file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
}

/** Round avatar: the photo if there is one, otherwise the first letter of the name. */
@Composable
fun ProfileAvatar(size: Dp, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val ver by ProfilePhoto.version.collectAsState()
    val bmp = remember(ver) { ProfilePhoto.load(ctx)?.asImageBitmap() }
    Box(modifier.size(size).clip(CircleShape).background(look.panelHi), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, "Profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(
            (look.prefs.displayName.firstOrNull() ?: '+').uppercase(), fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp, color = look.text,
        )
    }
}

@Composable
fun ProfileScreen(modifier: Modifier) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val ver by ProfilePhoto.version.collectAsState()
    val hasPhoto = remember(ver) { ProfilePhoto.exists(ctx) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { ProfilePhoto.save(ctx, uri) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenTitle(look.names.profile, "You, your photo and your body numbers")

        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(112.dp, Modifier.clickable { picker.launch("image/*") })
            Text(
                p.displayName.ifEmpty { "Add your name below" }, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                color = look.text, modifier = Modifier.padding(top = 10.dp),
            )
            Text("${p.age} yrs · ${p.heightCm} cm · ${p.weightKg} kg · max HR ${p.maxHr}", style = MaterialTheme.typography.bodySmall, color = look.dim)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaceButton(if (hasPhoto) "Change photo" else "Add photo", primary = true) { picker.launch("image/*") }
                if (hasPhoto) RaceButton("Remove", primary = false) { ProfilePhoto.remove(ctx) }
            }
            Text("The photo stays on this phone.", style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 6.dp))
        }

        ProfileFields()
        Spacer(Modifier.height(24.dp))
    }
}
