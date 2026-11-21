package com.enhikyy.girassol.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol.FrameType
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

data class MeshPeer(val deviceId: String, val name: String, val host: String, val port: Int, val via: String, val seenMs: Long)

data class MeshState(
    val running: Boolean = false,
    val deviceId: String = "",
    val port: Int = 0,
    val wifiLockUntilMs: Long = 0,
    val peers: List<MeshPeer> = emptyList(),
    val log: List<String> = emptyList(),
    val received: List<String> = emptyList(),
    val lastBytesSent: Long = 0,
    val lastBytesSaved: Long = 0
)

/**
 * MeshSync LAN (Processo 19) — dois aparelhos na mesma rede Wi-Fi, sem internet.
 *
 * Descoberta dupla: NSD/mDNS (`_girassol._tcp`) e beacon UDP em broadcast na porta 47653
 * (redes que filtram mDNS). Transporte TCP com o [MeshSyncProtocol.Session]: desafio-resposta
 * HMAC-SHA256 sobre a chave PBKDF2 do PIN e HMAC por quadro com sequência anti-replay.
 * Enquanto a malha está ativa um `WifiLock` (baixa latência) e um `MulticastLock` ficam presos
 * por no máximo [LOCK_TIMEOUT_MS]; ao expirar, a malha é encerrada sozinha (bateria).
 */
