package com.notcherry.lfm3pocket

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/**
 * Home shell. Native inference, import, media and chat stay in [MainActivity].
 */
class ComposeHostActivity : ComponentActivity() {
    private var attachedInterfaceScale = InterfaceSize.DEFAULT
    private var preloadSettingsStore: InferenceSettingsStore? = null

    override fun attachBaseContext(newBase: Context) {
        attachedInterfaceScale = InterfaceSize.read(newBase)
        super.attachBaseContext(InterfaceSize.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale)
    }

    override fun onDestroy() {
        preloadSettingsStore?.close()
        preloadSettingsStore = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeExternalAsk(intent)) return
        val runtime = (application as? Lfm3PocketApplication)?.modelRuntime()
        if (runtime != null) {
            val store = InferenceSettingsStore(this)
            preloadSettingsStore = store
            store.load(
                { settings ->
                    runtime.engine().configure(settings)
                    store.close()
                    if (preloadSettingsStore === store) preloadSettingsStore = null
                },
                {
                    store.close()
                    if (preloadSettingsStore === store) preloadSettingsStore = null
                },
            )
            runtime.preloadSavedModel()
        }
        window.statusBarColor = StillwaterUi.SURFACE
        window.navigationBarColor = StillwaterUi.SURFACE
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        setContent {
            PocketTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketNavigation(
                        onOpenChat = {
                            // Resume the live session. A fresh id would wipe a reply that is
                            // still generating after the user stepped back to Home.
                            startActivity(Intent(this@ComposeHostActivity, MainActivity::class.java))
                        },
                        onNewChat = {
                            startActivity(
                                Intent(this@ComposeHostActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_REQUEST_NEW_CHAT, true),
                            )
                        },
                        onOpenHistory = {
                            startActivity(Intent(this@ComposeHostActivity, HistoryActivity::class.java))
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(this@ComposeHostActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeExternalAsk(intent)
    }

    /**
     * Share targets and typed ASK intents open chat with a draft. They never
     * authorize a tool; only the user can approve from the phone UI.
     */
    private fun routeExternalAsk(intent: Intent?): Boolean {
        if (intent == null) return false
        val draft = when {
            Intent.ACTION_SEND == intent.action && intent.type?.startsWith("text/") == true ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            "com.notcherry.lfm3pocket.action.ASK" == intent.action ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra("prompt")
            else -> null
        }?.trim().orEmpty()
        val sharedImage = when {
            Intent.ACTION_SEND == intent.action && intent.type?.startsWith("image/") == true ->
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        val sharedDocs = ArrayList<android.net.Uri>()
        if (Intent.ACTION_SEND == intent.action && sharedImage == null) {
            val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (stream != null) sharedDocs.add(stream)
        } else if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
            val streams = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (streams != null) {
                for (u in streams) {
                    if (u != null && sharedDocs.size < DocumentAttachmentReader.MAX_FILES) {
                        sharedDocs.add(u)
                    }
                }
            }
        }
        if (draft.isEmpty() && sharedImage == null && sharedDocs.isEmpty()) return false
        val chat = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (draft.isNotEmpty()) chat.putExtra(MainActivity.EXTRA_DRAFT_TEXT, draft)
        if (sharedImage != null) {
            chat.data = sharedImage
            chat.clipData = android.content.ClipData.newUri(contentResolver, "shared-image", sharedImage)
            chat.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chat.putExtra(MainActivity.EXTRA_SHARED_IMAGE, sharedImage.toString())
        }
        if (sharedDocs.isNotEmpty()) {
            val uriStrings = ArrayList<String>(sharedDocs.size)
            var clip: android.content.ClipData? = chat.clipData
            for (u in sharedDocs) {
                uriStrings.add(u.toString())
                if (clip == null) {
                    clip = android.content.ClipData.newUri(contentResolver, "shared-doc", u)
                } else {
                    clip.addItem(android.content.ClipData.Item(u))
                }
            }
            chat.clipData = clip
            chat.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chat.putStringArrayListExtra(MainActivity.EXTRA_SHARED_DOCUMENTS, uriStrings)
        }
        startActivity(chat)
        finish()
        return true
    }
}

@Composable
private fun PocketNavigation(
    onOpenChat: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = rememberNavController()
    NavHost(navController = controller, startDestination = "home") {
        composable("home") {
            StillwaterHome(
                onOpenChat = onOpenChat,
                onNewChat = onNewChat,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
                onAbout = { controller.navigate("about") },
            )
        }
        composable("about") {
            StillwaterAbout(onBack = { controller.popBackStack() })
        }
    }
}

@Composable
private fun StillwaterAbout(onBack: () -> Unit) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    val context = LocalContext.current
    val versionName = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: ReleaseVersion.untagged().versionName
        } catch (_: Exception) {
            ReleaseVersion.untagged().versionName
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(atlas.surface)
            .systemBarsPadding()
            .semantics { testTagsAsResourceId = true }
            .padding(horizontal = space.space6, vertical = space.space4),
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = space.space2),
            modifier = Modifier.minimumInteractiveComponentSize(),
            colors = StillwaterControls.textButtonColors(),
        ) {
            Text(stringResource(R.string.back_to_home), style = type.body)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(space.space4))
            Text(stringResource(R.string.app_name), style = type.display, color = atlas.ink)
            Spacer(Modifier.height(space.space2))
            Text(
                stringResource(R.string.about_version_format, versionName),
                style = type.mono,
                color = atlas.accent,
            )
            Spacer(Modifier.height(space.space5))
            Text(
                stringResource(R.string.about_lead),
                style = type.body,
                color = atlas.ink,
            )
            Spacer(Modifier.height(space.space6))
            HorizontalDivider(color = atlas.border, thickness = 1.dp)
            Spacer(Modifier.height(space.space5))
            Text(
                stringResource(R.string.about_section_privacy_title),
                style = type.label,
                color = atlas.inkMuted,
            )
            Spacer(Modifier.height(space.space2))
            Text(
                stringResource(R.string.about_section_privacy_body),
                style = type.body,
                color = atlas.inkSoft,
            )
            Spacer(Modifier.height(space.space5))
            HorizontalDivider(color = atlas.border, thickness = 1.dp)
            Spacer(Modifier.height(space.space5))
            Text(
                stringResource(R.string.about_section_actions_title),
                style = type.label,
                color = atlas.inkMuted,
            )
            Spacer(Modifier.height(space.space2))
            Text(
                stringResource(R.string.about_section_actions_body),
                style = type.body,
                color = atlas.inkSoft,
            )
            Spacer(Modifier.height(space.space6))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StillwaterHome(
    onOpenChat: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var modelName by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner, context) {
        fun refreshModel() {
            val prefs = context.getSharedPreferences(RuntimePreferences.FILE, Context.MODE_PRIVATE)
            val storedName = prefs.getString("model_name", "").orEmpty()
            val storedPath = prefs.getString("model", "").orEmpty()
            modelName = when {
                storedName.isNotBlank() -> storedName
                storedPath.isNotBlank() -> java.io.File(storedPath).name
                else -> ""
            }
        }
        refreshModel()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshModel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasModel = modelName.isNotBlank()
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(atlas.surfaceGlow, atlas.surface),
                    radius = 1400f,
                ),
            )
            .systemBarsPadding()
            .semantics { testTagsAsResourceId = true }
            .padding(horizontal = space.space6, vertical = space.space5),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(if (hasModel) atlas.ok else atlas.warn, CircleShape),
                    )
                    Spacer(Modifier.width(space.space2))
                    Text(
                        stringResource(R.string.home_on_device),
                        style = type.label,
                        color = atlas.accent,
                    )
                }
                Spacer(Modifier.height(space.space6))
                Text(stringResource(R.string.app_name), style = type.display, color = atlas.ink)
                Spacer(Modifier.height(space.space4))
                Text(
                    stringResource(R.string.home_tagline),
                    style = type.body,
                    color = atlas.inkSoft,
                    modifier = Modifier.fillMaxWidth(0.92f),
                )
            }

            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(space.space8))
                // Model readiness strip: guides first-run setup when empty, confirms active GGUF when ready.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                        .border(1.dp, if (hasModel) atlas.border else atlas.borderStrong, MaterialTheme.shapes.medium)
                        .clickable { onOpenSettings() }
                        .padding(horizontal = space.space4, vertical = space.space3)
                        .testTag("${context.packageName}:id/home_import_model"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (hasModel) modelName else stringResource(R.string.home_no_model_title),
                            style = if (hasModel) type.mono else type.body,
                            color = atlas.ink,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasModel) {
                                stringResource(R.string.home_model_ready_suffix)
                            } else {
                                stringResource(R.string.home_no_model_hint)
                            },
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                        )
                    }
                    if (!hasModel) {
                        Spacer(Modifier.width(space.space3))
                        Text(
                            text = stringResource(R.string.home_setup_model),
                            style = type.label,
                            color = atlas.accent,
                        )
                    }
                }

                Spacer(Modifier.height(space.space4))
                Button(
                    onClick = onOpenChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 50.dp)
                        .testTag("${context.packageName}:id/open_chat"),
                    shape = MaterialTheme.shapes.medium,
                    colors = StillwaterControls.buttonColors(),
                ) {
                    Text(stringResource(R.string.open_chat), style = type.body.copy(color = atlas.candleInk))
                }
                Spacer(Modifier.height(space.space2))
                OutlinedButton(
                    onClick = onNewChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 48.dp)
                        .testTag("${context.packageName}:id/home_new_chat"),
                    shape = MaterialTheme.shapes.medium,
                    border = androidx.compose.foundation.BorderStroke(1.dp, atlas.borderStrong),
                    colors = StillwaterControls.outlinedColors(),
                ) {
                    Text(stringResource(R.string.new_chat), style = type.body)
                }
                Spacer(Modifier.height(space.space3))
                HorizontalDivider(color = atlas.border, thickness = 1.dp)
                Spacer(Modifier.height(space.space1))
                // FlowRow so Polish "O trybie offline" wraps cleanly at 2.0x font scale.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("${context.packageName}:id/history_open"),
                        contentPadding = PaddingValues(horizontal = space.space2, vertical = space.space1),
                        colors = StillwaterControls.textButtonColors(),
                    ) {
                        Text(stringResource(R.string.history), style = type.bodySmall, color = atlas.inkSoft)
                    }
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("${context.packageName}:id/home_settings"),
                        contentPadding = PaddingValues(horizontal = space.space2, vertical = space.space1),
                        colors = StillwaterControls.textButtonColors(),
                    ) {
                        Text(stringResource(R.string.settings_title), style = type.bodySmall, color = atlas.inkSoft)
                    }
                    TextButton(
                        onClick = onAbout,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        contentPadding = PaddingValues(horizontal = space.space2, vertical = space.space1),
                        colors = StillwaterControls.textButtonColors(),
                    ) {
                        Text(stringResource(R.string.about_offline_mode), style = type.bodySmall, color = atlas.inkSoft)
                    }
                }
            }
        }
    }
}
