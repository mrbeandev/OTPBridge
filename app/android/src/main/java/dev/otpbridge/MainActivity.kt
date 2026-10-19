package dev.otpbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import dev.otpbridge.ui.BridgeTheme
import dev.otpbridge.ui.BridgeIcons
import dev.otpbridge.vault.AccountSummary
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.otpbridge.protocol.PhoneBridge
import dev.otpbridge.totp.Totp
import dev.otpbridge.totp.TotpAccount
import dev.otpbridge.vault.Vault
import dev.otpbridge.vault.ForegroundSession
import dev.otpbridge.totp.CodeTiming
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : FragmentActivity() {
    private lateinit var vault: Vault
    private var bridge: PhoneBridge? = null
    private val networkLock = Mutex()
    private val foregroundJobs = mutableSetOf<Job>()
    private var tab by mutableIntStateOf(0)
    private val session = ForegroundSession()
    private var sessionEnabled by mutableStateOf(false)
    private var maskAllCodes by mutableStateOf(false)
    private var sessionUnlocked by mutableStateOf(false)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private data class VisibleCode(val code: String, val validUntil: Long)
    private var visibleCodes by mutableStateOf<Map<String, VisibleCode>>(emptyMap())
    private var lastSessionStep: String? = null
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var browsers by mutableStateOf<List<JSONObject>>(emptyList())
    private var pending by mutableStateOf<List<PhoneBridge.Pending>>(emptyList())
    private var accounts by mutableStateOf<List<AccountSummary>>(emptyList())
    private var needsMigration by mutableStateOf(false)
    private var deleteAccount by mutableStateOf<AccountSummary?>(null)
    private var accountMenu by mutableStateOf<String?>(null)
    private var addMenu by mutableStateOf(false)
    private var search by mutableStateOf("")
    private var enrollment by mutableStateOf(false)
    private var uriInput by mutableStateOf("")
    private var pairQr by mutableStateOf<String?>(null)
    private var selectedRequest by mutableStateOf<PhoneBridge.Pending?>(null)
    private var selectedAccount by mutableStateOf<String?>(null)
    private var approveBinding by mutableStateOf(false)
    private var scanMode = "account"
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { text -> if (scanMode == "pair") pairQr = text else { uriInput = text; enrollment = true } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Telemetry.init(this)
        Telemetry.track("app.create")
        vault = Vault(this)
        loadCatalog()
        sessionEnabled = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("foregroundSession", false)
        maskAllCodes = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("maskAllCodes", false)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (sessionEnabled && !session.unlocked) unlockSession()
            while (isActive) { now = System.currentTimeMillis(); tickCodes(); delay(250) }
        } }
        runCatching { bridge = PhoneBridge(this); browsers = bridge!!.list() }.onFailure { status = "Device storage unavailable: ${it.message}" }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { refresh(false); while (isActive) { delay(2500); if (tab == 0 || browsers.any { !it.optBoolean("active") && it.optLong("expiresAt") > System.currentTimeMillis() }) refresh(false) } } }
        setContent { BridgeTheme { Screen() } }
    }
    private fun error(message: String) { status = message; busy = false }
    private fun operation(block: suspend () -> Unit) {
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) { busy = true; try { check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)); block() } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "Operation failed" } finally { busy = false } }
        foregroundJobs.add(job); job.invokeOnCompletion { foregroundJobs.remove(job) }; job.start()
    }
    private suspend fun <T> network(block: () -> T): T = withContext(Dispatchers.IO) { networkLock.withLock { ensureActive(); check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "App is not foreground" }; block() } }
    private suspend fun refresh(showStatus: Boolean) {
        val b = bridge ?: return
        val job = currentCoroutineContext()[Job]
        try { val result = network { val errors = b.refresh { job?.isActive == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }; Triple(b.list(), b.pendingRequests(), errors) }; browsers = result.first; if (result.second.size != pending.size) Telemetry.track("ui.pending", "count" to result.second.size); pending = result.second; selectedRequest?.let { reviewed -> if (pending.none { it.key == reviewed.key && it.body.toString() == reviewed.body.toString() }) { selectedRequest = null; selectedAccount = null; approveBinding = false } }; if (result.third.isNotEmpty()) status = result.third.joinToString("\n"); else if (showStatus) status = "Fetched ${pending.size} pending request(s)." } catch (e: CancellationException) { throw e } catch (e: Exception) { Telemetry.track("ui.refresh-failed", "error" to (e.message ?: e.javaClass.simpleName)); status = "Refresh failed: ${e.message}" }
    }
    private fun loadCatalog() {
        lastSessionStep = null
        runCatching { accounts = vault.summaries(); needsMigration = vault.needsCatalogMigration }.onFailure { status = "Account list unavailable. Unlock once to repair it."; needsMigration = true }
    }
    private fun migrateCatalog() = vault.unlock(this, "Update your account list once", ::error) { key -> vault.migrateCatalog(key); loadCatalog(); status = "Account names are now visible. Codes stay locked." }
    private fun review(request: PhoneBridge.Pending) {
        if (request.body.getLong("expiresAt") <= System.currentTimeMillis()) { status = "This request expired. Start a new one in the browser."; return }
        selectedRequest = request; approveBinding = true
        val ref = request.body.optString("accountRef", "")
        val choices = accounts.filter { ref.isEmpty() || it.id == ref }
        selectedAccount = choices.singleOrNull()?.id
    }
    private fun authorize(reason: String, action: (ByteArray) -> Unit) {
        if (sessionEnabled && session.unlocked) { try { session.use(action) } catch (e: Exception) { error(e.message ?: "Session operation failed") } }
        else if (sessionEnabled) {
            vault.unlock(this, "Unlock this visit", ::error) { key -> session.unlock(key); sessionUnlocked = true; lastSessionStep = null; action(key); tickCodes() }
        } else vault.unlock(this, reason, ::error, action)
    }
    private fun unlockSession() {
        vault.unlock(this, "Unlock codes until you leave the app", ::error) { key ->
            session.unlock(key); sessionUnlocked = true
            if (needsMigration) { vault.migrateCatalog(key); loadCatalog() }
            lastSessionStep = null; tickCodes()
        }
    }
    private fun setSessionMode(enabled: Boolean) {
        sessionEnabled = enabled
        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("foregroundSession", enabled).apply()
        session.lock(); sessionUnlocked = false; visibleCodes = emptyMap(); lastSessionStep = null
        if (enabled) unlockSession()
    }
    private fun tickCodes() {
        if (sessionEnabled && session.unlocked) {
            val step = accounts.joinToString("|") { "${it.id}:${now / (it.period * 1000L)}" }
            if (step != lastSessionStep) {
                try { visibleCodes = session.use { key -> vault.accounts(key).associate { r -> r.id to VisibleCode(Totp.generate(r.account, now / 1000), CodeTiming.validUntil(now, r.account.period)) } }; lastSessionStep = step }
                catch (e: Exception) { session.lock(); sessionUnlocked = false; visibleCodes = emptyMap(); error(e.message ?: "Session locked") }
            }
        } else if (visibleCodes.values.any { it.validUntil <= now }) visibleCodes = visibleCodes.filterValues { it.validUntil > now }
    }
    private fun showCode(account: AccountSummary) = authorize("Show ${account.issuer.ifBlank { account.label }} code") { key ->
        val a = vault.accounts(key).firstOrNull { it.id == account.id }?.account ?: throw IllegalStateException("Account no longer exists")
        val time = System.currentTimeMillis()
        visibleCodes = visibleCodes + (account.id to VisibleCode(Totp.generate(a, time / 1000), CodeTiming.validUntil(time, a.period)))
    }
    private fun enroll() {
        try {
            val account = Totp.parse(uriInput)
            vault.unlock(this, "Add ${account.label}", ::error) { key -> vault.add(key, account); loadCatalog(); enrollment = false; uriInput = ""; status = "Account added. Codes stay protected by your fingerprint." }
        } catch (e: Exception) { error(e.message ?: "Invalid TOTP setup") }
    }
    private fun approve() {
        val request = selectedRequest ?: return; val accountId = selectedAccount ?: return
        if (!approveBinding || busy) return
        authorize("Approve ${request.body.getString("origin")}") { key ->
            val account = vault.accounts(key).firstOrNull { it.id == accountId }?.account ?: throw IllegalStateException("Account unavailable")
            val now = System.currentTimeMillis(); val remaining = account.period * 1000 - now % (account.period * 1000)
            val generation = if (remaining < 5000) now + remaining + 50 else now
            val validFrom = generation / (account.period * 1000) * (account.period * 1000)
            val validUntil = validFrom + account.period * 1000
            if (generation >= request.body.getLong("expiresAt")) throw IllegalStateException("Request will expire before the next code; request again")
            // Copy only the authorized data key across the short boundary wait,
            // then decrypt/generate at the actual time. The vault callback wipes
            // its original key immediately; cancellation wipes this copy too.
            val authorizedKey = key.copyOf()
            selectedRequest = null
            operation {
                try {
                    if (generation > now) delay(generation - now)
                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "Approval cancelled because the app is no longer foreground" }
                    val current = vault.accounts(authorizedKey).firstOrNull { it.id == accountId }?.account ?: throw IllegalStateException("Account unavailable")
                    val otp = Totp.generate(current, System.currentTimeMillis() / 1000)
                    network { bridge!!.approve(request, accountId, otp, validFrom, validUntil) }
                    Telemetry.track("approve.sent", "req" to Telemetry.short(request.body.optString("requestId")), "remainingMs" to (validUntil - System.currentTimeMillis()))
                } finally { authorizedKey.fill(0) }
                status = "Approved code sent. The browser validates the original field before filling."; refresh(false)
            }
        }
    }
    @Composable private fun Screen() {
        pairQr?.let { qr ->
            PairBrowserScreen(qr)
            return
        }
        selectedRequest?.let { request ->
            ReviewScreen(request)
            return
        }
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(0, 2).forEach { i ->
                        val label = listOf("Home", "Requests", "Browsers")[i]
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; status = "" },
                            icon = { Icon(listOf(BridgeIcons.Accounts, BridgeIcons.Requests, BridgeIcons.Browser)[i], contentDescription = null) },
                            label = { Text(if (i == 0 && pending.isNotEmpty()) "Home · ${pending.size}" else label) })
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0) FloatingActionButton(onClick = { addMenu = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(BridgeIcons.Add, contentDescription = "Add account") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().animateContentSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text(if (tab == 0) "OTPBridge" else "Browsers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (tab == 0) "Accounts & approvals" else "Only devices you trust.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (tab == 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconToggleButton(checked = !maskAllCodes, onCheckedChange = { visible -> maskAllCodes = !visible; getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("maskAllCodes", maskAllCodes).apply() }) { Icon(if(maskAllCodes) BridgeIcons.EyeOff else BridgeIcons.Eye, contentDescription = if(maskAllCodes) "Show account codes" else "Hide all account codes") }
                        TextButton(enabled = !busy, onClick = { operation { refresh(true) } }) { Text("Refresh") }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Unlock once per visit", fontWeight = FontWeight.Medium); Text(if (!sessionEnabled) "Optional · fingerprint for each action" else if (sessionUnlocked) "Unlocked · locks when you leave" else "Locked · fingerprint to reveal and send", style = MaterialTheme.typography.bodySmall) }
                            Switch(checked = sessionEnabled, onCheckedChange = ::setSessionMode)
                        }
                        if (sessionEnabled && !sessionUnlocked) TextButton(onClick = ::unlockSession) { Text("Unlock session") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) { Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                when (tab) {
                    0 -> {
                        AnimatedVisibility(visible = pending.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("REQUESTS · ${pending.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                pending.forEach { request -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(request.body.getString("browserName"), style = MaterialTheme.typography.labelMedium); Text("${CodeTiming.secondsRemaining(now, request.body.getLong("expiresAt"))}s left", style = MaterialTheme.typography.labelMedium) }
                                        Text(request.body.getString("origin").removePrefix("https://"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(enabled = !busy, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), onClick = { review(request) }) { Text("Review & send") }; TextButton(enabled = !busy, onClick = { operation { network { bridge!!.deny(request) }; status = "OTP request denied. No code was shared."; refresh(false) } }) { Text("Deny") } }
                                    }
                                } }
                            }
                        }
                        Text("ACCOUNTS · ${accounts.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (needsMigration) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Update your account list", fontWeight = FontWeight.SemiBold); Text("Unlock once to show names without unlocking codes. Your existing secrets stay unchanged.", style = MaterialTheme.typography.bodySmall); Button(onClick = ::migrateCatalog) { Text("Update with fingerprint") } } }
                        if (accounts.isNotEmpty()) OutlinedTextField(search, { search = it }, placeholder = { Text("Search accounts") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
                        if (accounts.isEmpty() && !needsMigration) EmptyState("Your codes, safely kept", "Add your first account with a setup QR or secret key. Names stay visible; codes stay locked.")
                        accounts.filter { search.isBlank() || "${it.issuer} ${it.label}".contains(search, true) }.forEach { a ->
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text((a.issuer.ifBlank { a.label }).take(1).uppercase(), fontWeight = FontWeight.Bold) } }
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(a.issuer.ifBlank { a.label }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (a.issuer.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        Box { IconButton(onClick = { accountMenu = a.id }) { Icon(BridgeIcons.More, contentDescription = "Account options for ${a.label}") }; DropdownMenu(expanded = accountMenu == a.id, onDismissRequest = { accountMenu = null }) { DropdownMenuItem(text = { Text("Delete account") }, onClick = { accountMenu = null; deleteAccount = a }) } }
                                    }
                                    val shown = visibleCodes[a.id]?.takeIf { it.validUntil > now && !maskAllCodes }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(shown?.code?.chunked(3)?.joinToString(" ") ?: "••• •••", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                                        if (maskAllCodes) Text("Hidden", style = MaterialTheme.typography.labelMedium)
                                        else if (shown == null) TextButton(onClick = { showCode(a) }) { Text("Reveal code") }
                                        else Text("${CodeTiming.secondsRemaining(now, shown.validUntil)}s", style = MaterialTheme.typography.titleMedium)
                                    }
                                    val until = CodeTiming.validUntil(now, a.period)
                                    LinearProgressIndicator(progress = { ((until - now).toFloat() / (a.period * 1000f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    Text("${CodeTiming.secondsRemaining(now, until)}s until next code · ${a.digits} digits · ${a.algorithm}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                        Text("Keep your recovery codes somewhere safe. No cloud backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        Button(onClick = { launchScan("pair") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) { Text("Pair a browser") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { val log = Telemetry.read(); startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "OTPBridge diagnostics").putExtra(android.content.Intent.EXTRA_TEXT, log.ifBlank { "No events yet." }.takeLast(90_000)), "Share diagnostics")) }) { Text("Share diagnostics") }
                            TextButton(onClick = { Telemetry.clear(); status = "Diagnostics cleared." }) { Text("Clear") }
                        }
                        if (browsers.isEmpty()) EmptyState("Connect your browser", "Open the extension settings, create a pairing QR, then scan it here.")
                        browsers.forEach { d -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.getString("browserName"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(d.getString("relay").removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!d.optBoolean("active")) {
                                val expired = d.getLong("expiresAt") <= System.currentTimeMillis()
                                Text(if (expired) "Pairing expired. Remove this entry and scan a new QR." else if (d.optBoolean("confirmed")) "Finishing connection automatically…" else "Approval is pending. Remove and scan again if setup was interrupted.")
                                if (!expired && !d.optBoolean("confirmed")) Button(enabled = !busy, onClick = { operation { network { bridge!!.confirm(d.getString("channelId")) }; refresh(false) } }) { Text("Finish approved pairing") }
                            } else Text("Connected", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { operation { network { bridge!!.revoke(d.getString("channelId")) }; refresh(false) } }) { Text("Remove browser") }
                        } } }
                    }
                }
            }
        }
        if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("Add an account") }, text = { Text("Scan the QR shown by the service, or enter its setup key.") }, confirmButton = { TextButton(onClick = { addMenu = false; launchScan("account") }) { Text("Scan QR code") } }, dismissButton = { TextButton(onClick = { addMenu = false; uriInput = ""; enrollment = true }) { Text("Enter setup key") } })
        if (enrollment) EnrollmentDialog()
        deleteAccount?.let { a -> AlertDialog(onDismissRequest = { deleteAccount = null }, title = { Text("Delete this account?") }, text = { Text("${a.issuer.ifBlank { a.label }} will be removed from this phone. This does not disable two-factor authentication on the service. Make sure you have another way to sign in.") }, confirmButton = { TextButton(onClick = { deleteAccount = null; vault.unlock(this, "Delete ${a.issuer.ifBlank { a.label }}", ::error) { key -> vault.delete(key, a.id); loadCatalog(); status = "Account deleted from this phone." } }) { Text("Delete with fingerprint") } }, dismissButton = { TextButton(onClick = { deleteAccount = null }) { Text("Keep account") } }) }
    }
    @Composable private fun PairBrowserScreen(qr: String) {
        BackHandler { pairQr = null }
        val details = remember(qr) { runCatching { require(dev.otpbridge.protocol.StrictJson.validUnicode(qr)); org.json.JSONObject(qr) }.getOrNull() }
        val identity = remember(qr) { dev.otpbridge.ui.BrowserIdentity.from(details?.optString("browserInfo") ?: "", details?.optString("deviceInfo") ?: "") }
        val browserName = details?.optString("browserName")?.take(80)?.ifBlank { "Unknown browser" } ?: "Invalid QR code"
        val relay = details?.optString("relay")?.take(2048) ?: ""
        var technicalDetails by remember(qr) { mutableStateOf(false) }
        val expired = (details?.optLong("expiresAt") ?: 0L) <= now
        Scaffold(
            topBar = { TopAppBar(title = { Text("Connect browser", style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = { pairQr = null }) { Icon(BridgeIcons.Back, contentDescription = "Back to Home") } }) },
            bottomBar = { Surface(shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (expired) Text("This QR expired. Create a new one in your extension.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = details != null && !busy && !expired, modifier = Modifier.fillMaxWidth(), onClick = { pairQr = null; operation { network { bridge!!.pair(qr) }; tab = 2; refresh(false) } }) { Text("Connect $browserName", maxLines = 1) }
                    TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { pairQr = null; operation { try { network { bridge!!.rejectPair(qr) }; status = "Browser pairing denied. No connection was created." } catch (e: Exception) { if(e is CancellationException)throw e; status = "Pairing declined locally. Could not notify browser; its QR will expire." }; Telemetry.track("ui.pair-denied") } }) { Text("Deny browser") }
                }
            } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        dev.otpbridge.ui.BrowserMark(identity.browser)
                        Column(Modifier.weight(1f)) { Text(browserName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(identity.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text("Is this your browser?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Connect only if you opened this QR yourself.", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You stay in control", style = MaterialTheme.typography.titleSmall)
                        Text("This browser can ask for a code. You decide what to send.", style = MaterialTheme.typography.bodyMedium)
                        Text("Your secret keys never leave this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Relay", style = MaterialTheme.typography.labelLarge)
                Text(relay.removePrefix("https://"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { technicalDetails = !technicalDetails }) { Text(if (technicalDetails) "Hide technical details" else "Technical details") }
                AnimatedVisibility(technicalDetails) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(details?.optString("browserInfo")?.take(240) ?: "", style = MaterialTheme.typography.bodySmall)
                    Text(details?.optString("deviceInfo")?.take(240) ?: "", style = MaterialTheme.typography.bodySmall)
                    Text("Browser details are provided by the scanned QR, not verified hardware identity.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
    @Composable private fun ReviewScreen(request: PhoneBridge.Pending) {
        BackHandler { selectedRequest = null; selectedAccount = null; approveBinding = false }
        var showDetails by remember(request.key) { mutableStateOf(false) }
        val body = request.body
        val context = body.optJSONObject("context")
        val device = context?.optJSONObject("device"); val browser = context?.optJSONObject("browser"); val location = context?.optJSONObject("location")
        val left = CodeTiming.secondsRemaining(now, body.getLong("expiresAt"))
        val ref = body.optString("accountRef", "")
        val choices = accounts.filter { ref.isEmpty() || it.id == ref }
        var accountQuery by remember(request.key) { mutableStateOf("") }
        val showSearch = dev.otpbridge.vault.AccountSearch.shouldShow(choices)
        val filteredChoices = dev.otpbridge.vault.AccountSearch.filter(choices, accountQuery)
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Review request", style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                    IconButton(onClick = { selectedRequest = null; selectedAccount = null; approveBinding = false }) { Icon(BridgeIcons.Back, contentDescription = "Back to Home") }
                })
            },
            bottomBar = {
                Surface(shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                        Text(if (left > 0) "Only this code is shared. Your secret stays on this phone." else "This request expired. Ask for a new code in your browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && left > 0, onClick = { val r = request; selectedRequest = null; operation { network { bridge!!.deny(r) }; status = "OTP request denied. No code was shared."; refresh(false) } }, modifier = Modifier.weight(1f)) { Text("Deny") }
                            Button(enabled = selectedAccount != null && !busy && left > 0, modifier = Modifier.weight(2f), onClick = ::approve) { Text(if (sessionEnabled && sessionUnlocked) "Send code" else "Approve with fingerprint") }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(body.getString("origin").removePrefix("https://"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(body.getString("browserName"), browser?.let { "${it.getString("name")} ${it.getString("version").substringBefore('.')}" }, device?.optString("os")?.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Surface(shape = RoundedCornerShape(50), color = if (left <= 15) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant) {
                        Text("${left}s", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = if (left <= 15) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (context != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { showDetails = !showDetails }) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(listOfNotNull(context.optString("publicIp").takeIf { it.isNotBlank() }?.let { "IP $it" }, location?.let { locationSummary(it) }).joinToString("  ·  ").ifBlank { "Request details" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(if (showDetails) "Hide" else "Details", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    AnimatedVisibility(showDetails) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dev.otpbridge.protocol.RequestContext.lines(context).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            location?.let { dev.otpbridge.ui.LocationPreview(it) }
                            Text("Browser-provided context, not proof of identity.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (needsMigration) TextButton(onClick = ::migrateCatalog) { Text("Unlock once to update account list") }
                if (choices.isEmpty() && !needsMigration) Text("Add an authenticator account first.", style = MaterialTheme.typography.bodyMedium)
                Text("Choose an account", style = MaterialTheme.typography.titleSmall)
                if (showSearch) OutlinedTextField(value = accountQuery, onValueChange = { query -> accountQuery = query; if (dev.otpbridge.vault.AccountSearch.filter(choices, query).none { it.id == selectedAccount }) selectedAccount = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search accounts") }, placeholder = { Text("Name, issuer or email") }, trailingIcon = { if (accountQuery.isNotEmpty()) TextButton(onClick = { accountQuery = "" }) { Text("Clear") } })
                if (showSearch && filteredChoices.isEmpty()) Text("No matching accounts.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth()) {
                    filteredChoices.forEach { a ->
                        val selected = selectedAccount == a.id
                        Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { selectedAccount = a.id }) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected, onClick = { selectedAccount = a.id })
                                Column(Modifier.weight(1f)) { Text(a.issuer.ifBlank { a.label }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1); if (a.issuer.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                Text("${CodeTiming.secondsRemaining(now, CodeTiming.validUntil(now, a.period))}s", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    private fun locationSummary(l: JSONObject): String {
        val acc = l.getDouble("accuracyMeters")
        return when { acc <= 100 -> "Location ±${acc.toInt()} m"; acc <= 5000 -> "Location ±${"%.1f".format(acc / 1000)} km"; else -> "Approx. area ±${(acc / 1000).toInt()} km" }
    }
    private fun launchScan(mode: String) {
        scanMode = mode
        scan.launch(ScanOptions().setCaptureActivity(SecureCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(if (mode == "pair") "Scan browser pairing QR" else "Scan account setup QR").setBeepEnabled(false))
    }
    @Composable private fun EmptyState(title: String, detail: String) {
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    @Composable private fun EnrollmentDialog() {
        var label by remember { mutableStateOf("") }; var issuer by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
        var algorithm by remember { mutableStateOf("SHA1") }; var digits by remember { mutableStateOf("6") }; var period by remember { mutableStateOf("30") }
        AlertDialog(onDismissRequest = { enrollment = false; uriInput = "" }, title = { Text("Add TOTP account") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (uriInput.isNotEmpty()) Text("Scanned setup ready. Secret is hidden.") else {
                OutlinedTextField(label, { label = it }, label = { Text("Account label") }); OutlinedTextField(issuer, { issuer = it }, label = { Text("Issuer (optional)") }); OutlinedTextField(secret, { secret = it }, label = { Text("Base32 secret") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(algorithm, { algorithm = it.uppercase() }, label = { Text("SHA1 / SHA256 / SHA512") }); OutlinedTextField(digits, { digits = it }, label = { Text("Digits: 6 or 8") }); OutlinedTextField(period, { period = it }, label = { Text("Period seconds: 15–120") })
            }
            Text("There is no vault backup. Keep recovery codes elsewhere.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = {
            if (uriInput.isNotEmpty()) enroll() else try { val a = Totp.validate(TotpAccount(label, issuer, secret, algorithm, digits.toInt(), period.toInt())); vault.unlock(this@MainActivity, "Add ${a.label}", ::error) { key -> vault.add(key, a); enrollment = false; loadCatalog(); status = "Account added. Codes stay locked." } } catch (e: Exception) { error(e.message ?: "Invalid account") }
        }) { Text("Save with biometrics") } }, dismissButton = { TextButton(onClick = { enrollment = false; uriInput = "" }) { Text("Cancel") } })
    }
    override fun onStart() { dev.otpbridge.relay.RelayClient.resumeForeground(); Telemetry.track("app.foreground"); super.onStart() }
    override fun onStop() {
        Telemetry.track("app.background")
        foregroundJobs.toList().forEach { it.cancel() }
        dev.otpbridge.relay.RelayClient.cancelAll()
        vault.cancel()
        session.lock(); sessionUnlocked = false; visibleCodes = emptyMap(); lastSessionStep = null; tab = 0; selectedRequest = null; selectedAccount = null; deleteAccount = null; accountMenu = null; addMenu = false
        approveBinding = false; uriInput = ""; enrollment = false; pairQr = null; busy = false
        super.onStop()
    }
}
