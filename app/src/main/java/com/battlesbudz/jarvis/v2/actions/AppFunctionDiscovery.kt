package com.battlesbudz.jarvis.v2.actions

/**
 * M3 AppFunctions discovery (T16): metadata/state discovery and
 * invalidation, collision-safe alias binding and task-relevant selection.
 *
 * Discovery snapshots come from an [AppFunctionMetadataSource] — ordinary
 * app access (PackageManager metadata scan) or, where the platform allows,
 * an ADB-assisted scan; the method is always labeled. A new snapshot
 * diffs against the previous one so updates, removals (uninstalls) and
 * additions invalidate exactly the affected bindings.
 *
 * The model never sees package names or raw function ids: it sees
 * collision-safe aliases bound to full provider/package/function identities.
 * Two providers exposing the same function name never collide — the later
 * binding gets a provider-suffixed alias and every binding in the collision
 * group is flagged, so the UI can explain it honestly.
 */
data class AppFunctionIdentity(val providerPackage: String, val functionId: String)

data class AppFunctionMetadata(
    val providerPackage: String,
    val functionId: String,
    val displayName: String,
    val description: String,
    val versionCode: Long,
    val parameters: AppFunctionType.Obj,
    val resultType: AppFunctionType,
    val scopes: Set<String>,
    val pricing: ProviderPricing = ProviderPricing.FREE,
    val needsUserInteraction: Boolean = false
) {
    init {
        require(providerPackage.isNotBlank() && functionId.isNotBlank())
        require(versionCode >= 0)
        require(scopes.size <= 16 && scopes.all { it.length <= 64 })
    }

    val identity = AppFunctionIdentity(providerPackage, functionId)
    val providerId: ProviderId = ProviderId.appFunctions(providerPackage)
    val wireName: String = ProviderWireNames.toolName(providerId, functionId)
}

/** How a discovery snapshot was obtained. Always labeled; never mixed silently. */
enum class DiscoveryAccessMethod { ORDINARY_APP, ADB }

data class DiscoverySnapshot(
    val functions: List<AppFunctionMetadata>,
    val accessMethod: DiscoveryAccessMethod,
    val atMs: Long
)

data class DiscoveryDiff(
    val added: List<AppFunctionMetadata>,
    val updated: List<AppFunctionMetadata>,
    val removed: List<AppFunctionMetadata>
)

object AppFunctionDiscovery {
    /**
     * Diff two snapshots by (package, function) identity. A version-code
     * bump on the same identity is an update; a vanished identity is a
     * removal (uninstall or withdrawn function) and invalidates its alias.
     */
    fun diff(before: DiscoverySnapshot?, after: DiscoverySnapshot): DiscoveryDiff {
        val beforeById = before?.functions?.associateBy { it.identity }.orEmpty()
        val afterById = after.functions.associateBy { it.identity }
        val added = after.functions.filter { it.identity !in beforeById }
        val removed = before?.functions.orEmpty().filter { it.identity !in afterById }
        val updated = after.functions.filter { fn ->
            val prev = beforeById[fn.identity]
            prev != null && (prev.versionCode != fn.versionCode || prev != fn)
        }
        return DiscoveryDiff(added, updated, removed)
    }
}

/** Supplies one discovery snapshot; implementations label their access method. */
interface AppFunctionMetadataSource {
    val accessMethod: DiscoveryAccessMethod
    fun query(): List<AppFunctionMetadata>
}

data class AliasBinding(
    val alias: String,
    val identity: AppFunctionIdentity,
    val wireName: String,
    val displayName: String,
    /** True when another provider exposed the same base alias; the settings UI explains it. */
    val collided: Boolean,
    val collisionNote: String? = null
)

sealed interface AliasResolution {
    data class Resolved(val binding: AliasBinding) : AliasResolution
    data object Unknown : AliasResolution
}

/**
 * Collision-safe alias bindings. Rebinding is total: every snapshot
 * replacement recomputes all aliases, so removals and updates invalidate
 * stale bindings instead of leaving dangling aliases.
 */
class AppFunctionAliasRegistry {
    private var bindings: Map<String, AliasBinding> = emptyMap()

    fun rebind(functions: List<AppFunctionMetadata>): List<AliasBinding> {
        val groups = functions.groupBy { baseAlias(it.functionId) }
        val next = linkedMapOf<String, AliasBinding>()
        for ((base, group) in groups.toSortedMap()) {
            if (group.size == 1) {
                val fn = group.single()
                next[base] = AliasBinding(base, fn.identity, fn.wireName, fn.displayName, collided = false)
                continue
            }
            val ordered = group.sortedBy { it.providerPackage }
            val note = "Also provided by ${ordered.joinToString(", ") { it.providerPackage }}."
            // The first provider keeps the short alias; the rest are
            // suffixed with their provider's short name, then a counter.
            val used = hashSetOf<String>()
            ordered.forEachIndexed { index, fn ->
                val alias = when {
                    index == 0 -> base
                    else -> {
                        val short = fn.providerPackage.substringAfterLast('.')
                            .lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
                            .ifBlank { "p$index" }
                        var candidate = "${base}_$short"
                        var counter = 2
                        while (candidate in used) candidate = "${base}_${short}_$counter".also { counter++ }
                        candidate
                    }
                }
                used += alias
                next[alias] = AliasBinding(alias, fn.identity, fn.wireName, fn.displayName,
                    collided = true, collisionNote = note)
            }
        }
        bindings = next
        return next.values.toList()
    }

