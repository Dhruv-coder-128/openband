package com.openband.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.SOURCE_BAND
import com.openband.app.data.Sample
import com.openband.app.health.HealthRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyBand: Boolean)

data class CharInfo(val uuid: UUID, val handle: Int, val props: Int) {
    val propsText: String
        get() = buildList {
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WnR")
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
        }.joinToString("/")
    val writable get() = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
}

data class ServiceInfo(val uuid: UUID, val chars: List<CharInfo>)

data class LiveReading(val kind: LiveKind, val value: Double, val at: Long)

data class BandState(
    val status: String = "Not connected",
    val connected: Boolean = false,
    val ready: Boolean = false,
    val syncing: Boolean = false,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val deviceName: String? = null,
    val address: String? = null,
    val battery: Int? = null,
    val liveHr: Int? = null,
    val liveKind: LiveKind? = null,
    /** The latest value of the measurement in progress, and when the band last refused one. */
    val reading: LiveReading? = null,
    val refusedAt: Long = 0,
    /** What the band last reported for its automatic heart-rate measuring. */
    val hrSetting: HrSetting? = null,
    val protocols: List<String> = emptyList(),
    val info: Map<String, String> = emptyMap(),
    val services: List<ServiceInfo> = emptyList(),
    val lastSync: Long = 0,
    /** Last time live data (today's steps / HR) was refreshed while the app was open. */
    val lastLive: Long = 0,
    /** Another app (the official one) talked to the band recently - we are staying out of its way. */
    val otherApp: Boolean = false,
    val alwaysOn: Boolean = false,
    /** Scan list shows every Bluetooth device instead of only Pebble bands. */
    val scanAll: Boolean = false,
    /** Official app installed but not connected yet - OpenBand waits for it. */
    val waitingOfficial: Boolean = false,
    val soloAllowed: Boolean = false,
    val log: List<String> = emptyList(),
)

/**
 * Single owner of the Bluetooth connection. Kept alive by [BandService].
 * All GATT operations go through a queue ([op]) because Android allows only one at a time.
 */
