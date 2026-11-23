package com.offlinetutor.data

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.offlinetutor.domain.BattleProtocol
import com.offlinetutor.domain.BattleProtocol.Msg
import com.offlinetutor.domain.BattleScoring
import com.offlinetutor.domain.ClassHeatmap
import com.offlinetutor.domain.QuestionShare
import com.offlinetutor.llm.MemoryProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 5 transport: Google Play services Nearby Connections (Bluetooth / Wi-Fi, no internet). One session at a
 * time (battle host, battle joiner or question share). Hidden when Play services is missing (QR fallback, 5c).
 */
class NearbyTransport(val context: Context) {
    interface Listener {
        fun onFound(endpointId: String, name: String) {}
        fun onLost(endpointId: String) {}
        fun onInitiated(endpointId: String, name: String, digits: String) {}
        fun onConnected(endpointId: String) {}
        fun onDisconnected(endpointId: String) {}
        fun onBytes(endpointId: String, bytes: ByteArray) {}
    }

    var listener: Listener? = null

    fun available(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private val client by lazy { Nearby.getConnectionsClient(context) }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { listener?.onBytes(endpointId, it) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onInitiated(endpointId, info.endpointName, info.authenticationDigits)
            client.acceptConnection(endpointId, payloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) listener?.onConnected(endpointId)
        }
        override fun onDisconnected(endpointId: String) { listener?.onDisconnected(endpointId) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) { listener?.onFound(endpointId, info.endpointName) }
        override fun onEndpointLost(endpointId: String) { listener?.onLost(endpointId) }
    }

