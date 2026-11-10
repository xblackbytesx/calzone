package com.xblackbytesx.calzone.signin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.account.AndroidAccounts
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.account.Signed
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.core.CoreError
import com.xblackbytesx.calzone.net.OkHttpTransport
import com.xblackbytesx.calzone.push.Push
import com.xblackbytesx.calzone.qr.QrScanner
import com.xblackbytesx.calzone.sync.SyncRunner
import com.xblackbytesx.calzone.sync.SyncWorker
import com.xblackbytesx.calzone.ui.Cz
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step { CHOOSE, SCAN, PASTE, WORKING }

/** Why signing in failed, for the screen to word. */
enum class SignInProblem { NOT_OURS, USED_UP, OFFLINE, OTHER }

data class SignInUi(val step: Step = Step.CHOOSE, val problem: SignInProblem? = null, val done: Boolean = false)

/** This phone's name on the Devices page. */
fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(60)

@HiltViewModel
class SignInViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: Core,
    private val session: Session,
    private val runner: SyncRunner,
) : ViewModel() {
    private val _ui = MutableStateFlow(SignInUi())
    val ui: StateFlow<SignInUi> = _ui

    fun step(s: Step) = _ui.update { it.copy(step = s, problem = null) }

    /** A scanned, pasted or opened sign-in address. */
    fun code(text: String) {
        val link = Core.link(text) ?: return _ui.update { it.copy(problem = SignInProblem.NOT_OURS) }
        _ui.update { it.copy(step = Step.WORKING, problem = null) }
        viewModelScope.launch {
            try {
                val signed = withContext(Dispatchers.IO) { core.signIn(OkHttpTransport(link.server, null), link, deviceName()) }
                session.save(Signed(link.server, signed.token))
                runner.forget()
                withContext(Dispatchers.IO) { AndroidAccounts.ensure(context, AndroidAccounts.nameFor(signed.account.name, link.server)) }
                // A phone that had push keeps it: the new token needs the endpoint again.
                if (Push.enabled(context)) Push.reregister(context)
                SyncWorker.now(context)
                _ui.update { it.copy(done = true) }
            } catch (e: CoreError) {
                val p = when {
                    e is CoreError.Offline -> SignInProblem.OFFLINE
                    e.message?.contains("answered 403") == true -> SignInProblem.USED_UP
                    else -> SignInProblem.OTHER
                }
                _ui.update { it.copy(step = Step.CHOOSE, problem = p) }
            }
        }
    }
}

@Composable
fun SignInScreen(link: String?, canGoBack: Boolean, onBack: () -> Unit, onDone: () -> Unit, vm: SignInViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(link) { link?.let(vm::code) }
    LaunchedEffect(ui.done) { if (ui.done) onDone() }
    val context = LocalContext.current
    val cameraOk = { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.step(Step.SCAN) }
    // Android 17: a server on the LAN or a VPN is unreachable without this; public servers work either way.
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val perm = "android.permission.ACCESS_LOCAL_NETWORK"
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) askLocal.launch(perm)
    }
    SignInContent(
        ui = ui,
        canGoBack = canGoBack || ui.step != Step.CHOOSE,
        onBack = { if (ui.step == Step.CHOOSE) onBack() else vm.step(Step.CHOOSE) },
        onScan = { if (cameraOk()) vm.step(Step.SCAN) else askCamera.launch(Manifest.permission.CAMERA) },
        onPaste = { vm.step(Step.PASTE) },
        onCode = vm::code,
    )
}

@Composable
fun problemText(p: SignInProblem): String = when (p) {
    SignInProblem.NOT_OURS -> stringResource(R.string.signin_not_ours)
    SignInProblem.USED_UP -> stringResource(R.string.signin_used_up)
    SignInProblem.OFFLINE -> stringResource(R.string.signin_offline)
    SignInProblem.OTHER -> stringResource(R.string.signin_other)
}

@Composable
fun SignInContent(
    ui: SignInUi,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onCode: (String) -> Unit,
) {
    val c = Cz.colors
    Column(Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp).height(56.dp)) {
            if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(
                stringResource(R.string.signin_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = if (canGoBack) 4.dp else 16.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ui.problem?.let {
                Text(problemText(it), color = c.danger, modifier = Modifier.padding(vertical = 8.dp))
            }
            when (ui.step) {
                Step.CHOOSE -> {
                    Text(stringResource(R.string.signin_intro), color = c.quiet, modifier = Modifier.padding(bottom = 16.dp))
                    Choice(Icons.Rounded.QrCodeScanner, stringResource(R.string.signin_scan), stringResource(R.string.signin_scan_hint), onScan)
                    Choice(Icons.Rounded.ContentPaste, stringResource(R.string.signin_paste), stringResource(R.string.signin_paste_hint), onPaste)
                }
                Step.SCAN -> {
                    Text(stringResource(R.string.signin_scan_hint), color = c.quiet, modifier = Modifier.padding(bottom = 12.dp))
                    var seen by remember { mutableStateOf(false) }
                    QrScanner(
                        onCode = { text ->
                            if (!seen && Core.link(text) != null) {
                                seen = true
                                onCode(text)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    )
                }
                Step.PASTE -> {
                    var text by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text(stringResource(R.string.signin_paste_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (text.trim().startsWith("http://")) {
                        Text(stringResource(R.string.signin_http_warning), color = c.caution, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onCode(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    ) { Text(stringResource(R.string.signin_continue), fontWeight = FontWeight.Bold) }
                }
                Step.WORKING -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val c = Cz.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = c.accent)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(hint, color = c.quiet, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
