package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

class ChatPanel(context: Context, private val send: Consumer<String>, private val stop: Runnable,
                private val clear: Runnable) : AbstractComposeView(context) {
    private data class Controls(val send: Boolean=false,val stop: Boolean=false,val clear: Boolean=true)
    private var transcript by mutableStateOf("No conversation yet.\n")
    private var controls by mutableStateOf(Controls())
    fun render(text: String) { transcript=text }
    fun updateControls(canSend: Boolean,canStop: Boolean,canClear: Boolean) { controls=Controls(canSend,canStop,canClear) }
    private fun tag(name: String) = "${context.packageName}:id/$name"

    @Composable override fun Content() {
        MaterialTheme {
            var input by remember { mutableStateOf("") }
            val scroll=rememberScrollState()
            val focus=LocalFocusManager.current
            val keyboard=LocalSoftwareKeyboardController.current
            LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }
            val annotated=remember(transcript) {
                buildAnnotatedString {
                    append(transcript)
                    Regex("https?://[^\\s]+").findAll(transcript).forEach {
                        addLink(LinkAnnotation.Url(it.value),it.range.first,it.range.last+1)
                    }
                }
            }
            Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId=true }) {
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    Text(annotated,modifier=Modifier.fillMaxWidth().padding(8.dp).testTag(tag("chat_transcript")))
                }
                OutlinedTextField(value=input,onValueChange={input=it},placeholder={Text("Write a message...")},
                    minLines=1,maxLines=4,modifier=Modifier.fillMaxWidth().testTag(tag("chat_input")))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={clear.run()},enabled=controls.clear) { Text("Clear") }
                    TextButton(onClick={stop.run()},enabled=controls.stop,modifier=Modifier.testTag(tag("chat_stop"))) { Text("Stop") }
                    Button(onClick={val text=input;input="";focus.clearFocus();keyboard?.hide();send.accept(text)},
                        enabled=controls.send,modifier=Modifier.testTag(tag("chat_send"))) { Text("Send") }
                }
            }
        }
    }
}