    fun advertise(name: String, service: String, strategy: Strategy) {
        client.startAdvertising(name, service, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
    }

    fun discover(service: String, strategy: Strategy) {
        client.startDiscovery(service, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    fun connect(myName: String, endpointId: String) { client.requestConnection(myName, endpointId, lifecycle) }

    fun send(endpointIds: Collection<String>, bytes: ByteArray) {
        if (endpointIds.isNotEmpty()) client.sendPayload(endpointIds.toList(), Payload.fromBytes(bytes))
    }

    fun stop() {
        runCatching { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() }
        listener = null
    }

    companion object {
        const val BATTLE_SERVICE = "com.offlinetutor.battle"
        const val SHARE_SERVICE = "com.offlinetutor.share"
    }
}

/** 5a host: advertises with P2P_STAR, runs rounds, scores, and builds the class misconception heatmap. */
class BattleHost(private val transport: NearbyTransport, private val scope: CoroutineScope, private val ai: TutorAi) {
    enum class Phase { IDLE, LOBBY, ROUND, RESULT, END }

    data class Player(val nickname: String, val score: Int = 0, val joinMs: Long? = null)

    data class State(
        val phase: Phase = Phase.IDLE, val code: String = "", val players: Map<String, Player> = emptyMap(),
        val pending: Map<String, String> = emptyMap(), // endpoint → name (connection initiated, digits shown)
        val questions: List<QuizRepository.BattleItem> = emptyList(), val round: Int = -1, val answered: Int = 0,
        val lastCounts: List<Int> = emptyList(), val rounds: List<ClassHeatmap.Round> = emptyList(), val maxJoiners: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val answers = HashMap<String, Msg.Answer>()
    private var startedAt = 0L
    private var timer: Job? = null

    fun start(nickname: String, questions: List<QuizRepository.BattleItem>) {
        val code = (1000..9999).random().toString()
        startedAt = SystemClock.elapsedRealtime()
        _state.value = State(Phase.LOBBY, code, questions = questions)
        transport.listener = object : NearbyTransport.Listener {
            override fun onInitiated(endpointId: String, name: String, digits: String) =
                _state.update { it.copy(pending = it.pending + (endpointId to "$name · $digits")) }
            override fun onConnected(endpointId: String) {
                val joinMs = SystemClock.elapsedRealtime() - startedAt
                _state.update { s ->
                    val name = s.pending[endpointId]?.substringBefore(" · ") ?: "?"
                    val players = s.players + (endpointId to Player(name, joinMs = joinMs))
                    s.copy(players = players, pending = s.pending - endpointId, maxJoiners = maxOf(s.maxJoiners, players.size))
                }
            }
            override fun onDisconnected(endpointId: String) = _state.update { it.copy(players = it.players - endpointId) }
            override fun onBytes(endpointId: String, bytes: ByteArray) {
                when (val m = BattleProtocol.decode(bytes)) {
                    is Msg.Hello -> _state.update { s -> s.players[endpointId]?.let { p -> s.copy(players = s.players + (endpointId to p.copy(nickname = m.nickname))) } ?: s }
                    is Msg.Answer -> if (m.i == _state.value.round && endpointId !in answers) {
                        answers[endpointId] = m
                        _state.update { it.copy(answered = answers.size) }
                        if (answers.size >= _state.value.players.size) endRound()
                    }
                    else -> Unit
                }
            }
        }
        transport.advertise("$nickname#$code", NearbyTransport.BATTLE_SERVICE, Strategy.P2P_STAR)
    }

    /** Sends the questions (without answer flags) and starts round 1. */
    fun begin() {
        val s = _state.value
        transport.send(s.players.keys, BattleProtocol.encode(Msg.Quiz(s.questions.map { BattleProtocol.BQ(it.stem, it.options) })))
        startRound(0)
    }

    fun startRound(i: Int) {
        answers.clear()
        _state.update { it.copy(phase = Phase.ROUND, round = i, answered = 0) }
        transport.send(_state.value.players.keys, BattleProtocol.encode(Msg.RoundStart(i, ROUND_MS)))
        timer?.cancel()
        timer = scope.launch { delay(ROUND_MS + 1500); if (_state.value.round == i && _state.value.phase == Phase.ROUND) endRound() }
    }

    fun endRound() {
        val s = _state.value
        if (s.phase != Phase.ROUND) return
        timer?.cancel()
        val q = s.questions[s.round]
        val counts = MutableList(q.options.size) { 0 }
        answers.values.forEach { a -> if (a.option in counts.indices) counts[a.option]++ }
        var players = s.players
        for ((id, p) in s.players) {
            val a = answers[id]
            val correct = a?.option == q.answer
            val pts = if (a == null) 0 else BattleScoring.points(correct, a.ms, ROUND_MS)
            players = players + (id to p.copy(score = p.score + pts))
            transport.send(listOf(id), BattleProtocol.encode(Msg.RoundResult(s.round, q.answer, counts, q.codes, pts, a?.let { correct })))
        }
        val board = players.values.sortedByDescending { it.score }.map { it.nickname to it.score }
        transport.send(players.keys, BattleProtocol.encode(Msg.Leaderboard(board)))
        val rounds = s.rounds + ClassHeatmap.Round(answers.values.map { it.option }, q.codes)
        val last = s.round >= s.questions.lastIndex
        _state.update { it.copy(phase = if (last) Phase.END else Phase.RESULT, players = players, lastCounts = counts, rounds = rounds) }
        if (last) finish()
    }

    fun next() { val s = _state.value; if (s.phase == Phase.RESULT) startRound(s.round + 1) }

    private fun finish() {
        val s = _state.value
        transport.send(s.players.keys, BattleProtocol.encode(Msg.End))
        // Phase 7 metrics: join times, joiners, peak RAM while hosting.
        scope.launch {
            ai.logNote("MESH_BATTLE", "mesh-battle-${System.currentTimeMillis()}", JSONObject()
                .put("maxJoiners", s.maxJoiners).put("questions", s.questions.size)
                .put("joinMs", JSONArray(s.players.values.mapNotNull { it.joinMs }))
                .put("peakRssMb", MemoryProbe.read(transport.context).vmHwmKb / 1024)
                .put("heatmap", ClassHeatmap.headline(s.rounds) ?: JSONObject.NULL).toString())
        }
    }

    fun stop() { timer?.cancel(); transport.stop(); _state.value = State() }

    companion object { const val ROUND_MS = 20_000L }
}

/** 5a joiner: needs the app but not the model. */
class BattleClient(private val transport: NearbyTransport) {
    data class Host(val endpointId: String, val name: String) {
        val nickname get() = name.substringBefore("#")
        val code get() = name.substringAfter("#", "")
    }

    data class State(
        val hosts: List<Host> = emptyList(), val connecting: String? = null, val connected: Boolean = false, val digits: String? = null,
        val questions: List<BattleProtocol.BQ> = emptyList(), val round: Int = -1, val roundStartedAt: Long = 0, val deadlineMs: Long = 0,
        val myAnswer: Int? = null, val result: Msg.RoundResult? = null, val board: List<Pair<String, Int>> = emptyList(),
        val ended: Boolean = false, val joinMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var hostId: String? = null
    private var nickname = ""
    private var requestedAt = 0L

    fun discover() {
        _state.value = State()
        transport.listener = object : NearbyTransport.Listener {
            override fun onFound(endpointId: String, name: String) =
                _state.update { s -> s.copy(hosts = (s.hosts.filter { it.endpointId != endpointId } + Host(endpointId, name))) }
            override fun onLost(endpointId: String) = _state.update { s -> s.copy(hosts = s.hosts.filter { it.endpointId != endpointId }) }
            override fun onInitiated(endpointId: String, name: String, digits: String) = _state.update { it.copy(digits = digits) }
            override fun onConnected(endpointId: String) {
                hostId = endpointId
                _state.update { it.copy(connected = true, connecting = null, joinMs = SystemClock.elapsedRealtime() - requestedAt) }
                transport.send(listOf(endpointId), BattleProtocol.encode(Msg.Hello(nickname)))
            }
            override fun onDisconnected(endpointId: String) = _state.update { it.copy(connected = false) }
            override fun onBytes(endpointId: String, bytes: ByteArray) {
                when (val m = BattleProtocol.decode(bytes)) {
                    is Msg.Quiz -> _state.update { it.copy(questions = m.questions) }
                    is Msg.RoundStart -> _state.update { it.copy(round = m.i, roundStartedAt = SystemClock.elapsedRealtime(), deadlineMs = m.deadlineMs, myAnswer = null, result = null) }
                    is Msg.RoundResult -> _state.update { it.copy(result = m) }
                    is Msg.Leaderboard -> _state.update { it.copy(board = m.entries) }
                    Msg.End -> _state.update { it.copy(ended = true) }
                    else -> Unit
                }
            }
        }
        transport.discover(NearbyTransport.BATTLE_SERVICE, Strategy.P2P_STAR)
    }

    fun join(host: Host, nick: String) {
        nickname = nick.take(20)
        requestedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(connecting = host.endpointId) }
        transport.connect(nickname, host.endpointId)
    }

    fun answer(option: Int, confident: Boolean?) {
        val s = _state.value
        val id = hostId ?: return
        if (s.myAnswer != null || s.round < 0) return
        _state.update { it.copy(myAnswer = option) }
        transport.send(listOf(id), BattleProtocol.encode(Msg.Answer(s.round, option, SystemClock.elapsedRealtime() - s.roundStartedAt, confident)))
    }

    fun stop() { transport.stop(); _state.value = State() }
}

/**
 * 5b "Share questions" between nearby app users: both phones advertise and discover; on connect each sends its
 * manifest, asks for what it lacks (capped), and validates what it receives. Counts questions received and the
 * generation time saved, using the measured median time per tagged question on this phone.
 */
class QuestionShareSession(
    private val transport: NearbyTransport, private val scope: CoroutineScope, private val cache: QuestionCache,
    private val ai: TutorAi, private val secondsPerTagged: suspend () -> Double?,
) {
    data class State(val running: Boolean = false, val peers: Int = 0, val received: Int = 0, val llm: Int = 0, val template: Int = 0,
                     val rejected: Int = 0, val sent: Int = 0, val secondsSaved: Double? = null, val note: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(profile: Profile, cap: Int = CAP) {
        _state.value = State(running = true)
        val name = "${profile.name.take(12)}#share"
        transport.listener = object : NearbyTransport.Listener {
            override fun onFound(endpointId: String, name2: String) { transport.connect(name, endpointId) }
            override fun onConnected(endpointId: String) {
                _state.update { it.copy(peers = it.peers + 1) }
                scope.launch {
                    val m = QuestionShare.manifestJson(cache.manifest(profile))
                    transport.send(listOf(endpointId), JSONObject().put("v", 1).put("t", "MANIFEST").put("m", m).toString().toByteArray())
                }
            }
            override fun onBytes(endpointId: String, bytes: ByteArray) {
                val o = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return
                scope.launch {
                    when (o.optString("t")) {
                        "MANIFEST" -> {
                            val want = QuestionShare.wanted(cache.known(profile), QuestionShare.parseManifest(o.getString("m")), profile.grade, cap)
                            transport.send(listOf(endpointId), JSONObject().put("v", 1).put("t", "WANT").put("h", JSONArray(want)).toString().toByteArray())
                        }
                        "WANT" -> {
                            val h = o.getJSONArray("h").let { a -> (0 until a.length()).map { a.getString(it) } }.take(cap)
                            val items = cache.itemsJson(profile, h)
                            val bytes = JSONObject().put("v", 1).put("t", "ITEMS").put("items", items).toString().toByteArray()
                            if (bytes.size < BattleProtocol.MAX_BYTES) {
                                transport.send(listOf(endpointId), bytes)
                                _state.update { it.copy(sent = it.sent + h.size) }
                            }
                        }
                        "ITEMS" -> {
                            val r = cache.receive(profile, o.getString("items"), cap)
                            val per = secondsPerTagged()
                            _state.update { s ->
                                s.copy(received = s.received + r.stored, llm = s.llm + r.llm, template = s.template + r.template,
                                    rejected = s.rejected + r.rejected, secondsSaved = per?.let { (s.llm + r.llm) * it })
                            }
                            val s = _state.value
                            ai.logNote("MESH_SHARE", "mesh-share-${System.currentTimeMillis()}", JSONObject().put("received", s.received)
                                .put("llm", s.llm).put("template", s.template).put("rejected", s.rejected)
                                .put("secondsSaved", s.secondsSaved ?: JSONObject.NULL).toString())
                        }
                    }
                }
            }
        }
        transport.advertise(name, NearbyTransport.SHARE_SERVICE, Strategy.P2P_POINT_TO_POINT)
        transport.discover(NearbyTransport.SHARE_SERVICE, Strategy.P2P_POINT_TO_POINT)
    }

    fun stop() { transport.stop(); _state.update { it.copy(running = false) } }

    companion object { const val CAP = 20 }
}