object MeshSyncManager {
    private const val TAG = "GirassolMesh"
    const val LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    private const val BEACON_EVERY_MS = 2_000L
    private const val PEER_TTL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private var appCtx: Context? = null
    private var key: ByteArray? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var jobs = mutableListOf<Job>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())
    private val lockTimeout = Runnable { log("WifiLock expirou (${LOCK_TIMEOUT_MS / 60000} min): malha encerrada"); stop() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update { it.copy(log = (listOf(msg) + it.log).take(30)) }
    }

    fun deviceId(context: Context): String {
        val p = context.getSharedPreferences("girassol_mesh", Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().take(12).also { p.edit().putString("device_id", it).apply() }
    }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".take(40)
    private fun inbox(context: Context) = File(context.filesDir, "mesh").apply { mkdirs() }

    @Synchronized
    fun start(context: Context, pin: String) {
        if (_state.value.running) return
        require(pin.length >= 6) { "PIN de pareamento precisa de ao menos 6 dígitos" }
        val app = context.applicationContext
        appCtx = app
        key = MeshSyncProtocol.deriveKey(pin)
        val id = deviceId(app)
        val wm = app.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "girassol:meshsync").apply { setReferenceCounted(false); acquire() }
        mcastLock = wm.createMulticastLock("girassol:meshsync-beacon").apply { setReferenceCounted(false); acquire() }
        main.postDelayed(lockTimeout, LOCK_TIMEOUT_MS)
        val ss = ServerSocket(0).also { server = it }
        _state.update { it.copy(running = true, deviceId = id, port = ss.localPort, wifiLockUntilMs = System.currentTimeMillis() + LOCK_TIMEOUT_MS) }
        log("Malha ativa: $id na porta TCP ${ss.localPort} (WifiLock por ${LOCK_TIMEOUT_MS / 60000} min)")
        jobs += scope.launch { acceptLoop(ss) }
        jobs += scope.launch { beaconLoop(id, ss.localPort) }
        jobs += scope.launch { while (isActive) { delay(5_000); prunePeers() } }
        registerNsd(app, id, ss.localPort)
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(lockTimeout)
        jobs.forEach { it.cancel() }; jobs.clear()
        runCatching { server?.close() }; server = null
        runCatching { udp?.close() }; udp = null
        runCatching { regListener?.let { nsd?.unregisterService(it) } }; regListener = null
        runCatching { discListener?.let { nsd?.stopServiceDiscovery(it) } }; discListener = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }; wifiLock = null
        runCatching { if (mcastLock?.isHeld == true) mcastLock?.release() }; mcastLock = null
        if (_state.value.running) log("Malha encerrada; WifiLock liberado")
        _state.update { it.copy(running = false, peers = emptyList(), wifiLockUntilMs = 0) }
    }

    // ------------------------------------------------------------------ descoberta

    private fun addPeer(p: MeshPeer) {
        if (p.deviceId == _state.value.deviceId) return
        _state.update { s -> s.copy(peers = (s.peers.filter { it.deviceId != p.deviceId } + p).sortedBy { it.name }) }
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        _state.update { s -> s.copy(peers = s.peers.filter { now - it.seenMs < PEER_TTL_MS || it.via == "nsd" }) }
    }

    private suspend fun beaconLoop(id: String, port: Int) {
        val sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(MeshSyncProtocol.BEACON_PORT)) }
        udp = sock
        sock.soTimeout = BEACON_EVERY_MS.toInt()
        val payload = MeshSyncProtocol.beacon(id, port, deviceName())
        val bcast = InetAddress.getByName("255.255.255.255")
        val buf = ByteArray(512)
        var last = 0L
        while (currentCoroutineContextActive()) {
            val now = System.currentTimeMillis()
            if (now - last >= BEACON_EVERY_MS) {
                runCatching { sock.send(DatagramPacket(payload, payload.size, bcast, MeshSyncProtocol.BEACON_PORT)) }
                last = now
            }
            val pk = DatagramPacket(buf, buf.size)
            val got = runCatching { sock.receive(pk); true }.getOrDefault(false)
            if (!got) { if (sock.isClosed) return; continue }
            MeshSyncProtocol.parseBeacon(pk.data, pk.length)?.let { (pid, pport, pname) ->
                addPeer(MeshPeer(pid, pname, pk.address.hostAddress ?: return@let, pport, "udp", System.currentTimeMillis()))
            }
        }
    }

    private fun currentCoroutineContextActive() = udp?.isClosed == false

    @Suppress("DEPRECATION")
    private fun registerNsd(context: Context, id: String, port: Int) {
        val mgr = context.getSystemService(NsdManager::class.java) ?: return
        nsd = mgr
        val info = NsdServiceInfo().apply { serviceName = "girassol-$id"; serviceType = MeshSyncProtocol.SERVICE_TYPE; this.port = port }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) = log("NSD registrado: ${s.serviceName}")
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) = log("NSD: registro falhou ($e); seguindo só com beacon UDP")
            override fun onServiceUnregistered(s: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) = Unit
        }
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener) }
        discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = log("NSD: descoberta falhou ($e)")
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
            override fun onServiceLost(s: NsdServiceInfo) {
                val pid = s.serviceName.removePrefix("girassol-")
                _state.update { st -> st.copy(peers = st.peers.filterNot { it.deviceId == pid && it.via == "nsd" }) }
            }
            override fun onServiceFound(s: NsdServiceInfo) {
                if (!s.serviceName.startsWith("girassol-") || s.serviceName == "girassol-$id") return
                runCatching {
                    mgr.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) = Unit
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host?.hostAddress ?: return
                            addPeer(MeshPeer(si.serviceName.removePrefix("girassol-"), si.serviceName, host, si.port, "nsd", System.currentTimeMillis()))
                        }
                    })
                }
            }
        }
        runCatching { mgr.discoverServices(MeshSyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener) }
    }

    // ------------------------------------------------------------------ servidor

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch { runCatching { serve(s) }.onFailure { log("Sessão recebida falhou: ${it.message}") }; runCatching { s.close() } }
        }
    }

    private suspend fun serve(sock: Socket) {
        val ctx = appCtx ?: return
        sock.soTimeout = 60_000
        val session = MeshSyncProtocol.Session(sock.getInputStream(), sock.getOutputStream(), key ?: return, deviceId(ctx), initiator = false)
        session.handshake()
        log("Par autenticado (HMAC-SHA256): ${session.peerId}")
        while (true) {
            val (type, payload) = session.receive()
            when (type) {
                FrameType.MANIFEST -> {
                    val manifest = MeshSyncProtocol.decodeManifest(payload)
                    val base = baseFor(ctx, session.peerId)
                    val want = MeshSyncProtocol.missing(manifest, base)
                    session.send(FrameType.WANT, MeshSyncProtocol.encodeWant(MeshSyncProtocol.Want(want)))
                    val blocks = session.expect(FrameType.BLOCKS) { MeshSyncProtocol.decodeBlocks(it) }
                    val grs = MeshSyncProtocol.assemble(manifest, base, blocks)
                    val f = File(inbox(ctx), "${session.peerId}-t${manifest.tick}.grs")
                    f.writeBytes(grs)
                    File(inbox(ctx), "${session.peerId}.raw").writeBytes(WorldSnapshotCodec.encodeRaw(WorldSnapshotCodec.decode(grs)))
                    session.send(FrameType.SNAPSHOT_OK, f.name.toByteArray())
                    log("Snapshot .grs de ${session.peerId} (tick ${manifest.tick}): ${want.size}/${manifest.chunks.size} blocos recebidos, SHA-256 ok")
                    _state.update { it.copy(received = inbox(ctx).listFiles { x -> x.name.endsWith(".grs") }?.map { it.name }?.sorted() ?: emptyList()) }
                }
                FrameType.MIGRANT -> {
                    val packet = MeshSyncProtocol.decodeMigrant(payload)
                    val r = SimulationHub.admitMigrant(packet)
                    session.send(FrameType.MIGRANT_ACK, r.toByteArray())
                    log("Migrante recebido de ${packet.originDevice}: $r")
                }
                FrameType.BYE -> return
                else -> session.send(FrameType.ERROR, "quadro $type inesperado".toByteArray())
            }
        }
    }

    private suspend fun baseFor(ctx: Context, peerId: String): ByteArray? =
        File(inbox(ctx), "$peerId.raw").takeIf { it.exists() }?.readBytes()
            ?: SimulationHub.rawSnapshot()?.first

    // ------------------------------------------------------------------ cliente

    private suspend fun <T> withPeer(peer: MeshPeer, block: suspend (MeshSyncProtocol.Session) -> T): T = withContext(Dispatchers.IO) {
        val ctx = appCtx ?: error("malha inativa")
        Socket().use { s ->
            s.connect(InetSocketAddress(peer.host, peer.port), 5_000)
            s.soTimeout = 60_000
            val session = MeshSyncProtocol.Session(s.getInputStream(), s.getOutputStream(), key ?: error("malha inativa"), deviceId(ctx), initiator = true)
            session.handshake()
            val r = block(session)
            runCatching { session.send(FrameType.BYE, ByteArray(0)) }
            r
        }
    }

    /** Envia o snapshot diferencial do mundo (só blocos que o par ainda não tem). */
    suspend fun pushSnapshot(peer: MeshPeer): String = runCatching {
        val (raw, seed, tick) = SimulationHub.rawSnapshot() ?: error("mundo não carregado")
        val manifest = MeshSyncProtocol.manifestOf(raw, seed, tick)
        withPeer(peer) { s ->
            s.send(FrameType.MANIFEST, MeshSyncProtocol.encodeFrame(manifest))
            val want = s.expect(FrameType.WANT) { MeshSyncProtocol.decodeWant(it) }
            val blocks = MeshSyncProtocol.packBlocks(raw, manifest, want.hashes)
            s.send(FrameType.BLOCKS, MeshSyncProtocol.encodeBlocks(blocks))
            val ok = s.expect(FrameType.SNAPSHOT_OK) { String(it) }
            _state.update { it.copy(lastBytesSent = blocks.gz.size.toLong(), lastBytesSaved = (raw.size - blocks.gz.size).toLong().coerceAtLeast(0)) }
            "Snapshot enviado a ${peer.name}: ${want.hashes.size}/${manifest.chunks.size} blocos · ${blocks.gz.size / 1024} KB ($ok)"
        }
    }.getOrElse { "MeshSync falhou: ${it.message}" }.also { log(it) }

    /** Migra um cidadão (com genoma, memórias e saldos) para o par; desfaz se o envio falhar. */
    suspend fun migrate(peer: MeshPeer, citizenId: String): String {
        val ctx = appCtx ?: return "malha inativa"
        val packet = SimulationHub.emigrate(citizenId, deviceId(ctx)) ?: return "cidadão $citizenId indisponível"
        return runCatching {
            withPeer(peer) { s ->
                s.send(FrameType.MIGRANT, MeshSyncProtocol.encodeMigrant(packet))
                "Migração: ${packet.citizen.name} → ${peer.name} (${s.expect(FrameType.MIGRANT_ACK) { String(it) }})"
            }
        }.getOrElse {
            val back = SimulationHub.admitMigrant(packet)
            "Migração falhou (${it.message}); ${packet.citizen.name} permaneceu: $back"
        }.also { log(it) }
    }

    /** Importa um snapshot recebido (substitui o mundo local após validar o SHA-256 do GRS). */
    suspend fun importReceived(name: String): String {
        val ctx = appCtx ?: return "malha inativa"
        val f = File(inbox(ctx), name).takeIf { it.exists() } ?: return "arquivo não encontrado"
        return SimulationHub.importSnapshot(f.readBytes())
    }

    fun refreshInbox(context: Context) {
        _state.update { it.copy(received = inbox(context).listFiles { x -> x.name.endsWith(".grs") }?.map { it.name }?.sorted() ?: emptyList()) }
    }
}
