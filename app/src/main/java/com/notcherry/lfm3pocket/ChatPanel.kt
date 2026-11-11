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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

class ChatPanel(context: Context, private val send: Consumer<String>, private val stop: Runnable,
                private val clear: Runnable) : AbstractComposeView(context) {
    private data class Controls(val send: Boolean=false,val stop: Boolean=false,val clear: Boolean=true)
    private data class ToolCard(val details: String,val risk: String,val status: String,val result: String,
                                val pending: Boolean,val approve: Runnable?,val reject: Runnable?)
    private var transcript by mutableStateOf("No conversation yet.\n")
    private var liveStatus by mutableStateOf("")
    private var toolCard by mutableStateOf<ToolCard?>(null)
    private var controls by mutableStateOf(Controls())
    fun render(text: String,status: String) { transcript=text;liveStatus=status }
    fun renderToolCard(details: String,risk: String,status: String,result: String,pending: Boolean,
                       approve: Runnable?,reject: Runnable?) {
        toolCard=ToolCard(details,risk,status,result,pending,approve,reject)
    }
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
                toolCard?.let { card ->
                    Card(Modifier.fillMaxWidth().padding(horizontal=8.dp, vertical=6.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Android action",style=MaterialTheme.typography.titleMedium)
                            Text(card.details,modifier=Modifier.testTag(tag("tool_details")))
                            Text(card.risk,modifier=Modifier.testTag(tag("tool_risk")))
                            Text(card.status,modifier=Modifier.testTag(tag("tool_status")))
                            if(card.result.isNotBlank())Text(card.result,modifier=Modifier.testTag(tag("tool_result")))
                            if(card.pending)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={card.reject?.run()},modifier=Modifier.testTag(tag("tool_reject"))) { Text("Reject") }
                                Button(onClick={card.approve?.run()},modifier=Modifier.testTag(tag("tool_approve"))) { Text("Approve") }
                            }
                        }
                    }
                }
                if(liveStatus.isNotBlank()) {
                    val active=liveStatus.startsWith("Searching") || liveStatus.startsWith("Generating")
                            || liveStatus.startsWith("Stopping")
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=8.dp)
                            .semantics {
                                liveRegion=LiveRegionMode.Polite
                            },
                        verticalAlignment=Alignment.CenterVertically) {
                        if(active) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        Text(liveStatus,style=MaterialTheme.typography.bodyMedium,
                            modifier=Modifier.padding(start=8.dp).testTag(tag("chat_live_status")))
                    }
                }
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
