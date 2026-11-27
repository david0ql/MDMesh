package com.dallycontrol.agent.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.core.config.ManagedWifi
import com.dallycontrol.core.config.WifiAuthError
import com.dallycontrol.core.config.WifiDiagnosis
import com.dallycontrol.core.config.WifiPriority
import com.dallycontrol.core.config.WifiRulesPlan
import com.dallycontrol.core.config.WifiSafeguard
import com.dallycontrol.core.config.WifiScanMatch
import com.dallycontrol.core.config.WifiSecurity
import com.dallycontrol.core.config.WifiSupport
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.policy.wifi.WifiPolicy
import com.dallycontrol.policy.wifi.WifiPolicyFactory
import com.dallycontrol.policy.wifi.WifiRadio
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.ConfigWifi
import com.dallycontrol.proto.ConfigWifiRules
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.ManagedWifiReport
import com.dallycontrol.proto.ManagedWifiRulesStatus
import com.dallycontrol.proto.ManagedWifiStatus
import com.dallycontrol.proto.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The policy's Wi-Fi on the phone, as the Device Owner:
 *  - **networks**: saved (see [WifiNetworks] for the API used on each Android version), left alone while unchanged so a
 *    re-applied policy never drops the connection, and removed when the policy drops them — the user's own networks
 *    are never touched;
 *  - **certificates** of enterprise networks: installed and removed with them ([WifiCertificates]);
 *  - **lock-down rules** through [WifiPolicy]; a rule the Android version lacks is reported, not ignored;
 *  - **radio**: forced on or off when the policy says so, and put back if the user changes it;
 *  - **safeguard** ([WifiSafeguard]): with a restrictive rule in force and no usable network for the grace period, the
 *    restrictive rules are lifted for a while and the console is told;
 *  - **state** ([report]): per network, whether it is saved and connected, and why not.
 */
