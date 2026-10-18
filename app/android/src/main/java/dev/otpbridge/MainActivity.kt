package dev.otpbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    private var status by mutableStateOf("Open the app to fetch requests. No notifications or background service.")
    private var busy by mutableStateOf(false)
    private var browsers by mutableStateOf<List<JSONObject>>(emptyList())
    private var pending by mutableStateOf<List<PhoneBridge.Pending>>(emptyList())
    private var accountNames by mutableStateOf<List<Pair<String, String>>>(emptyList())
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
        runCatching { bridge = PhoneBridge(this); browsers = bridge!!.list() }.onFailure { status = "Device storage unavailable: ${it.message}" }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { if (tab == 1 || browsers.any { !it.optBoolean("active") }) refresh(false); delay(2500) } } }
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF23675B))) { Screen() } }
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
    private fun unlockNames() {
        accountNames = emptyList(); selectedAccount = null
        vault.unlock(this, "View account names", ::error) { key -> accountNames = vault.accounts(key).map { it.id to listOf(it.account.issuer, it.account.label).filter(String::isNotBlank).joinToString(" · ") } }
    }
    private fun enroll() {
        try {
            val account = Totp.parse(uriInput)
            vault.unlock(this, "Add ${account.label}", ::error) { key -> vault.add(key, account); accountNames = vault.accounts(key).map { it.id to "${it.account.issuer} · ${it.account.label}" }; enrollment = false; uriInput = ""; status = "Account added locally. Keep service recovery codes; there is no vault backup yet." }
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
        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OTPBridge", style = MaterialTheme.typography.headlineLarge)
                Text("Phone-held secrets. Explicit approval.", style = MaterialTheme.typography.bodyMedium)
                TabRow(selectedTabIndex = tab) { listOf("Accounts", "Requests", "Browsers").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i; displayedCode = null }, text = { Text(title) }) } }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)
                when (tab) {
                    0 -> {
                        Text("Keep your service recovery codes. Phone loss or biometric key invalidation can make this vault inaccessible. No backup/export in v1.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { scanMode = "account"; scan.launch(ScanOptions().setCaptureActivity(SecureCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan TOTP setup QR").setBeepEnabled(false)) }) { Text("Scan account QR") }
                        OutlinedButton(onClick = { uriInput = ""; enrollment = true }) { Text("Add account manually") }
                        OutlinedButton(onClick = ::unlockNames) { Text("Unlock account list") }
                        accountNames.forEach { (id, name) ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(name); TextButton(onClick = { vault.unlock(this@MainActivity, "Display one code", ::error) { key -> val a = vault.accounts(key).first { it.id == id }.account; displayedCode = "${Totp.generate(a, System.currentTimeMillis() / 1000)} · valid for ${a.period - System.currentTimeMillis() / 1000 % a.period}s"; lifecycleScope.launch { delay(15000); displayedCode = null } } }) { Text("Show code") } } }
                        }
                        displayedCode?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
                    }
                    1 -> {
                        Button(enabled = !busy, onClick = { operation { refresh(true) } }) { Text("Refresh pending requests") }
                        Text("Only requests from confirmed browsers appear. Requests expire after 90 seconds.")
                        if (pending.isEmpty()) Text("No pending requests. Choose “Fill from phone” in your browser.")
                        pending.forEach { request -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(request.body.getString("browserName"), style = MaterialTheme.typography.titleMedium)
                            Text(request.body.getString("origin"))
                            Text("Expires in ${maxOf(0, (request.body.getLong("expiresAt") - System.currentTimeMillis()) / 1000)}s")
                            Row { Button(onClick = { selectedRequest = request; selectedAccount = null; approveBinding = false; unlockNames() }) { Text("Review") }; TextButton(onClick = { operation { network { bridge!!.deny(request) }; refresh(false) } }) { Text("Deny") } }
                        } } }
                    }
                    2 -> {
                        Button(onClick = { scanMode = "pair"; scan.launch(ScanOptions().setCaptureActivity(SecureCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan browser pairing QR").setBeepEnabled(false)) }) { Text("Pair browser QR") }
                        Text("Pair only with a browser you control. Compare both confirmation numbers before confirming.")
                        browsers.forEach { d -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.getString("browserName"), style = MaterialTheme.typography.titleMedium); Text(d.getString("relay"), style = MaterialTheme.typography.bodySmall)
                            if (!d.optBoolean("active")) {
                                val sas = d.getString("sas"); Text("${sas.take(4)} ${sas.takeLast(4)}", style = MaterialTheme.typography.headlineMedium)
                                Text(if (d.optBoolean("confirmed")) "Waiting for browser confirmation. Keep app open." else "Does this exactly match the browser?")
                                if (!d.optBoolean("confirmed")) Button(enabled = !busy, onClick = { operation { network { bridge!!.confirm(d.getString("channelId")) }; refresh(false) } }) { Text("Numbers match — confirm") }
                            } else Text("Paired and ready")
                            TextButton(onClick = { operation { network { bridge!!.revoke(d.getString("channelId")) }; refresh(false) } }) { Text("Revoke browser") }
                        } } }
                    }
                }
            }
        }
        if (enrollment) EnrollmentDialog()
        pairQr?.let { qr -> AlertDialog(onDismissRequest = { pairQr = null }, title = { Text("Pair this browser?") }, text = { Text("Only continue if this QR came from your own OTPBridge extension. You will compare confirmation numbers next.") }, confirmButton = { TextButton(onClick = { pairQr = null; operation { network { bridge!!.pair(qr) }; tab = 2; refresh(false) } }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { pairQr = null }) { Text("Cancel") } }) }
        selectedRequest?.let { request -> AlertDialog(onDismissRequest = { selectedRequest = null }, title = { Text("Approve OTP fill") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.body.getString("browserName")); Text(request.body.getString("origin"), style = MaterialTheme.typography.titleMedium)
            Text("Select the account for this exact origin. TOTP cannot prevent phishing; check the hostname.")
            val ref = request.body.optString("accountRef", "")
            accountNames.filter { ref.isEmpty() || it.first == ref }.forEach { (id, name) -> Row { RadioButton(selectedAccount == id, onClick = { selectedAccount = id }); Text(name, Modifier.padding(top = 12.dp)) } }
            Row { Checkbox(approveBinding, onCheckedChange = { approveBinding = it }); Text("I approve using this account on this website.") }
        } }, confirmButton = { TextButton(enabled = selectedAccount != null && approveBinding && !busy, onClick = ::approve) { Text("Approve with biometrics") } }, dismissButton = { TextButton(onClick = { selectedRequest = null }) { Text("Cancel") } }) }
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
            if (uriInput.isNotEmpty()) enroll() else try { val a = Totp.validate(TotpAccount(label, issuer, secret, algorithm, digits.toInt(), period.toInt())); vault.unlock(this@MainActivity, "Add ${a.label}", ::error) { key -> vault.add(key, a); enrollment = false; accountNames = emptyList(); status = "Account added locally. Unlock account list to view." } } catch (e: Exception) { error(e.message ?: "Invalid account") }
        }) { Text("Save with biometrics") } }, dismissButton = { TextButton(onClick = { enrollment = false; uriInput = "" }) { Text("Cancel") } })
    }
    override fun onStart() { super.onStart(); dev.otpbridge.relay.RelayClient.resumeForeground(); operation { refresh(false) } }
    override fun onStop() {
        foregroundJobs.toList().forEach { it.cancel() }
        dev.otpbridge.relay.RelayClient.cancelAll()
        vault.cancel()
        displayedCode = null; accountNames = emptyList(); selectedRequest = null; selectedAccount = null
        approveBinding = false; uriInput = ""; enrollment = false; pairQr = null; busy = false
        super.onStop()
    }
}
