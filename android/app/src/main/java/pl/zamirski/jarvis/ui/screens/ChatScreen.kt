package pl.zamirski.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.core.Sources
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.VoiceMode
import pl.zamirski.jarvis.ui.components.JarvisCore
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.theme.JColors

@Composable
fun ChatScreen(vm: JarvisViewModel, actions: AppActions, onOpenHistory: () -> Unit, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    fun submit() {
        val text = input.trim()
        if (text.isNotEmpty()) {
            vm.send(text)
            input = ""
        }
    }

    ScreenFrame(
        title = "Rozmowa",
        onBack = {
            vm.cancelVoice()
            onBack()
        },
        actions = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "Historia rozmów", tint = JColors.Accent)
            }
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Filled.AddComment, contentDescription = "Nowa rozmowa (obecna trafi do historii)", tint = JColors.Accent)
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            JarvisCore(Modifier.fillMaxWidth().height(150.dp), energy = vm.soundLevel, active = vm.mode != VoiceMode.IDLE)
        }
        Text(
            when {
                vm.liveText.isNotBlank() -> vm.liveText
                vm.mode == VoiceMode.IDLE -> "Naciśnij mikrofon, aby mówić, albo napisz poniżej."
                else -> ""
            },
            color = if (vm.mode == VoiceMode.LISTENING) JColors.AccentBright else JColors.TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.handsFreeActive) {
            Text(
                "TRYB ROZMOWY · powiedz „to wszystko”, aby zakończyć",
                color = JColors.Orange,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { MessageBubble(it) }
        }

        Row(
            Modifier.fillMaxWidth().imePadding().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.takePhoto) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Zdjęcie dla AI", tint = JColors.Accent)
            }
            IconButton(onClick = actions.pickFile) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Plik dla AI", tint = JColors.Accent)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Wpisz polecenie…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JColors.Accent,
                    unfocusedBorderColor = JColors.CardBorder,
                ),
                trailingIcon = {
                    IconButton(onClick = { submit() }) {
                        Icon(Icons.Filled.Send, contentDescription = "Wyślij", tint = JColors.Accent)
                    }
                },
            )
            Spacer(Modifier.size(8.dp))
            MicButton(vm.mode, size = 56) { actions.startVoice() }
        }
    }
}

/** Dymek wiadomości — używany w rozmowie i w historii. */
@Composable
fun MessageBubble(m: ChatMessage) {
    val (align, bg, border, label, labelColor) = when (m.kind) {
        Kind.USER -> Style(Alignment.CenterEnd, JColors.CardSolid, JColors.CardBorder, "TY", JColors.TextDim)
        Kind.ASSISTANT -> Style(Alignment.CenterStart, JColors.Card, JColors.Accent.copy(alpha = 0.6f), "JARVIS", JColors.Accent)
        Kind.NOTICE -> Style(Alignment.Center, JColors.Card, JColors.Warning.copy(alpha = 0.5f), "SYSTEM", JColors.Warning)
    }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(label, color = labelColor, fontSize = 11.sp, letterSpacing = 1.5.sp)
            if (m.attachmentLabel != null) {
                Text(m.attachmentLabel, color = JColors.Orange, fontSize = 13.sp)
            }
            val (body, sources) = if (m.kind == Kind.ASSISTANT) Sources.split(m.text) else m.text to emptyList()
            Text(
                body,
                color = JColors.Text,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = if (m.attachmentLabel != null && m.kind == Kind.USER) 4 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (sources.isNotEmpty()) {
                val uri = LocalUriHandler.current
                Text("ŹRÓDŁA", color = JColors.TextDim, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp))
                sources.forEach { s ->
                    Text(
                        "[${s.n}] ${s.title}" + (s.date?.let { " · $it" } ?: ""),
                        color = JColors.Accent,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { runCatching { uri.openUri(s.url) } },
                    )
                    val host = runCatching { java.net.URI(s.url).host?.removePrefix("www.") }.getOrNull()
                    val caption = listOfNotNull(host, s.snippet.takeIf { it.isNotBlank() }).joinToString(" — ")
                    if (caption.isNotBlank()) {
                        Text(caption, color = JColors.TextDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private data class Style(
    val align: Alignment,
    val bg: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
    val label: String,
    val labelColor: androidx.compose.ui.graphics.Color,
)