@Suppress("TooManyFunctions")
class AndroidManagedWifi(
    private val context: Context,
    private val wifi: WifiNetworks,
    private val handle: DpmHandle,
    private val certs: WifiCertificates,
    private val events: EventSink,
) : ManagedWifi, WifiRadio {
    private val prefs = context.getSharedPreferences("mdm_wifi_policy", Context.MODE_PRIVATE)
    private val safeguard = WifiSafeguard(object : WifiSafeguard.Store {
        override fun load() = this@AndroidManagedWifi.load().guard.let { WifiSafeguard.State(it.offlineSince, it.relaxedAt, it.relaxedUntil) }
        override fun save(state: WifiSafeguard.State) =
            store(this@AndroidManagedWifi.load().copy(guard = Guard(state.offlineSince, state.relaxedAt, state.relaxedUntil)))
    })
    private var cache: State? = null
    private var lastSsid: String? = null
    private var lastHeal = 0L

    @Serializable
    data class Net(
        val netId: Int = -1,
        val fingerprint: String = "",
        val error: String? = null,
        val certificateProblem: Boolean = false,
        val unsupported: Boolean = false,
        val unsupportedOptions: List<String> = emptyList(),
        val via: String = VIA_CONFIGURATION,
    )

    @Serializable
    data class Guard(val offlineSince: Long? = null, val relaxedAt: Long? = null, val relaxedUntil: Long? = null)

    /** What the last policy asked for and what came of it. Private to the app, like the stored policy document. */
    @Serializable
    data class State(
        val managed: Boolean = false,
        val networks: List<ConfigWifi> = emptyList(),
        val rules: ConfigWifiRules? = null,
        val radio: Boolean? = null,
        val nets: Map<String, Net> = emptyMap(),
        val authErrors: Map<String, String> = emptyMap(),
        val rulesApplied: List<String> = emptyList(),
        val rulesUnsupported: List<String> = emptyList(),
        val relaxedReason: String? = null,
        val guard: Guard = Guard(),
    )

    private val policy: WifiPolicy? get() = WifiPolicyFactory.create(handle, this)

    private fun owner() = runCatching {
        handle.dpm.isDeviceOwnerApp(handle.admin.packageName) || handle.dpm.isProfileOwnerApp(handle.admin.packageName)
    }.getOrDefault(false)

    // --- ManagedWifi ---

    @Synchronized
    override fun apply(networks: List<ConfigWifi>, rules: ConfigWifiRules?, radio: Boolean?): String {
        val old = load()
        // A different policy starts from scratch: whatever the safeguard lifted for the old one is over.
        if (old.networks != networks || old.rules != rules || old.radio != radio) safeguard.reset()
        val relaxed = safeguard.state().relaxed
        val nets = if (owner() || Build.VERSION.SDK_INT < 29) saveAsOwner(networks, old, radio) else suggest(networks)
        runCatching { certs.retainOnly(networks) }
        val managed = networks.isNotEmpty() || rules != null || radio != null
        var next = load().copy(
            managed = managed, networks = networks, rules = rules, radio = radio, nets = nets,
            // An error belongs to the network as it was: it goes when the network changes or leaves.
            authErrors = old.authErrors.filterKeys { ssid -> nets[ssid]?.fingerprint == old.nets[ssid]?.fingerprint && nets[ssid] != null },
            relaxedReason = if (relaxed) old.relaxedReason else null,
        )
        store(next)
        if (!relaxed) {
            next = enforceRules(next)
            store(next)
            enforceRadio()
        }
        prefs.edit().remove(LEGACY_ADDED).apply()
        return ConfigOutcome.APPLIED
    }

    private fun saveAsOwner(networks: List<ConfigWifi>, old: State, radio: Boolean?): Map<String, Net> {
        val nets = LinkedHashMap<String, Net>()
        val wpa3 = wifi.wpa3Supported()
        val owe = wifi.oweSupported()
        networks.forEachIndexed { i, n ->
            val fp = fingerprint(n)
            val prev = old.nets[n.ssid]
            val why = WifiSupport.unsupported(n, Build.VERSION.SDK_INT, wpa3, owe)
            nets[n.ssid] = when {
                why != null -> {
                    if (prev != null && prev.netId >= 0) wifi.forget(n.ssid, prev.netId)
                    Net(fingerprint = fp, error = why, unsupported = true)
                }
                prev != null && prev.fingerprint == fp && prev.netId >= 0 && wifi.isConfigured(n.ssid, prev.netId) -> prev
                else -> {
                    // Join the first network only when the phone has no Wi-Fi connection; the rest are just saved.
                    val connect = i == 0 && n.autoConnect != false && wifi.connectedSsid() == null
                    val saved = wifi.save(n, certs, connect, mayEnableRadio = radio != false)
                    if (saved.netId < 0) Log.w(TAG, "network ${i + 1} not saved: ${saved.error}")
                    Net(
                        saved.netId, fp, saved.error, saved.certificateProblem,
                        unsupportedOptions = (saved.unsupportedOptions + WifiSupport.unsupportedOptions(n, Build.VERSION.SDK_INT)).distinct(),
                    )
                }
            }
        }
        val wanted = networks.map { it.ssid }.toSet()
        val mine = old.nets.filterValues { it.via == VIA_CONFIGURATION }.keys + prefs.getStringSet(LEGACY_ADDED, emptySet()).orEmpty()
        (mine - wanted).forEach { gone -> wifi.forget(gone, old.nets[gone]?.netId) }
        return nets
    }

    private fun suggest(networks: List<ConfigWifi>): Map<String, Net> {
        if (Build.VERSION.SDK_INT < 29) return emptyMap()
        val errors = wifi.suggest(networks, certs)
        return networks.associate { n ->
            val error = errors[n.ssid]
            n.ssid to Net(if (error == null) 0 else -1, fingerprint(n), error, unsupported = error != null, via = VIA_SUGGESTION)
        }
    }

    // --- rules ---

    /** Put every rule of [s] in force (and lift the ones it no longer has). @return [s] with what was applied. */
    private fun enforceRules(s: State): State {
        val p = policy
        val rules = s.rules
        val applied = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        fun rule(name: String, wanted: Boolean, force: Boolean = false, op: (WifiPolicy, Boolean) -> PolicyOutcome) {
            if (!wanted && name !in s.rulesApplied && !force) return
            when (val o = p?.let { op(it, wanted) } ?: PolicyOutcome.Unsupported) {
                PolicyOutcome.Applied -> if (wanted) applied += name
                PolicyOutcome.Unsupported -> if (wanted) unsupported += name
                is PolicyOutcome.Failed -> if (wanted) { unsupported += name; Log.w(TAG, "rule $name: ${o.reason}") }
            }
        }
        rule(RULE_LOCK, rules?.lockManaged == true) { w, on -> w.setManagedNetworksLocked(on) }
        rule(RULE_ADD, rules?.blockAdd == true) { w, on -> w.setAddingNetworksBlocked(on) }
        // Before this version the `wifi` toggle set DISALLOW_CONFIG_WIFI; the rule owns it now, so it is settled once.
        val migrate = !prefs.getBoolean(CONFIG_MIGRATED, false)
        rule(RULE_CONFIG, rules?.blockConfig == true, force = migrate) { w, on -> w.setUserChangesBlocked(on) }
        if (migrate && p != null) prefs.edit().putBoolean(CONFIG_MIGRATED, true).apply()
        rule(RULE_STATE, rules?.blockState == true) { w, on -> w.setStateChangeBlocked(on) }
        val mode = rules?.ssidMode?.trim()?.lowercase()
        rule(RULE_SSID, mode == "allow" || mode == "deny") { w, on ->
            when {
                !on || rules == null -> w.setSsidRestriction(null, emptySet())
                mode == "allow" -> w.setSsidRestriction(WifiPolicy.SsidMode.ALLOW, WifiRulesPlan.allowedSsids(rules, s.networks))
                else -> w.setSsidRestriction(WifiPolicy.SsidMode.DENY, WifiRulesPlan.deniedSsids(rules, s.networks))
            }
        }
        val level = rules?.let { WifiRulesPlan.effectiveMinSecurity(it, s.networks) } ?: 0
        rule(RULE_SECURITY, level > 0) { w, on -> w.setMinimumSecurity(if (on) level else 0) }
        return s.copy(rulesApplied = applied, rulesUnsupported = unsupported, relaxedReason = null)
    }

    /** The safeguard's relax: everything that can keep the phone off a network goes; the networks stay locked. */
    private fun relax(reason: String) {
        val s = load()
        val p = policy
        if (p != null) {
            if (RULE_SSID in s.rulesApplied) p.setSsidRestriction(null, emptySet())
            if (RULE_SECURITY in s.rulesApplied) p.setMinimumSecurity(0)
            if (RULE_ADD in s.rulesApplied) p.setAddingNetworksBlocked(false)
            if (RULE_CONFIG in s.rulesApplied) p.setUserChangesBlocked(false)
            if (RULE_STATE in s.rulesApplied) p.setStateChangeBlocked(false)
        }
        if (!wifi.isEnabled()) wifi.setEnabled(true)
        store(s.copy(rulesApplied = s.rulesApplied.filter { it == RULE_LOCK }, relaxedReason = reason))
        Log.w(TAG, "Wi-Fi lock-down relaxed: $reason")
        runCatching { events.record(EventType.WIFI_LOCKDOWN_RELAXED, reason) }
        runCatching { CheckInWorker.scheduleNow(context) }
    }

    private fun restore() {
        store(enforceRules(load()))
        enforceRadio()
        Log.i(TAG, "Wi-Fi lock-down restored")
        runCatching { events.record(EventType.WIFI_LOCKDOWN_RESTORED, null) }
        runCatching { CheckInWorker.scheduleNow(context) }
    }

    // --- radio ---

    /** The `wifi` toggle. While the safeguard holds the lock-down lifted, "off" is not obeyed. */
    override fun set(enabled: Boolean): Boolean {
        if (!enabled && safeguard.state().relaxed) return true
        return wifi.setEnabled(enabled)
    }

    private fun enforceRadio() {
        val want = load().radio ?: return
        if (safeguard.state().relaxed) return
        if (wifi.isEnabled() != want) wifi.setEnabled(want)
    }

    // --- periodic work (see [Watch]) ---

    /** Once a minute while the agent runs: the safeguard, the radio, networks that went missing, and the priority. */
    @Synchronized
    fun tick(now: Long = System.currentTimeMillis()) {
        val s = load()
        if (!s.managed) return
        val grace = WifiRulesPlan.safeguardMinutes(s.rules) * MINUTE_MS
        when (safeguard.tick(now, online(s), WifiRulesPlan.isRestrictive(s.rules, s.radio), grace)) {
            WifiSafeguard.Action.RELAX -> relax("sin red durante ${WifiRulesPlan.safeguardMinutes(s.rules)} min con el bloqueo de Wi-Fi activo")
            WifiSafeguard.Action.RESTORE -> restore()
            WifiSafeguard.Action.NONE -> Unit
        }
        enforceRadio()
        if (now - lastHeal >= HEAL_EVERY_MS) {
            lastHeal = now
            heal()
            prefer()
        }
    }

    /** A managed network that is no longer saved (first save failed, or something removed it) is saved again. */
    private fun heal() {
        val s = load()
        if (!owner()) return
        val missing = s.networks.filter { n ->
            val net = s.nets[n.ssid]
            net != null && !net.unsupported && !net.certificateProblem && net.via == VIA_CONFIGURATION && !wifi.isConfigured(n.ssid, net.netId.takeIf { it >= 0 })
        }
        if (missing.isEmpty()) return
        val nets = s.nets.toMutableMap()
        missing.forEach { n ->
            val saved = wifi.save(n, certs, connect = false, mayEnableRadio = s.radio != false)
            nets[n.ssid] = Net(saved.netId, fingerprint(n), saved.error, saved.certificateProblem, unsupportedOptions = saved.unsupportedOptions)
        }
        store(load().copy(nets = nets))
    }

    /** Android ignores a saved network's priority since Android 8, so the agent moves to the preferred one itself. */
    private fun prefer() {
        val s = load()
        if (s.networks.none { it.priority != null } || !wifi.isEnabled()) return
        val inRange = wifi.scan()?.keys ?: return
        WifiPriority.preferred(s.networks, inRange, wifi.connectedSsid())?.let { wifi.connect(it) }
    }

    /** Usable connectivity: a validated network (Wi-Fi, mobile data, cable), or Wi-Fi on one of the managed networks. */
    private fun online(s: State): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifi.connectedSsid() in s.networks.map { it.ssid }
    }.getOrDefault(false)

    @Synchronized
    fun onAuthError(error: WifiAuthError) {
        val ssid = wifi.connectedSsid() ?: lastSsid ?: return
        val s = load()
        if (s.networks.none { it.ssid == ssid }) return
        store(s.copy(authErrors = s.authErrors + (ssid to error.name)))
        runCatching { CheckInWorker.scheduleNow(context) }
    }

    fun onSupplicantState() {
        wifi.connectedSsid()?.let { lastSsid = it }
    }

    // --- state for the console ---

    /** Null when the policy manages no Wi-Fi. */
    @Synchronized
    fun report(now: Long = System.currentTimeMillis()): ManagedWifiReport? {
        var s = load()
        if (!s.managed) return null
        val connectedSsid = wifi.connectedSsid()?.takeIf { wifi.isConnected() }
        if (connectedSsid != null && connectedSsid in s.authErrors) {
            s = s.copy(authErrors = s.authErrors - connectedSsid)
            store(s)
        }
        val radioOn = wifi.isEnabled()
        val scan = if (radioOn) wifi.scan() else null
        val networks = s.networks.map { n ->
            val net = s.nets[n.ssid] ?: Net()
            val sec = WifiSecurity.normalize(n.security)
            val seen = scan?.get(n.ssid)
            val configured = if (net.via == VIA_SUGGESTION) net.netId >= 0 else net.netId >= 0 && wifi.isConfigured(n.ssid, net.netId)
            val facts = WifiDiagnosis.Facts(
                configured = configured,
                connected = connectedSsid == n.ssid,
                unsupported = net.unsupported,
                certificateProblem = net.certificateProblem,
                radioOn = radioOn,
                // A hidden network never shows up in a scan: not seeing it proves nothing.
                inRange = if (scan == null) null else if (seen != null) true else if (n.hidden == true) null else false,
                securityMatches = seen?.any { WifiScanMatch.matches(sec, it) },
                authError = s.authErrors[n.ssid]?.let { name -> WifiAuthError.values().firstOrNull { it.name == name } },
            )
            ManagedWifiStatus(
                ssid = n.ssid, security = sec, configured = configured, connected = facts.connected, inRange = facts.inRange,
                reason = WifiDiagnosis.reason(n, facts), detail = net.error, unsupportedOptions = net.unsupportedOptions, via = net.via,
            )
        }
        val guard = safeguard.state()
        val rules = if (s.rules == null && s.radio == null && !guard.relaxed) {
            null
        } else {
            ManagedWifiRulesStatus(
                applied = s.rulesApplied + listOfNotNull(s.radio?.let { if (it) RADIO_ON else RADIO_OFF }.takeIf { !guard.relaxed }),
                unsupported = s.rulesUnsupported,
                relaxed = guard.relaxed, relaxedAt = guard.relaxedAt, relaxedUntil = guard.relaxedUntil, relaxedReason = s.relaxedReason,
            )
        }
        return ManagedWifiReport(networks = networks, rules = rules, checkedAt = now)
    }

    // --- storage ---

    private fun load(): State = cache ?: (
        prefs.getString(KEY_STATE, null)?.let { raw -> runCatching { ProtocolJson.json.decodeFromString(State.serializer(), raw) }.getOrNull() }
            ?: State()
        ).also { cache = it }

    private fun store(s: State) {
        cache = s
        prefs.edit().putString(KEY_STATE, ProtocolJson.json.encodeToString(State.serializer(), s)).apply()
    }

    private fun fingerprint(n: ConfigWifi): String =
        MessageDigest.getInstance("SHA-256").digest(ProtocolJson.json.encodeToString(ConfigWifi.serializer(), n).toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * What runs between policy applies while the agent's service is alive: [tick] every minute, the radio put back the
     * moment the user changes it, and the supplicant's authentication errors (the only place Android says "wrong
     * password" to an app).
     */
    class Watch(private val managed: AndroidManagedWifi) {
        private var job: Job? = null
        private var registered: Context? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> managed.enforceRadio()
                        WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                            managed.onSupplicantState()
                            if (intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) {
                                managed.onAuthError(
                                    when (intent.getIntExtra(EXTRA_ERROR_REASON, 0)) {
                                        REASON_WRONG_PASSWORD -> WifiAuthError.WRONG_PASSWORD
                                        REASON_EAP_FAILURE -> WifiAuthError.EAP_FAILURE
                                        REASON_TIMEOUT -> WifiAuthError.TIMEOUT
                                        else -> WifiAuthError.OTHER
                                    },
                                )
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "wifi broadcast", it) }
            }
        }

        @Synchronized
        fun start(context: Context, scope: CoroutineScope) {
            if (registered == null) {
                ContextCompat.registerReceiver(
                    context, receiver,
                    IntentFilter().apply {
                        addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                        addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
                    },
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = context
            }
            if (job?.isActive != true) {
                job = scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        runCatching { managed.tick() }.onFailure { Log.w(TAG, "wifi tick", it) }
                        delay(TICK_MS)
                    }
                }
            }
        }

        @Synchronized
        fun stop() {
            registered?.let { c -> runCatching { c.unregisterReceiver(receiver) } }
            registered = null
            job?.cancel()
        }
    }

    companion object {
        private const val TAG = "ManagedWifi"
        private const val KEY_STATE = "state"
        private const val LEGACY_ADDED = "added"
        private const val CONFIG_MIGRATED = "config_wifi_migrated"
        const val VIA_CONFIGURATION = "configuration"
        const val VIA_SUGGESTION = "suggestion"
        const val RULE_LOCK = "lockManaged"
        const val RULE_ADD = "blockAdd"
        const val RULE_CONFIG = "blockConfig"
        const val RULE_STATE = "blockState"
        const val RULE_SSID = "ssidPolicy"
        const val RULE_SECURITY = "minSecurity"
        const val RADIO_ON = "radioOn"
        const val RADIO_OFF = "radioOff"
        private const val MINUTE_MS = 60_000L
        private const val TICK_MS = 60_000L
        private const val HEAL_EVERY_MS = 5 * 60_000L

        /** WifiManager.EXTRA_SUPPLICANT_ERROR_REASON and its values (hidden constants, public behaviour since Android 8). */
        private const val EXTRA_ERROR_REASON = "supplicantErrorReason"
        private const val REASON_TIMEOUT = 1
        private const val REASON_WRONG_PASSWORD = 2
        private const val REASON_EAP_FAILURE = 3
    }
}
