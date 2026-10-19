package dev.otpbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MainActivity : FragmentActivity() {
    private lateinit var vault: Vault
    private var bridge: PhoneBridge? = null
    private val networkLock = Mutex()
    private val foregroundJobs = mutableSetOf<Job>()
    private var tab by mutableIntStateOf(0)
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
    private var displayedCode by mutableStateOf<String?>(null)
    private var scanMode = "account"
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { text -> if (scanMode == "pair") pairQr = text else { uriInput = text; enrollment = true } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = Vault(this)
        loadCatalog()
        runCatching { bridge = PhoneBridge(this); browsers = bridge!!.list() }.onFailure { status = "Device storage unavailable: ${it.message}" }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { refresh(false); while (isActive) { delay(2500); if (tab == 1 || browsers.any { !it.optBoolean("active") && it.optLong("expiresAt") > System.currentTimeMillis() }) refresh(false) } } }
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
        try { val result = network { val errors = b.refresh { job?.isActive == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }; Triple(b.list(), b.pendingRequests(), errors) }; browsers = result.first; pending = result.second; selectedRequest?.let { reviewed -> if (pending.none { it.key == reviewed.key && it.body.toString() == reviewed.body.toString() }) { selectedRequest = null; selectedAccount = null; approveBinding = false } }; if (result.third.isNotEmpty()) status = result.third.joinToString("\n"); else if (showStatus) status = "Fetched ${pending.size} pending request(s)." } catch (e: CancellationException) { throw e } catch (e: Exception) { status = "Refresh failed: ${e.message}" }
    }
    private fun loadCatalog() {
        runCatching { accounts = vault.summaries(); needsMigration = vault.needsCatalogMigration }.onFailure { status = "Account list unavailable. Unlock once to repair it."; needsMigration = true }
    }
    private fun migrateCatalog() = vault.unlock(this, "Update your account list once", ::error) { key -> vault.migrateCatalog(key); loadCatalog(); status = "Account names are now visible. Codes stay locked." }
    private fun review(request: PhoneBridge.Pending) {
        selectedRequest = request; approveBinding = true
        val ref = request.body.optString("accountRef", "")
        val choices = accounts.filter { ref.isEmpty() || it.id == ref }
        selectedAccount = choices.singleOrNull()?.id
    }
    private fun showCode(account: AccountSummary) = vault.unlock(this, "Show ${account.issuer.ifBlank { account.label }} code", ::error) { key ->
        val a = vault.accounts(key).firstOrNull { it.id == account.id }?.account ?: throw IllegalStateException("Account no longer exists")
        val now = System.currentTimeMillis(); val remaining = a.period * 1000L - now % (a.period * 1000L)
        displayedCode = Totp.generate(a, now / 1000).chunked(3).joinToString(" ")
        operation { delay(minOf(15000L, remaining)); displayedCode = null }
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
        vault.unlock(this, "Approve ${request.body.getString("origin")}", ::error) { key ->
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
                } finally { authorizedKey.fill(0) }
                status = "Approved code sent. The browser validates the original field before filling."; refresh(false)
            }
        }
    }
    @Composable private fun Screen() {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("Accounts", "Requests", "Browsers").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; displayedCode = null; status = "" },
                            icon = { Icon(listOf(BridgeIcons.Accounts, BridgeIcons.Requests, BridgeIcons.Browser)[i], contentDescription = null) },
                            label = { Text(if (i == 1 && pending.isNotEmpty()) "Requests · ${pending.size}" else label) })
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0) FloatingActionButton(onClick = { addMenu = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(BridgeIcons.Add, contentDescription = "Add account") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text(if (tab == 0) "OTPBridge" else if (tab == 1) "Requests" else "Browsers", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (tab == 0) "Your accounts. Your approval." else if (tab == 1) "Review before you share a code." else "Only devices you trust.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (tab == 1) TextButton(enabled = !busy, onClick = { operation { refresh(true) } }) { Text("Refresh") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) { Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                if (pending.isNotEmpty() && tab != 1) Surface(onClick = { tab = 1 }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("${pending.size} request${if (pending.size == 1) "" else "s"} waiting", fontWeight = FontWeight.SemiBold); Text("Review →") } }
                when (tab) {
                    0 -> {
                        if (needsMigration) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Update your account list", fontWeight = FontWeight.SemiBold); Text("Unlock once to show names without unlocking codes. Your existing secrets stay unchanged.", style = MaterialTheme.typography.bodySmall); Button(onClick = ::migrateCatalog) { Text("Update with fingerprint") } } }
                        if (accounts.isNotEmpty()) OutlinedTextField(search, { search = it }, placeholder = { Text("Search accounts") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
                        if (accounts.isEmpty() && !needsMigration) EmptyState("Your codes, safely kept", "Add your first account with a setup QR or secret key. Names stay visible; codes stay locked.")
                        accounts.filter { search.isBlank() || "${it.issuer} ${it.label}".contains(search, true) }.forEach { a ->
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { Text((a.issuer.ifBlank { a.label }).take(1).uppercase(), fontWeight = FontWeight.Bold) } }
                                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(a.issuer.ifBlank { a.label }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (a.issuer.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        Box { IconButton(onClick = { accountMenu = a.id }) { Icon(BridgeIcons.More, contentDescription = "Account options for ${a.label}") }; DropdownMenu(expanded = accountMenu == a.id, onDismissRequest = { accountMenu = null }) { DropdownMenuItem(text = { Text("Delete account") }, onClick = { accountMenu = null; deleteAccount = a }) } }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("••• •••", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = { showCode(a) }) { Text("Reveal code") } }
                                    Text("${a.digits} digits · ${a.period}s · ${a.algorithm}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(64.dp))
                        Text("Keep your recovery codes somewhere safe. No cloud backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        if (pending.isEmpty()) EmptyState("All clear", "Choose Fill from phone in your browser. Keep this screen open to receive the request.")
                        pending.forEach { request -> Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(request.body.getString("browserName"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(request.body.getString("origin").removePrefix("https://"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("A one-time code is requested. Nothing is shared until you approve.", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(enabled = !busy, onClick = { review(request) }) { Text("Choose account") }; TextButton(enabled = !busy, onClick = { operation { network { bridge!!.deny(request) }; refresh(false) } }) { Text("Deny") } }
                        } } }
                    }
                    2 -> {
                        Button(onClick = { launchScan("pair") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) { Text("Pair a browser") }
                        if (browsers.isEmpty()) EmptyState("Connect your browser", "Open the extension settings, create a pairing QR, then scan it here.")
                        browsers.forEach { d -> Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(d.getString("browserName"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(d.getString("relay").removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!d.optBoolean("active")) {
                                val expired = d.getLong("expiresAt") <= System.currentTimeMillis()
                                val sas = d.getString("sas"); Text("${sas.take(4)} ${sas.takeLast(4)}", style = MaterialTheme.typography.headlineLarge)
                                Text(if (expired) "Pairing expired. Remove this entry and scan a new QR." else if (d.optBoolean("confirmed")) "Finishing connection automatically…" else "Do these digits match your browser?")
                                if (!expired && !d.optBoolean("confirmed")) Button(enabled = !busy, onClick = { operation { network { bridge!!.confirm(d.getString("channelId")) }; refresh(false) } }) { Text("Digits match · connect") }
                            } else Text("Connected", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { operation { network { bridge!!.revoke(d.getString("channelId")) }; refresh(false) } }) { Text("Remove browser") }
                        } } }
                    }
                }
            }
        }
        if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("Add an account") }, text = { Text("Scan the QR shown by the service, or enter its setup key.") }, confirmButton = { TextButton(onClick = { addMenu = false; launchScan("account") }) { Text("Scan QR code") } }, dismissButton = { TextButton(onClick = { addMenu = false; uriInput = ""; enrollment = true }) { Text("Enter setup key") } })
        if (enrollment) EnrollmentDialog()
        displayedCode?.let { code -> AlertDialog(onDismissRequest = { displayedCode = null }, title = { Text("Your one-time code") }, text = { Column { Text(code, style = MaterialTheme.typography.displaySmall); Text("Hidden automatically in a few seconds.", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { displayedCode = null }) { Text("Hide code") } }) }
        deleteAccount?.let { a -> AlertDialog(onDismissRequest = { deleteAccount = null }, title = { Text("Delete this account?") }, text = { Text("${a.issuer.ifBlank { a.label }} will be removed from this phone. This does not disable two-factor authentication on the service. Make sure you have another way to sign in.") }, confirmButton = { TextButton(onClick = { deleteAccount = null; vault.unlock(this, "Delete ${a.issuer.ifBlank { a.label }}", ::error) { key -> vault.delete(key, a.id); loadCatalog(); status = "Account deleted from this phone." } }) { Text("Delete with fingerprint") } }, dismissButton = { TextButton(onClick = { deleteAccount = null }) { Text("Keep account") } }) }
        pairQr?.let { qr -> AlertDialog(onDismissRequest = { pairQr = null }, title = { Text("Connect this browser?") }, text = { Text("Continue only if you opened this QR in your own extension. Next, compare the digits and confirm here on your phone.") }, confirmButton = { TextButton(onClick = { pairQr = null; operation { network { bridge!!.pair(qr) }; tab = 2; refresh(false) } }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { pairQr = null }) { Text("Cancel") } }) }
        selectedRequest?.let { request -> AlertDialog(onDismissRequest = { selectedRequest = null }, title = { Text("Share one code") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(request.body.getString("origin").removePrefix("https://"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Requested by ${request.body.getString("browserName")}. Check the website before approving.", style = MaterialTheme.typography.bodySmall)
            val ref = request.body.optString("accountRef", "")
            if (needsMigration) { Text("Unlock once to update your existing account names."); TextButton(onClick = ::migrateCatalog) { Text("Update account list") } }
            accounts.filter { ref.isEmpty() || it.id == ref }.forEach { a -> Row(Modifier.fillMaxWidth().clickable { selectedAccount = a.id }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selectedAccount == a.id, onClick = { selectedAccount = a.id }); Column { Text(a.issuer.ifBlank { a.label }, fontWeight = FontWeight.Medium); if (a.issuer.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodySmall) } } }
            if (accounts.isEmpty() && !needsMigration) Text("Add an authenticator account first.")
            Text("Only this code is shared. Your secret key stays here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { TextButton(enabled = selectedAccount != null && !busy, onClick = ::approve) { Text("Approve with fingerprint") } }, dismissButton = { TextButton(onClick = { selectedRequest = null }) { Text("Cancel") } }) }
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
    override fun onStart() { dev.otpbridge.relay.RelayClient.resumeForeground(); super.onStart() }
    override fun onStop() {
        foregroundJobs.toList().forEach { it.cancel() }
        dev.otpbridge.relay.RelayClient.cancelAll()
        vault.cancel()
        displayedCode = null; selectedRequest = null; selectedAccount = null; deleteAccount = null; accountMenu = null; addMenu = false
        approveBinding = false; uriInput = ""; enrollment = false; pairQr = null; busy = false
        super.onStop()
    }
}