    fun resolve(alias: String): AliasResolution =
        bindings[alias]?.let { AliasResolution.Resolved(it) } ?: AliasResolution.Unknown

    fun all(): List<AliasBinding> = bindings.values.toList()

    private fun baseAlias(functionId: String): String =
        ProviderWireNames.sanitizeFunctionId(functionId)
}

/**
 * Task-relevant tool selection (JVM-pure): rank bound functions by
 * keyword overlap between the task text and the alias, display name,
 * function id and description. Zero-overlap candidates are dropped; ties
 * break deterministically by alias. This is a relevance pre-filter, not a
 * model decision.
 */
object AppFunctionTaskSelection {
    fun select(
        query: String,
        bindings: List<AliasBinding>,
        metadataFor: (AliasBinding) -> AppFunctionMetadata?,
        limit: Int = 5
    ): List<AliasBinding> {
        require(limit in 1..25)
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()
        // A natural query like "look up the user by id" names the
        // `lookup_user` function without sharing whole tokens with it
        // ("look" vs "lookup"). The separator-blind phrase signal catches
        // that: an alias whose name appears in the request, ignoring
        // separators, outranks pure token overlap.
        val compactQuery = compact(query)
        return bindings.mapNotNull { binding ->
            val metadata = metadataFor(binding) ?: return@mapNotNull null
            val haystack = tokenize("${binding.alias} ${binding.displayName} " +
                "${metadata.functionId} ${metadata.description}")
            val overlap = queryTokens.intersect(haystack).size
            val phraseHit = compact(binding.alias).isNotEmpty() &&
                compactQuery.contains(compact(binding.alias))
            if (overlap == 0 && !phraseHit) null
            else Triple(binding, if (phraseHit) 1 else 0, overlap)
        }.sortedWith(
            compareByDescending<Triple<AliasBinding, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.alias })
            .take(limit).map { it.first }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }.toSet()

    private fun compact(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Noise tokens that would otherwise hand out spurious relevance
     * points (a query's "the" matching every "the ..." description).
     * Without this, "look up the user by id" ties `echo` ("Echoes the
     * given text back") with `lookup_user` on one junk token each and the
     * alphabetical tiebreak picks the wrong function.
     */
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for",
        "with", "by", "at", "from", "is", "are", "it", "this", "that",
        "please", "my", "me", "you", "your", "we"
    )
}

/**
 * The discovered AppFunctions world: the current snapshot, its alias
 * bindings and per-provider scope declarations. The dispatcher and the
 * settings surface read from here; the journal's grant records reference
 * it only through wire names and namespaced scopes.
 */
class ProviderRegistry {
    private var snapshot: DiscoverySnapshot? = null
    val aliasRegistry = AppFunctionAliasRegistry()

    fun update(newSnapshot: DiscoverySnapshot): DiscoveryDiff {
        val diff = AppFunctionDiscovery.diff(snapshot, newSnapshot)
        snapshot = newSnapshot
        aliasRegistry.rebind(newSnapshot.functions)
        return diff
    }

    fun metadataFor(provider: ProviderId, functionId: String): AppFunctionMetadata? =
        snapshot?.functions?.firstOrNull {
            it.providerPackage == provider.id && it.functionId == functionId
        }

    fun metadataForWire(wireName: String): AppFunctionMetadata? {
        val parsed = ProviderWireNames.parseToolName(wireName) ?: return null
        if (parsed.provider.kind != ProviderKind.APP_FUNCTIONS) return null
        return metadataFor(parsed.provider, parsed.functionId)
    }

    fun functions(): List<AppFunctionMetadata> = snapshot?.functions.orEmpty()

    fun functionsOf(provider: ProviderId): List<AppFunctionMetadata> =
        snapshot?.functions.orEmpty().filter { it.providerId == provider }

    fun providerIds(): List<ProviderId> =
        snapshot?.functions.orEmpty().map { it.providerId }.distinct().sortedBy { it.id }

    /** The full scope set a provider's grant may ever cover (T08 cap input). */
    fun declaredScopes(provider: ProviderId): Set<String> =
        snapshot?.functions.orEmpty()
            .filter { it.providerId == provider }
            .flatMapTo(hashSetOf()) { it.scopes }

    fun accessMethod(): DiscoveryAccessMethod? = snapshot?.accessMethod

    fun snapshotAtMs(): Long? = snapshot?.atMs
}
