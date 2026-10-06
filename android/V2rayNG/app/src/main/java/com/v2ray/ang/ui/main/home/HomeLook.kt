package com.v2ray.ang.ui.main.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SubLookStore
import com.v2ray.ang.net.SubLook
import com.v2ray.ang.ui.compose.HomeTokens
import kotlinx.coroutines.launch

/**
 * Appearance of one subscription: picture from the gallery or from Telegram, an emoji, a card colour, a background picture.
 * Everything stays on this phone.
 */
@Composable
internal fun AppearanceSheet(group: GroupMapItem, now: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val look = SubLookStore.get(group.id)
    var tgInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            if (!SubLookStore.setImage(context, group.id, uri, background = false)) context.toastError(R.string.look_failed)
            busy = false
        }
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            if (!SubLookStore.setImage(context, group.id, uri, background = true)) context.toastError(R.string.look_failed)
            busy = false
        }
    }

    HomeSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(cardBrush(group, now)).background(HomeTokens.cardShade)) {
                SubAvatar(group, 60.dp, 20.dp, 26)
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.look_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }

        Label(stringResource(R.string.look_avatar))
        SheetRow(R.drawable.ic_image_24dp, stringResource(R.string.look_photo)) { if (!busy) pickAvatar.launch("image/*") }

        // Telegram: the picture of a public bot or channel, taken once
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(homeSurface()).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Box(Modifier.weight(1f)) {
                if (tgInput.isEmpty()) Text(stringResource(R.string.look_telegram_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                BasicTextField(
                    value = tgInput, onValueChange = { tgInput = it.take(120) }, singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(homeAccent()), modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                stringResource(R.string.look_telegram_take),
                color = homeAccent(), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy && tgInput.isNotBlank()) {
                    scope.launch {
                        busy = true
                        if (SubLookStore.fetchTelegram(context, group.id, tgInput)) {
                            tgInput = ""
                            context.toastSuccess(R.string.look_done)
                        } else context.toastError(R.string.look_telegram_failed)
                        busy = false
                    }
                }.padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
        Text(stringResource(R.string.look_telegram_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))

        Label(stringResource(R.string.look_emoji))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            items(SubLook.EMOJIS) { e ->
                val on = look.avatar == SubLook.Avatar.EMOJI && look.emoji == e
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(homeSurface(on))
                        .border(1.5.dp, if (on) homeAccent() else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp))
                        .semantics { role = Role.Button; contentDescription = e }
                        .clickable { SubLookStore.set(group.id, look.copy(avatar = SubLook.Avatar.EMOJI, emoji = e, rev = System.currentTimeMillis())) },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 24.sp) }
            }
        }

        Label(stringResource(R.string.look_color))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            item {
                val on = look.gradient < 0
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(homeSurface(on))
                        .border(1.5.dp, if (on) homeAccent() else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(22.dp))
                        .clickable { SubLookStore.set(group.id, look.copy(gradient = -1)) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text(stringResource(R.string.look_auto), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
            }
            itemsIndexed(HomeTokens.cardGradients) { i, colors ->
                val on = look.gradient == i
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(colors))
                        .border(2.dp, if (on) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .semantics { role = Role.RadioButton; contentDescription = "${i + 1}" }
                        .clickable { SubLookStore.set(group.id, look.copy(gradient = i)) }
                )
            }
        }

        Label(stringResource(R.string.look_background))
        SheetRow(R.drawable.ic_image_24dp, stringResource(if (look.background) R.string.look_bg_change else R.string.look_bg_pick)) { if (!busy) pickBackground.launch("image/*") }
        if (look.background) {
            SheetRow(R.drawable.ic_delete_24dp, stringResource(R.string.look_bg_remove), danger = true) {
                SubLookStore.backgroundFile(context, group.id).delete()
                SubLookStore.set(group.id, look.copy(background = false, rev = System.currentTimeMillis()))
            }
        }
        if (!look.isDefault) {
            Spacer(Modifier.height(6.dp))
            SheetRow(R.drawable.ic_restore_24dp, stringResource(R.string.look_reset), danger = true) { SubLookStore.reset(context, group.id) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp))
}
