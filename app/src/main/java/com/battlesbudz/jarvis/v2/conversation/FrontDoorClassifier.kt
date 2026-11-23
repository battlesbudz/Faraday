package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.memory.EmbeddingEngineUnavailable
import com.battlesbudz.jarvis.v2.memory.MemoryEmbeddingService
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Keeps the exact training window construction in one place so routing and scoring agree. */
internal object FrontDoorInput {
    fun build(previousUser: String?, current: String): String =
        if (previousUser == null) current else "$previousUser\n$current"
}

internal data class FrontDoorDecision(
    val label: String,
    val pAct: Double,
    val pRead: Double,
    val pChat: Double,
    val toolsOffered: Boolean,
    val actIntended: Boolean,
    val discussionConfident: Boolean,
    val vetoConfident: Boolean,
    val windowUpgraded: Boolean,
)

/** Immutable linear head; its Double math matches the training script's softmax and thresholds. */
internal class FrontDoorHead(source: InputStream) {
    private val weights: Array<DoubleArray>
    private val bias: DoubleArray
    private val offer: Double
    private val act: Double
    private val discussion: Double
    private val veto: Double

    init {
        val json = source.use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
        require(json.optInt("format") == 1 && json.optInt("dim") == DIMENSION)
        require((0 until CLASS_COUNT).map { json.getJSONArray("classes").getString(it) } == CLASSES)
        weights = Array(CLASS_COUNT) { row ->
            val values = json.getJSONArray("weights").getJSONArray(row)
            require(values.length() == DIMENSION)
            DoubleArray(DIMENSION) { values.getDouble(it) }
        }
        val biasJson = json.getJSONArray("bias")
        require(biasJson.length() == CLASS_COUNT)
        bias = DoubleArray(CLASS_COUNT) { biasJson.getDouble(it) }
        val thresholds = json.getJSONObject("thresholds")
        offer = thresholds.getDouble("offer")
        act = thresholds.getDouble("act")
        discussion = thresholds.getDouble("discussion")
        veto = thresholds.getDouble("veto")
    }

    fun probs(embedding: FloatArray): DoubleArray {
        require(embedding.size == DIMENSION) { "Expected $DIMENSION embedding values, got ${embedding.size}." }
        val logits = DoubleArray(CLASS_COUNT) { row ->
            var value = bias[row]
            for (column in 0 until DIMENSION) value += weights[row][column] * embedding[column].toDouble()
            value
        }
        val max = logits.maxOrNull()!!
        val exponentials = DoubleArray(CLASS_COUNT) { kotlin.math.exp(logits[it] - max) }
        val total = exponentials.sum()
        return DoubleArray(CLASS_COUNT) { exponentials[it] / total }
    }

    fun decide(pA: DoubleArray, pB: DoubleArray?): FrontDoorDecision {
        require(pA.size == CLASS_COUNT && (pB == null || pB.size == CLASS_COUNT))
        val actA = pA[ACT] >= act
        val discussionConfident = pA[CHAT] >= pA.maxOrNull()!! &&
            pA[CHAT] >= discussion && pA[ACT] + pA[READ] < offer
        fun offered(p: DoubleArray) = p[ACT] + p[READ] >= offer
        val toolsOffered = offered(pA) || (!discussionConfident && pB != null && offered(pB))
        val actIntended = actA || (!discussionConfident && pB != null && pB[ACT] >= act)
        val windowUpgraded = !discussionConfident && pB != null &&
            ((!offered(pA) && offered(pB)) || (!actA && pB[ACT] >= act))
        val vetoConfident = !windowUpgraded && pA[CHAT] >= pA.maxOrNull()!! &&
            pA[CHAT] >= veto && pA[ACT] < act
        val labelProbs = if (windowUpgraded) pB!! else pA
        val label = CLASSES[labelProbs.indices.maxByOrNull { labelProbs[it] }!!]
        return FrontDoorDecision(label, pA[ACT], pA[READ], pA[CHAT], toolsOffered,
            actIntended, discussionConfident, vetoConfident, windowUpgraded)
    }

