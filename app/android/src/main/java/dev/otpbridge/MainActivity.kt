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
    private var diagnosticsOpen by mutableStateOf(false)
    private var importOpen by mutableStateOf(false)
    private var importEntries by mutableStateOf<List<dev.otpbridge.totp.AuthenticatorImport.Entry>>(emptyList())
    private var importSkipped by mutableStateOf<List<dev.otpbridge.totp.AuthenticatorImport.Skipped>>(emptyList())
    private var importBatches by mutableStateOf<Set<Int>>(emptySet())
    private var importBatchSize by mutableIntStateOf(0)
    private var importExcluded by mutableStateOf<Set<Int>>(emptySet())
    private var importImageResults by mutableStateOf<List<String>>(emptyList())
    private var importDecoding by mutableStateOf(false)
    private var browserMenu by mutableStateOf<String?>(null)
    private var removeBrowser by mutableStateOf<JSONObject?>(null)
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
        result.contents?.let { text -> when {
            scanMode == "pair" -> pairQr = text
            scanMode == "import" || text.startsWith("otpauth-migration://", true) -> addImport(text, "transfer QR")
            else -> { uriInput = text; enrollment = true }
        } }
    }
    private val openImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = input.read(buf); if (n < 0) break; require(out.size() + n <= 512 * 1024) { "File is too large" }; out.write(buf, 0, n) }
                out.toString(Charsets.UTF_8.name())
            } ?: throw IllegalStateException("Could not open file")
            addImport(text, "file")
        } catch (e: Exception) { status = "Could not read import file: ${e.message ?: "unknown error"}" }
    }
    private val pickQrImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        importDecoding = true
        lifecycleScope.launch {
            // Decode off the main thread; secrets stay in memory only.
            val decoded = withContext(Dispatchers.Default) { uris.mapIndexed { i, uri -> i to runCatching { dev.otpbridge.totp.QrImageDecoder.decode(contentResolver, uri) }.getOrNull() } }
            val results = ArrayList<String>()
            decoded.filter { it.second == null }.forEach { (i, _) -> results += "Image ${i + 1}: no QR code found" }
            val texts = decoded.mapNotNull { (i, text) -> text?.let { i to it } }
            val ordered = dev.otpbridge.totp.AuthenticatorImport.orderByPart(texts.map { it.second })
            ordered.forEach { text ->
                val i = texts.first { it.second === text }.first
                if (!text.trim().startsWith("otpauth", true)) { results += "Image ${i + 1}: not an authenticator QR"; return@forEach }
                val before = importEntries.size; val parts = importBatches.size
                addImport(text, "image")
                val part = dev.otpbridge.totp.AuthenticatorImport.runCatching { parseMigration(text).batch }.getOrNull()?.let { "part ${it.index + 1} of ${it.size} · " } ?: ""
                val added = importEntries.size - before
                results += if (part.isNotEmpty() && importBatches.size == parts) "Image ${i + 1}: ${part}already added" else "Image ${i + 1}: $part$added account${if (added == 1) "" else "s"}"
            }
            importImageResults = importImageResults + results
            Telemetry.track("import.images", "picked" to uris.size, "decoded" to decoded.count { it.second != null })
            importDecoding = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Telemetry.init(this)
        Telemetry.track("app.create")
        vault = Vault(this)
        loadCatalog()
        // Unlock once per visit is the default; an explicit user choice is respected.
        sessionEnabled = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("foregroundSession", true)
        maskAllCodes = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("maskAllCodes", false)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            // No prompt on an empty vault; first unlock happens when a code is needed.
            if (sessionEnabled && !session.unlocked && accounts.isNotEmpty()) unlockSession()
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
        if (importOpen) { ImportScreen(); return }
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
                        Text(if (tab == 0) "Accounts & approvals" else "Browsers that can request codes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (tab == 2) IconButton(onClick = { diagnosticsOpen = true }) { Icon(BridgeIcons.Bug, contentDescription = "Diagnostics") }
                    if (tab == 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconToggleButton(checked = !maskAllCodes, onCheckedChange = { visible -> maskAllCodes = !visible; getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("maskAllCodes", maskAllCodes).apply() }) { Icon(if(maskAllCodes) BridgeIcons.EyeOff else BridgeIcons.Eye, contentDescription = if(maskAllCodes) "Show account codes" else "Hide all account codes") }
                        TextButton(enabled = !busy, onClick = { operation { refresh(true) } }) { Text("Refresh") }
                    }
                }
                if (tab == 0) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
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
                        if (browsers.isEmpty()) EmptyState("No browsers yet", "Open the OTPBridge extension settings, create a pairing QR, then scan it here.")
                        else Text("CONNECTED · ${browsers.count { it.optBoolean("active") }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        browsers.forEach { d -> BrowserCard(d) }
                        Button(onClick = { launchScan("pair") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) { Text("Pair a browser") }
                        Text("Each browser can only ask for codes. You approve every request here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("Add accounts") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Triple("Scan setup QR", "From a website's two-factor setup page") { addMenu = false; launchScan("account") },
                    Triple("Enter setup key", "Type the key manually") { addMenu = false; uriInput = ""; enrollment = true },
                    Triple("Import from another app", "Google Authenticator transfer QR or a .txt export") { addMenu = false; resetImport(); importOpen = true }).forEach { (title, detail, action) ->
                    Surface(onClick = action, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("Close") } })
        if (enrollment) EnrollmentDialog()
        if (diagnosticsOpen) DiagnosticsSheet()
        removeBrowser?.let { d -> AlertDialog(onDismissRequest = { removeBrowser = null }, title = { Text("Remove ${d.getString("browserName")}?") }, text = { Text("This browser will no longer be able to request codes. You can pair it again later.") }, confirmButton = { TextButton(onClick = { removeBrowser = null; operation { network { bridge!!.revoke(d.getString("channelId")) }; status = "${d.getString("browserName")} removed."; refresh(false) } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removeBrowser = null }) { Text("Keep") } }) }
        deleteAccount?.let { a -> AlertDialog(onDismissRequest = { deleteAccount = null }, title = { Text("Delete this account?") }, text = { Text("${a.issuer.ifBlank { a.label }} will be removed from this phone. This does not disable two-factor authentication on the service. Make sure you have another way to sign in.") }, confirmButton = { TextButton(onClick = { deleteAccount = null; vault.unlock(this, "Delete ${a.issuer.ifBlank { a.label }}", ::error) { key -> vault.delete(key, a.id); loadCatalog(); status = "Account deleted from this phone." } }) { Text("Delete with fingerprint") } }, dismissButton = { TextButton(onClick = { deleteAccount = null }) { Text("Keep account") } }) }
    }
    @Composable private fun ImportScreen() {
        BackHandler { importOpen = false; resetImport() }
        val chosen = importEntries.indices.count { it !in importExcluded }
        Scaffold(
            topBar = { TopAppBar(title = { Text("Import accounts", style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = { importOpen = false; resetImport() }) { Icon(BridgeIcons.Back, contentDescription = "Back") } }) },
            bottomBar = { if (importEntries.isNotEmpty()) Surface(shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (importBatchSize > 1 && importBatches.size < importBatchSize) Text("Scanned ${importBatches.size} of $importBatchSize transfer QR codes. You can import now or scan the rest first.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = chosen > 0 && !busy, modifier = Modifier.fillMaxWidth(), onClick = ::saveImport) { Text("Import $chosen account${if (chosen == 1) "" else "s"} with fingerprint") }
                }
            } }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) { Text(status, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                Text("From Google Authenticator", style = MaterialTheme.typography.titleSmall)
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("1. In Google Authenticator, open ⋮ menu → Transfer accounts → Export accounts.", style = MaterialTheme.typography.bodySmall)
                        Text("2. Select accounts and tap Next to show the QR code.", style = MaterialTheme.typography.bodySmall)
                        Text("3. Screenshot each QR code (large exports have several), then choose them all below. Order does not matter.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(enabled = !importDecoding, onClick = { scanMode = "import"; pickQrImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text(if (importDecoding) "Reading images…" else "Choose QR screenshots") }
                OutlinedButton(onClick = { launchScan("import") }, modifier = Modifier.fillMaxWidth()) { Text(if (importBatches.isEmpty()) "Scan with camera" else "Scan next QR with camera") }
                if (importDecoding) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (importBatchSize > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Parts:", style = MaterialTheme.typography.labelMedium)
                    (0 until importBatchSize).forEach { part ->
                        val have = part in importBatches
                        Surface(shape = RoundedCornerShape(50), color = if (have) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant) {
                            Text("${part + 1}", Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = if (have) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                importImageResults.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("From a text file", style = MaterialTheme.typography.titleSmall)
                Text("A .txt file with one otpauth:// link per line, as exported by Aegis, 2FAS, Bitwarden and others.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { scanMode = "import"; openImportFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose file") }
                if (importEntries.isNotEmpty()) {
                    Text("FOUND · ${importEntries.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    importEntries.forEachIndexed { i, e ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = i !in importExcluded, onCheckedChange = { on -> importExcluded = if (on) importExcluded - i else importExcluded + i })
                                Column(Modifier.weight(1f)) {
                                    Text(e.account.issuer.ifBlank { e.account.label }, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(listOfNotNull(e.account.label.takeIf { e.account.issuer.isNotBlank() }, "${e.account.digits} digits", e.account.algorithm.takeIf { it != "SHA1" }, if (e.weak) "short secret" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (importEntries.any { it.weak }) Text("“Short secret” accounts work, but their key is shorter than recommended. Consider re-enrolling them on the website.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (importSkipped.isNotEmpty()) {
                    Text("SKIPPED · ${importSkipped.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    importSkipped.forEach { Text("${it.source}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                }
                Text("Images and files are read on this phone only; nothing is uploaded. Delete the QR screenshots and export files after importing — they contain your secret keys.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable private fun BrowserCard(d: JSONObject) {
        val identity = remember(d.toString()) { dev.otpbridge.ui.BrowserIdentity.from(d.optString("browserInfo"), d.optString("deviceInfo")) }
        val active = d.optBoolean("active"); val expired = !active && d.getLong("expiresAt") <= System.currentTimeMillis()
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.otpbridge.ui.BrowserMark(identity.browser, Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(d.getString("browserName"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(identity.summary.ifBlank { "Browser" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box { IconButton(onClick = { browserMenu = d.getString("channelId") }) { Icon(BridgeIcons.More, contentDescription = "Options for ${d.getString("browserName")}") }
                        DropdownMenu(expanded = browserMenu == d.getString("channelId"), onDismissRequest = { browserMenu = null }) { DropdownMenuItem(text = { Text("Remove browser") }, onClick = { browserMenu = null; removeBrowser = d }) } }
                }
                Row(Modifier.padding(top = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(50), color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface) {
                        Text(if (active) "Connected" else if (expired) "Expired" else if (d.optBoolean("confirmed")) "Finishing…" else "Pending", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(d.getString("relay").removePrefix("https://"), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (expired) Text("This pairing expired. Remove it and scan a new QR.", Modifier.padding(top = 6.dp, end = 10.dp), style = MaterialTheme.typography.bodySmall)
                else if (!active && !d.optBoolean("confirmed")) TextButton(enabled = !busy, onClick = { operation { network { bridge!!.confirm(d.getString("channelId")) }; refresh(false) } }) { Text("Finish approved pairing") }
            }
        }
    }
    @Composable private fun DiagnosticsSheet() {
        var summary by remember { mutableStateOf(Telemetry.summary()) }
        val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
        ModalBottomSheet(onDismissRequest = { diagnosticsOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(BridgeIcons.Bug, contentDescription = null); Text("Diagnostics", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                Text("A local log of app activity to help fix bugs. Attach the file to a GitHub issue if something goes wrong.", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Events recorded" to "${summary.events}", "Errors / rejections" to "${summary.errors}", "Oldest" to (summary.first?.let { fmt.format(java.util.Date(it)) } ?: "—"), "Newest" to (summary.last?.let { fmt.format(java.util.Date(it)) } ?: "—"), "Size" to "${(summary.bytes + 1023) / 1024} KB").forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Safe to share", style = MaterialTheme.typography.titleSmall)
                        Text("Never includes OTP codes, secret keys, relay tokens, encrypted messages, IP addresses, locations, emails or account names. These are removed when recorded and again on export. Stored only on this phone; nothing is uploaded.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(enabled = summary.events > 0, modifier = Modifier.fillMaxWidth(), onClick = { shareDiagnostics() }) { Text("Share report file (.txt)") }
                OutlinedButton(enabled = summary.events > 0, modifier = Modifier.fillMaxWidth(), onClick = { Telemetry.clear(); summary = Telemetry.summary(); status = "Diagnostics cleared." }) { Text("Clear log") }
            }
        }
    }
    private fun shareDiagnostics() {
        try {
            val file = Telemetry.exportReport(this, BuildConfig.VERSION_NAME)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.diagnostics", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, "OTPBridge diagnostics ${BuildConfig.VERSION_NAME}").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(file.name, uri)
            startActivity(android.content.Intent.createChooser(send, "Share diagnostics report"))
            Telemetry.track("diagnostics.exported")
        } catch (e: Exception) { status = "Could not create diagnostics file: ${e.javaClass.simpleName}" }
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
    private fun addImport(text: String, source: String) {
        val r = try { dev.otpbridge.totp.AuthenticatorImport.parseText(text) } catch (e: Exception) { status = e.message ?: "Could not import"; importOpen = true; return }
        importOpen = true
        r.batch?.let { b ->
            if (importBatchSize != 0 && importBatchSize != b.size) { importEntries = emptyList(); importSkipped = emptyList(); importBatches = emptySet() }
            importBatchSize = b.size
            if (b.index in importBatches) { status = "QR ${b.index + 1} of ${b.size} was already scanned."; return }
            importBatches = importBatches + b.index
        }
        importEntries = importEntries + r.entries; importSkipped = importSkipped + r.skipped
        Telemetry.track("import.parsed", "source" to source, "found" to r.entries.size, "skipped" to r.skipped.size, "batchIndex" to r.batch?.index, "batchSize" to r.batch?.size)
        status = ""
    }
    private fun resetImport() { if (scanMode == "import") scanMode = "account"; importEntries = emptyList(); importSkipped = emptyList(); importBatches = emptySet(); importBatchSize = 0; importExcluded = emptySet(); importImageResults = emptyList() }
    private fun saveImport() {
        val chosen = importEntries.filterIndexed { i, _ -> i !in importExcluded }.map { it.account }
        if (chosen.isEmpty()) return
        vault.unlock(this, "Import ${chosen.size} account${if (chosen.size == 1) "" else "s"}", ::error) { key ->
            val added = vault.addAll(key, chosen); val dup = chosen.size - added
            loadCatalog(); importOpen = false; resetImport()
            status = "Imported $added account${if (added == 1) "" else "s"}" + if (dup > 0) ". $dup already existed and were skipped." else "."
            Telemetry.track("import.saved", "added" to added, "duplicates" to dup)
        }
    }
    private fun launchScan(mode: String) {
        scanMode = mode
        scan.launch(ScanOptions().setCaptureActivity(SecureCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(when (mode) { "pair" -> "Scan browser pairing QR"; "import" -> "Scan Google Authenticator transfer QR"; else -> "Scan account setup QR" }).setBeepEnabled(false))
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
        approveBinding = false; uriInput = ""; enrollment = false; pairQr = null; busy = false; diagnosticsOpen = false; browserMenu = null; removeBrowser = null; if (scanMode != "import") { importOpen = false; resetImport() }
        super.onStop()
    }
}