@SuppressLint("MissingPermission")
object BandManager {
    private const val TAG = "BandManager"
    private val CCCD: UUID = uuid16(0x2902)

    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "background error", e)
        runCatching { log("Error: ${e.javaClass.simpleName}: ${e.message}") }
    })

    /** Runs a callback body without ever letting an exception kill the app. */
    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.e(TAG, what, e); runCatching { log("$what failed: ${e.message}") } }
    }
    val state = MutableStateFlow(BandState())

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray>? = null
    @Volatile private var protocols: List<BandProtocol> = emptyList()
    @Volatile private var userDisconnect = false
    private var reconnectJob: Job? = null
    private var scanStopJob: Job? = null
    private val emitted = ArrayList<Sample>()
    private val db get() = Db.get(ctx)

    fun init(c: Context) {
        ctx = c.applicationContext
        state.update { it.copy(address = savedAddress, deviceName = savedName, alwaysOn = alwaysOn, soloAllowed = soloAllowed) }
    }

    // ------------------------------------------------------------------ sharing the band with the official app
    //
    // The band answers every app connected to it on the same characteristic, so two apps syncing at the
    // same time corrupt each other's data (that is what broke the official app). By default OpenBand
    // therefore only holds the band while it is on screen, plus a short background top-up every 15 min,
    // and backs off as soon as it sees the band answering someone else's request.

    /** Package names of the official band apps. */
    private val OFFICIAL_APPS = listOf("com.pebble.ring.app", "com.pebble.plus")

    /** True if the official app is installed - then OpenBand never opens the band connection itself. */
    fun officialInstalled(): Boolean = OFFICIAL_APPS.any { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /** Is the band already connected to this phone (by the official app)? */
    private fun linkUp(dev: BluetoothDevice): Boolean = runCatching {
        ctx.getSystemService(BluetoothManager::class.java).getConnectionState(dev, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(false)

    /**
     * The official app must always be the one that opens the connection: if OpenBand connects first, the official
     * app's session on top of it is incomplete and its "measure now" button stops working. So with the official
     * app installed OpenBand only joins a connection the official app already made, and never changes anything on
     * the band. [soloAllowed] = the user explicitly chose to use OpenBand without the official app.
     */
    var soloAllowed: Boolean
        get() = prefs.getBoolean("solo", false)
        set(v) { prefs.edit().putBoolean("solo", v).apply(); state.update { it.copy(soloAllowed = v) }; if (v) ensureConnected() }

    private fun mayOpenLink(dev: BluetoothDevice) = soloAllowed || !officialInstalled() || linkUp(dev)
    private var gateJob: Job? = null

    /** Keep a permanent connection. Off by default so the official app keeps working. */
    var alwaysOn: Boolean
        get() = prefs.getBoolean("always_on2", false) && (soloAllowed || !officialInstalled())
        set(v) {
            prefs.edit().putBoolean("always_on2", v).apply()
            state.update { it.copy(alwaysOn = v) }
            if (v) BandService.start(ctx)
            else {
                runCatching { ctx.stopService(Intent(ctx, BandService::class.java)) }
                if (!foreground) releaseSoon()
            }
        }

    @Volatile var foreground = false
        private set
    /** True while a workout runs: the band link (live heart rate) stays up even with the screen off. */
    @Volatile var workoutActive = false
        private set

    fun setWorkoutActive(on: Boolean) = safe("workout") {
        workoutActive = on
        if (on) { releaseJob?.cancel(); ensureConnected() } else if (!foreground && !alwaysOn) releaseSoon()
    }
    /** When the last live heart-rate packet arrived (0 = never). */
    @Volatile var liveHrAt = 0L
        private set
    @Volatile private var foreignAt = 0L
    @Volatile private var eventAt = 0L
    private var liveJob: Job? = null
    private var releaseJob: Job? = null

    private fun otherAppActive(windowMs: Long = 45_000) = System.currentTimeMillis() - foreignAt < windowMs

    /** Called by MainActivity when the app becomes visible / hidden. */
    fun setForeground(on: Boolean) = safe("foreground") {
        foreground = on
        if (on) {
            releaseJob?.cancel()
            ensureConnected()
            startLiveLoop()
        } else {
            if (workoutActive) return@safe
            liveJob?.cancel()
            if (state.value.liveKind != null) stopLive()
            if (!alwaysOn) releaseSoon()
        }
    }

    /** Lets go of the band shortly after the app is hidden, so the official app can use it. */
    private fun releaseSoon(after: Long = 300) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(after)
            // every loop checks shouldYield() (= app not visible) and stops at its next step, so this is a few seconds at most
            withTimeoutOrNull(6_000) { while (state.value.syncing) delay(100) }
            if (foreground || alwaysOn || workoutActive || visiting) return@launch
            runCatching { flush(export = false) }
            disconnect(quiet = true)
        }
    }

    /** While the app is open: refresh today's steps every 15 s and heart rate / HRV / stress every minute. */
    private fun startLiveLoop() {
        liveJob?.cancel()
        liveJob = scope.launch {
            var n = 0
            while (foreground) {
                // band pushed an event -> refresh right away, otherwise every 15 s
                val waitUntil = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < waitUntil && eventAt == 0L) delay(500)
                val pushed = eventAt != 0L
                eventAt = 0L
                val st = state.value
                if (!st.ready || st.syncing || st.liveKind != null) continue
                if (otherAppActive()) { state.update { it.copy(otherApp = true) }; continue }
                if (st.otherApp) state.update { it.copy(otherApp = false) }
                if (!syncMutex.tryLock()) continue
                try {
                    val full = pushed || n % 6 == 0
                    for (p in protocols) runCatching { p.poll(io, full) }
                    flush(export = false)
                    state.update { it.copy(lastLive = System.currentTimeMillis()) }
                } finally { syncMutex.unlock() }
                n++
            }
        }
    }

    // ------------------------------------------------------------------ visits from the background

    /** True while a short background visit is in progress. */
    @Volatile private var visiting = false
    private val visitMutex = Mutex()

    private val userPrefs get() = com.openband.app.ui.RaceSettings.prefs.value

    /**
     * May OpenBand talk to the band while it is not on screen? With the Pebble app installed only when the user switched
     * it on, allowed Usage access, and the Pebble app is not on screen right now.
     */
    fun backgroundAllowed(): Boolean {
        if (savedAddress == null || !hasPermissions()) return false
        if (officialInstalled() && !soloAllowed) {
            if (!userPrefs.bgBand) return false
            if (!OfficialApp.usageGranted(ctx) || OfficialApp.inFront(ctx)) return false
        }
        return true
    }

    /**
     * One short job on the band: join the link (never opening it ahead of the Pebble app), do the work, let go. While the
     * app is on screen the existing connection is used. Returns null when it was not possible or not allowed.
     */
    private suspend fun <T> visit(work: suspend (BandProtocol) -> T): T? = visitMutex.withLock {
        val inUse = { foreground || workoutActive || alwaysOn }
        if (!inUse() && !backgroundAllowed()) return@withLock null
        val dev = savedAddress?.let { adapter?.getRemoteDevice(it) } ?: return@withLock null
        visiting = true
        val wake = ctx.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "openband:visit").apply { acquire(90_000) }
        try {
            if (!state.value.ready) {
                if (gatt == null) {
                    if (!mayOpenLink(dev)) { log("Visit skipped: the Pebble app has no connection to the band"); return@withLock null }
                    connectAttempts = 0
                    ensureConnected()
                }
                withTimeoutOrNull(25_000) { while (!state.value.ready) delay(200) } ?: run { log("Visit failed: the band did not become ready"); return@withLock null }
            }
            if (otherAppActive()) { log("Visit skipped: the Pebble app is talking to the band"); return@withLock null }
            val p = protocols.firstOrNull { it.supportsLive } ?: return@withLock null
            val result = syncMutex.withLock { work(p) }
            flush(export = false)
            result
        } finally {
            visiting = false
            runCatching { if (wake.isHeld) wake.release() }
            if (!inUse()) disconnect(quiet = true)
        }
    }

    /** Background top-up: today's readings, or a normal sync when [full]. True if the band was read. */
    suspend fun backgroundRead(full: Boolean): Boolean {
        if (foreground) { log("Background read not needed: the app is open"); return false }
        if (!backgroundAllowed()) {
            log("Background read skipped: " + when {
                savedAddress == null -> "no band paired"
                !hasPermissions() -> "no Bluetooth permission"
                officialInstalled() && !soloAllowed && !userPrefs.bgBand -> "switched off"
                officialInstalled() && !soloAllowed && !OfficialApp.usageGranted(ctx) -> "no Usage access"
                else -> "the Pebble app is on screen"
            })
            return false
        }
        return visit { p ->
            if (full) {
                currentDays = 2
                for (d in protocols) runCatching { d.sync(io) }.onFailure { log("${d.name} sync: ${it.message}") }
            } else p.quickRead(io)
            keepHrInterval(p)
            if (!io.shouldYield()) {
                state.update { it.copy(lastSync = System.currentTimeMillis()) }
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            }
            true
        } == true
    }

    /** Used by [com.openband.app.sync.SyncWorker] when the always-on service is not doing this already. */
    suspend fun backgroundSync() {
        if (alwaysOn) { if (state.value.ready) sync() else ensureConnected(); return }
        if (System.currentTimeMillis() - prefs.getLong("last_sync", 0) < 12 * 60_000L) return
        backgroundRead(full = true)
    }

    /** The Pebble app writes its own interval when it connects: put the chosen one back. */
    private suspend fun keepHrInterval(p: BandProtocol) {
        val want = userPrefs.hrInterval
        if (want <= 0 || io.shouldYield()) return
        p.writeHrSetting(io, want)?.let { r -> state.update { it.copy(hrSetting = r) } }
    }

    /** The user chose an interval: set it on the band now. Returns what the band reports, or null if it could not be reached. */
    suspend fun setHrInterval(minutes: Int): HrSetting? = visit { p ->
        p.writeHrSetting(io, minutes)?.also { r -> state.update { it.copy(hrSetting = r) } }
    }

    suspend fun readHrInterval(): HrSetting? = visit { p ->
        p.readHrSetting(io)?.also { r -> state.update { it.copy(hrSetting = r) } }
    }

    /** Makes the band vibrate. Skipped while the band is busy measuring or the Pebble app is on screen. */
    suspend fun alert(steps: List<com.openband.app.notify.Buzz.Step>): Boolean {
        if (state.value.liveKind != null && !workoutActive) return false
        return visit { p ->
            var ok = true
            for (step in steps) {
                if (step.pauseMs > 0) delay(step.pauseMs)
                // a ringing band is always told to stop
                if (io.shouldYield() && !com.openband.app.notify.Buzz.stops(step)) { ok = false; continue }
                ok = (if (step.messageType == null) p.alertFind(io) else p.alertMessage(io, step.messageType, step.label)) && ok
            }
            ok
        } == true
    }

    private val prefs get() = ctx.getSharedPreferences("band", Context.MODE_PRIVATE)
    var savedAddress: String?
        get() = prefs.getString("address", null)
        private set(v) = prefs.edit().putString("address", v).apply()
    var savedName: String?
        get() = prefs.getString("name", null)
        private set(v) = prefs.edit().putString("name", v).apply()

    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermissions(c: Context = ctx): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + msg
        state.update { it.copy(log = (listOf(line) + it.log).take(200)) }
    }

    private fun status(s: String) = state.update { it.copy(status = s) }

    // ------------------------------------------------------------------ scanning

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = safe("scanResult") {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return@safe
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            // a Pebble / Qore band: by name, or by the QRing service it advertises
            val likely = Regex("pbl|qore|pebble", RegexOption.IGNORE_CASE).containsMatchIn(name) || QRingProtocol.SERVICE in uuids
            if (!likely && !state.value.scanAll) return@safe
            val d = FoundDevice(name, result.device.address, result.rssi, likely)
            state.update { s ->
                val list = (s.found.filter { it.address != d.address } + d)
                    .sortedWith(compareByDescending<FoundDevice> { it.likelyBand }.thenByDescending { it.rssi })
                s.copy(found = list)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: $errorCode")
            state.update { it.copy(scanning = false) }
        }
    }

    fun startScan() = safe("scan") {
        if (!hasPermissions()) { log("Bluetooth permission missing"); return@safe }
        if (adapter?.isEnabled != true) { log("Turn Bluetooth ON first"); state.update { it.copy(status = "Bluetooth is off") }; return@safe }
        val scanner = adapter?.bluetoothLeScanner ?: run { log("Bluetooth is off"); return@safe }
        state.update { it.copy(found = emptyList(), scanning = true) }
        // bands already bonded to the phone often do not advertise: list bonded devices too
        adapter?.bondedDevices?.forEach { d ->
            val n = d.name ?: return@forEach
            val pebble = Regex("pbl|qore|pebble", RegexOption.IGNORE_CASE).containsMatchIn(n)
            if (pebble || state.value.scanAll) state.update { s -> s.copy(found = s.found + FoundDevice("$n (paired)", d.address, -100, pebble)) }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        scanStopJob?.cancel()
        scanStopJob = scope.launch { delay(15_000); stopScan() }
    }

    fun setScanAll(on: Boolean) { state.update { it.copy(scanAll = on) }; startScan() }

    fun stopScan() {
        if (!::ctx.isInitialized) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        state.update { it.copy(scanning = false) }
    }

    // ------------------------------------------------------------------ connection

    /** Choose a band and connect (persisted - the app reconnects automatically from then on). */
    fun pair(address: String, name: String) {
        stopScan()
        savedAddress = address
        savedName = name.removeSuffix(" (paired)")
        state.update { it.copy(address = address, deviceName = savedName) }
        connectAttempts = 0
        ensureConnected()
        BandService.start(ctx)
    }

    fun forget() {
        disconnect()
        savedAddress = null
        savedName = null
        state.update { BandState() }
        ctx.stopService(Intent(ctx, BandService::class.java))
    }

    /** Called by the service. Connects if a band is saved and nothing is connected. */
    fun ensureConnected(): Unit = safe("connect") {
        val addr = savedAddress ?: return@safe
        if (!hasPermissions()) { status("Bluetooth permission missing"); return@safe }
        if (gatt != null) return@safe
        if (adapter?.isEnabled != true) { status("Bluetooth is off"); scheduleReconnect(); return@safe }
        userDisconnect = false
        val dev: BluetoothDevice = adapter?.getRemoteDevice(addr) ?: return@safe
        if (!mayOpenLink(dev)) {
            // wait until the official app has connected the band, then join its connection
            status("Waiting for the Pebble app to connect the band")
            state.update { it.copy(waitingOfficial = true) }
            gateJob?.cancel()
            gateJob = scope.launch {
                while (gatt == null && (foreground || alwaysOn || workoutActive) && !mayOpenLink(dev)) delay(5_000)
                if (gatt == null && (foreground || alwaysOn || workoutActive)) ensureConnected()
            }
            return@safe
        }
        state.update { it.copy(waitingOfficial = false) }
        // first attempts connect directly (fast); afterwards autoConnect=true lets Android
        // reconnect by itself whenever the band comes back into range
        val auto = false // never let Android open the link on its own (the official app must connect first)
        connectAttempts++
        status(if (auto) "Waiting for band…" else "Connecting…")
        log("Connecting to $addr (attempt $connectAttempts, auto=$auto, bond=${dev.bondState})")
        gatt = dev.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE)
    }

    @Volatile private var connectAttempts = 0
    private var discoveryJob: Job? = null

    /** Clears Android's cached service table for this device (hidden API, best effort). */
    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            .onSuccess { log("GATT cache cleared") }
    }

    /** discoverServices() sometimes never answers on some phones: retry, clear cache, then reconnect. */
    private fun startDiscovery(g: BluetoothGatt) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            for (attempt in 1..3) {
                delay(if (attempt == 1) 800 else 1500)
                if (attempt > 1) refreshCache(g)
                status(if (attempt == 1) "Discovering services…" else "Discovering services (retry $attempt)…")
                if (!g.discoverServices()) log("discoverServices() refused")
                delay(12_000)
                if (state.value.services.isNotEmpty()) return@launch
                log("No service list after 12 s (try $attempt)")
            }
            log("Service discovery failed - reconnecting")
            runCatching { g.disconnect(); g.close() }
            if (gatt == g) gatt = null
            state.update { it.copy(connected = false, ready = false) }
            scheduleReconnect(3_000)
        }
    }

    fun disconnect(quiet: Boolean = false) = safe("disconnect") {
        userDisconnect = true
        reconnectJob?.cancel()
        discoveryJob?.cancel()
        gateJob?.cancel()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        pending?.cancel()
        if (quiet) log("Band released - the official app can use it now")
        state.update { it.copy(connected = false, ready = false, status = if (quiet) "Idle · shared" else "Disconnected", liveHr = null, liveKind = null) }
    }

    private fun scheduleReconnect(after: Long = 10_000) {
        if (userDisconnect || savedAddress == null) return
        if (!alwaysOn && !foreground && !workoutActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(after)
            if (gatt == null) ensureConnected()
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) = safe("connection") {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected (status $st)")
                connectAttempts = 0
                state.update { it.copy(connected = true, services = emptyList(), status = "Discovering services…") }
                startDiscovery(g)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $st)")
                discoveryJob?.cancel()
                pending?.cancel()
                runCatching { g.close() }
                if (gatt == g) gatt = null
                state.update { it.copy(connected = false, ready = false, liveHr = null, liveKind = null, status = if (userDisconnect) "Disconnected" else "Waiting for band…") }
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) = safe("services") {
            log("Services discovered (status $st, ${g.services?.size ?: 0} services)")
            if (st != BluetoothGatt.GATT_SUCCESS || g.services.isNullOrEmpty()) return@safe // watchdog retries
            discoveryJob?.cancel()
            scope.launch { setup(g) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            log("MTU $mtu")
            pending?.complete(ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            pending?.complete(ByteArray(0))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            pending?.complete(ByteArray(0))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            pending?.complete(if (st == BluetoothGatt.GATT_SUCCESS) value else ByteArray(0))
        }

        @Deprecated("pre-33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                pending?.complete(if (st == BluetoothGatt.GATT_SUCCESS) (c.value ?: ByteArray(0)) else ByteArray(0))
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = safe("notify") {
            onNotify(c, value)
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                onNotify(c, c.value ?: return)
            }
        }
    }

    private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        scope.launch(Dispatchers.IO) { db.insertRaw("RX", c.instanceId, c.uuid.toString(), value.hex()) }
        for (p in protocols) runCatching { p.onNotify(io, c.uuid, value) }.onFailure { Log.w(TAG, "notify ${p.name}", it) }
    }

    /** Runs one GATT operation and waits for its callback. Returns null on failure/timeout. */
    private suspend fun op(timeoutMs: Long = 5000, start: (BluetoothGatt) -> Boolean): ByteArray? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        val d = CompletableDeferred<ByteArray>()
        pending = d
        val started = try { start(g) } catch (e: Exception) { Log.w(TAG, "op", e); false }
        val r = if (started) withTimeoutOrNull(timeoutMs) { runCatching { d.await() }.getOrNull() } else null
        pending = null
        r
    }

    private fun allChars(): List<BluetoothGattCharacteristic> =
        gatt?.services?.flatMap { it.characteristics } ?: emptyList()

    private fun findChar(uuid: UUID) = allChars().firstOrNull { it.uuid == uuid }

    private suspend fun writeChar(c: BluetoothGattCharacteristic, data: ByteArray, noResponse: Boolean?): Boolean {
        val nr = noResponse ?: (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0)
        val type = if (nr) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = op { g ->
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
            }
        } != null
        scope.launch(Dispatchers.IO) { db.insertRaw(if (ok) "TX" else "TX_FAIL", c.instanceId, c.uuid.toString(), data.hex()) }
        return ok
    }

    private suspend fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return false
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return op { gg ->
            if (Build.VERSION.SDK_INT >= 33) gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                run { d.value = v; gg.writeDescriptor(d) }
            }
        } != null
    }

    val io: BandIo = object : BandIo {
        override suspend fun write(char: UUID, data: ByteArray, noResponse: Boolean?): Boolean {
            val c = findChar(char) ?: return false
            return writeChar(c, data, noResponse)
        }

        override suspend fun writeHandle(handle: Int, data: ByteArray): Boolean {
            val c = allChars().firstOrNull { it.instanceId == handle }
                // the snoop log records the VALUE handle; instanceId is usually the same, sometimes off by one
                ?: allChars().firstOrNull { it.instanceId == handle - 1 }
                ?: return false
            return writeChar(c, data, null)
        }

        override suspend fun read(char: UUID): ByteArray? {
            val c = findChar(char) ?: return null
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return null
            return op { it.readCharacteristic(c) }?.takeIf { it.isNotEmpty() }
        }

        override fun hasChar(char: UUID) = findChar(char) != null

        override fun emit(type: String, value: Double, ts: Long, endTs: Long) {
            val s = Sample(ts, type, value, SOURCE_BAND, endTs)
            synchronized(emitted) { emitted += s }
        }

        override fun setLiveHr(bpm: Int?) { if (bpm != null) liveHrAt = System.currentTimeMillis(); state.update { it.copy(liveHr = bpm) } }

        override fun setBattery(level: Int) {
            if (level !in 0..100) return
            state.update { it.copy(battery = level) }
            emit(M.BATTERY, level.toDouble())
        }

        override fun setInfo(key: String, value: String) = state.update { it.copy(info = it.info + (key to value)) }

        override fun log(msg: String) = BandManager.log(msg)

        override val historyDays: Int get() = currentDays

        /** Stop talking to the band the moment OpenBand leaves the screen (unless "always connected"), or the Pebble app is active. */
        override fun shouldYield() = (!foreground && !alwaysOn && !workoutActive && !visiting) || otherAppActive() ||
            (visiting && !foreground && officialInstalled() && !soloAllowed && OfficialApp.inFront(ctx))

        override fun liveReading(kind: LiveKind, value: Double) {
            state.update { it.copy(reading = LiveReading(kind, value, System.currentTimeMillis())) }
        }

        override fun liveFailed(kind: LiveKind) { state.update { it.copy(refusedAt = System.currentTimeMillis()) } }

        override fun foreignTraffic(what: String) {
            val first = !otherAppActive()
            foreignAt = System.currentTimeMillis()
            if (first) {
                log("Another app is talking to the band ($what) - OpenBand steps aside")
                state.update { it.copy(otherApp = true) }
            }
        }

        override fun bandEvent() { eventAt = System.currentTimeMillis() }
    }

    @Volatile private var currentDays = 7

    private suspend fun setup(g: BluetoothGatt) {
        val services = g.services ?: emptyList()
        state.update { s ->
            s.copy(services = services.map { sv -> ServiceInfo(sv.uuid, sv.characteristics.map { CharInfo(it.uuid, it.instanceId, it.properties) }) })
        }
        log("Found ${services.size} services: " + services.joinToString { it.uuid.toString().substring(0, 8) })
        status("Setting up link…")
        val joined = officialInstalled() && !soloAllowed
        // when we joined the official app's connection, it already set the MTU - leave the link exactly as it is
        if (!joined && op(3000) { it.requestMtu(247) } == null) log("MTU request ignored")

        // Only the two channels OpenBand reads from. Other services (HID, the vendor's ae** channels...) are
        // never touched, so the band and the official app behave exactly as before.
        val wanted = setOf(QRingProtocol.NOTIFY, QRingProtocol.BIG_NOTIFY)
        val known = (g.services ?: emptyList()).any { it.uuid == QRingProtocol.SERVICE }
        val protectedServices = setOf(uuid16(0x1812), uuid16(0x1800), uuid16(0x1801))
        val notifyChars = (g.services ?: emptyList()).filter { it.uuid !in protectedServices }.flatMap { it.characteristics }.filter {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (!known || it.uuid in wanted)
        }
        var n = 0
        notifyChars.forEachIndexed { i, c ->
            status("Subscribing ${i + 1}/${notifyChars.size}…")
            val ok = try { enableNotify(c) } catch (e: SecurityException) { false }
            if (ok) n++ else log("Subscribe skipped: ${c.uuid.toString().substring(0, 8)} (0x%04x)".format(c.instanceId))
        }
        log("Subscribed to $n of ${notifyChars.size} characteristics")

        val vendor = listOf(QRingProtocol(), LearnedProtocol(db)).filter { it.matches(services) }
        protocols = listOf(StandardProtocol()) + vendor
        state.update { it.copy(protocols = protocols.map { p -> p.name }, ready = true, status = "Connected") }
        if (vendor.isEmpty()) log("No built-in protocol for this band yet - use the Lab tab (snoop import) to teach it")
        for (p in protocols) runCatching { p.onReady(io) }.onFailure { log("${p.name} init: ${it.message}") }
        // a background visit does its own, smaller piece of work
        if (visiting && !foreground && !alwaysOn && !workoutActive) return
        sync()
    }

    // ------------------------------------------------------------------ sync & live

    private val syncMutex = Mutex()

    fun requestSync() {
        scope.launch { sync() }
    }

    suspend fun sync() {
        if (!state.value.ready) return
        if (!syncMutex.tryLock()) return
        try {
            // full 7-day read twice a day, otherwise just today + yesterday (short, so we rarely collide with the official app)
            val full = System.currentTimeMillis() - prefs.getLong("last_full", 0) > 12 * 3_600_000L
            currentDays = if (full) 7 else 2
            state.update { it.copy(syncing = true, status = "Syncing…") }
            for (p in protocols) runCatching { p.sync(io) }.onFailure { log("${p.name} sync: ${it.message}") }
            protocols.firstOrNull { it.supportsLive }?.let { runCatching { keepHrInterval(it) } }
            flush()
            state.update { it.copy(lastSync = System.currentTimeMillis()) }
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            if (full && !io.shouldYield()) prefs.edit().putLong("last_full", System.currentTimeMillis()).apply()
            log("Sync finished")
        } finally {
            state.update { it.copy(syncing = false, status = if (it.connected) "Connected" else it.status) }
            syncMutex.unlock()
        }
    }

    /** Saves buffered band samples to the DB and (optionally) Health Connect. Also called periodically for live data. */
    suspend fun flush(export: Boolean = true) {
        val batch = synchronized(emitted) { ArrayList(emitted).also { emitted.clear() } }
        if (batch.isEmpty()) return
        db.insertSamples(batch)
        db.trimRaw()
        runCatching { com.openband.app.widget.WidgetUpdater.request(ctx) }
        if (!export) return
        val written = runCatching { HealthRepo(ctx).exportBand(batch) }.getOrDefault(0)
        log("Saved ${batch.size} samples" + if (written > 0) ", $written written to Health Connect" else "")
    }

    /** One quick read of today's steps (and heart-rate / HRV / stress when [full]) - used by workouts. */
    suspend fun pollNow(full: Boolean = false) {
        if (!state.value.ready) return
        if (!syncMutex.tryLock()) return
        try {
            for (p in protocols) runCatching { p.poll(io, full) }
            flush(export = false)
        } finally { syncMutex.unlock() }
    }

    /** [soft] = just send the start command again without stopping first (keeps the sensor warm). */
    fun startLive(kind: LiveKind, soft: Boolean = false) = scope.launch {
        val p = protocols.firstOrNull { it.supportsLive }
        if (p == null) { log("Live measurement not supported by this protocol (standard HR is shown automatically if the band broadcasts it)"); return@launch }
        if (!(soft && state.value.liveKind == kind)) p.stopLive(io)
        if (!soft) state.update { it.copy(reading = null, refusedAt = 0) }
        p.startLive(io, kind)
        state.update { it.copy(liveKind = kind) }
    }

    fun stopLive() = scope.launch {
        protocols.firstOrNull { it.supportsLive }?.stopLive(io)
        state.update { it.copy(liveKind = null) }
        flush()
    }

    /** Lab: raw write to any characteristic. */
    fun rawWrite(handle: Int, hex: String) = scope.launch {
        val data = runCatching { hexToBytes(hex) }.getOrElse { log("Bad hex: ${it.message}"); return@launch }
        val ok = io.writeHandle(handle, data)
        log("Write 0x%04x %s -> %s".format(handle, data.hex(), if (ok) "ok" else "FAILED"))
    }

    fun rawRead(handle: Int) = scope.launch {
        val c = allChars().firstOrNull { it.instanceId == handle } ?: return@launch
        val v = op { it.readCharacteristic(c) }
        log("Read 0x%04x -> %s".format(handle, v?.hex() ?: "FAILED"))
        if (v != null) db.insertRaw("READ", handle, c.uuid.toString(), v.hex())
    }
}