    companion object {
        const val DIMENSION = 256
        const val CLASS_COUNT = 3
        const val CHAT = 0
        const val READ = 1
        const val ACT = 2
        val CLASSES = listOf("CHAT", "READ", "ACT")
    }
}

internal data class FrontDoorVerdict(
    val decision: FrontDoorDecision,
    val cold: Boolean,
    val source: String,
) {
    val label get() = decision.label
    val pAct get() = decision.pAct
    val pRead get() = decision.pRead
    val pChat get() = decision.pChat
    val toolsOffered get() = decision.toolsOffered
    val actIntended get() = decision.actIntended
    val discussionConfident get() = decision.discussionConfident
    val vetoConfident get() = decision.vetoConfident
    val windowUpgraded get() = decision.windowUpgraded
}

/**
 * EmbeddingGemma is optional and its native load must not delay routing. Cold turns fail open to
 * the declared approval gates, preventing the build 1972 NotAction miss from hiding tool calls.
 */
internal class FrontDoorClassifier(
    private val serviceProvider: () -> MemoryEmbeddingService?,
    private val assetOpener: () -> InputStream,
) {
    @Volatile var warmed: Boolean = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    private val headLock = Any()
    @Volatile private var head: FrontDoorHead? = null
    @Volatile private var headFailed = false
    @Volatile private var headFailureLogged = false

    suspend fun classify(current: String, previousUser: String?): FrontDoorVerdict {
        if (headFailed) return coldVerdict("cold_fail_open")
        if (!warmed) {
            warmUpAsync()
            return coldVerdict("cold_fail_open")
        }
        return try {
            val service = serviceProvider()
            if (service == null || !service.isAvailable) {
                warmed = false
                coldVerdict("unavailable_fail_open")
            } else {
                val classifierHead = loadHead() ?: return coldVerdict("cold_fail_open")
                val pair = withContext(Dispatchers.Default) {
                    val pA = classifierHead.probs(service.embedClassification(current))
                    val pB = previousUser?.let {
                        classifierHead.probs(service.embedClassification(FrontDoorInput.build(it, current)))
                    }
                    pA to pB
                }
                FrontDoorVerdict(classifierHead.decide(pair.first, pair.second), false, "classifier")
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            warmed = false
            val unavailable = error is EmbeddingEngineUnavailable
            coldVerdict(if (unavailable) "unavailable_fail_open" else "cold_fail_open")
        }
    }

    fun warmUpAsync() {
        if (warmed || headFailed || !warming.compareAndSet(false, true)) return
        scope.launch {
            try {
                val classifierHead = loadHead() ?: return@launch
                val service = serviceProvider()
                if (service == null || !service.isAvailable) return@launch
                withContext(Dispatchers.Default) { service.embedClassification(PROBE) }
                warmed = true
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                warmed = false
            } finally {
                warming.set(false)
            }
        }
    }

    private fun loadHead(): FrontDoorHead? {
        head?.let { return it }
        if (headFailed) return null
        synchronized(headLock) {
            head?.let { return it }
            if (headFailed) return null
            return try {
                FrontDoorHead(assetOpener()).also { head = it }
            } catch (error: Throwable) {
                headFailed = true
                warmed = false
                if (!headFailureLogged) {
                    headFailureLogged = true
                    android.util.Log.e("FrontDoorClassifier", "Front-door head could not be loaded; remaining fail-open.", error)
                }
                null
            }
        }
    }

    private fun coldVerdict(source: String) = FrontDoorVerdict(
        FrontDoorDecision("cold", 0.0, 0.0, 0.0, true, false, false, false, false),
        cold = true,
        source = source,
    )

    private companion object { const val PROBE = "Open an app." }
}
