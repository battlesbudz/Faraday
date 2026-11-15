package com.battlesbudz.jarvis.v2.memory

/**
 * The fixed golden corpus for hybrid recall (in-repo plan §8): paraphrases,
 * coreference, negatives and no-relevant-memory turns over a small fixed ledger.
 *
 * The embedding side is a fixture, honestly: [ConceptEngine] maps each corpus text
 * to a declared concept axis, so a paraphrase pair shares geometry exactly the way
 * a real embedder places paraphrases near each other, and unrelated pairs are
 * orthogonal the way unrelated personal facts are distant. The corpus therefore
 * measures the PIPELINE — candidate generation, eligibility, fusion, relevance
 * filtering, the profile split — not EmbeddingGemma's raw quality, which is a
 * device measurement (Unit 1.5). What the gate proves is that given embeddings
 * with this geometry, hybrid recall beats the lexical-only path that ships today,
 * and that the failure modes §8 names (irrelevant injection, ineligible leakage,
 * stale projections) do not occur.
 */
internal object MemoryGoldenCorpus {
    const val AXIS_TEA = 0
    const val AXIS_VEHICLE = 1
    const val AXIS_SPOUSE_ALLERGY = 2
    const val AXIS_BROTHER_HOME = 3
    const val AXIS_BROTHER_JOB = 4
    const val AXIS_CABIN_GOAL = 5
    const val AXIS_FLIGHT_SEAT = 6
    const val AXIS_DENTIST = 7
    const val AXIS_APPOINTMENT = 8
    const val AXIS_PHONE = 9
    const val AXIS_BIRTHDAY = 10
    const val AXIS_BLOOD = 11
    const val AXIS_SKATE = 12
    const val AXIS_HOME = 13
    const val AXIS_NOTEBOOK = 14
    const val AXIS_CONTACT = 15
    const val AXIS_NOISE = 250

    /** Memory contents, verbatim as proposed to the ledger. */
    object Text {
        const val NOTEBOOK = "My notebook color is teal."
        const val TEA = "I love drinking oolong tea first thing in the morning."
        const val CAR = "My car is a blue 2019 Subaru Outback."
        const val TRUCK_SUPERSEDED = "My car is a red Ford truck."
        const val WIFE = "My wife Andrea is allergic to peanuts."
        const val BROTHER_HOME = "My brother Daniel lives in Denver."
        const val BROTHER_JOB = "Daniel works night shifts as a nurse."
        const val CABIN = "I am saving up to buy a cabin near Lake Placid."
        const val SEAT = "I prefer aisle seats when I fly."
        const val DENTIST = "My dentist is Dr. Patel at BrightSmile in Watertown."
        const val SKATE = "I used to skateboard when I was a teenager."
        const val HOME = "I live in Watertown, New York."
        const val CONTACT = "The best way to reach Daniel is through his work email."
        const val PENDING_TEA = "My favorite morning drink is chamomile tea."
        const val EXPIRED_ESPRESSO = "I love espresso shots."
    }

    /** Concept geometry for documents (memory contents) and queries alike. */
    val concepts: Map<String, Int> = mapOf(
        Text.NOTEBOOK to AXIS_NOTEBOOK,
        Text.TEA to AXIS_TEA,
        Text.CAR to AXIS_VEHICLE,
        Text.TRUCK_SUPERSEDED to AXIS_VEHICLE,
        Text.WIFE to AXIS_SPOUSE_ALLERGY,
        Text.BROTHER_HOME to AXIS_BROTHER_HOME,
        Text.BROTHER_JOB to AXIS_BROTHER_JOB,
        Text.CABIN to AXIS_CABIN_GOAL,
        Text.SEAT to AXIS_FLIGHT_SEAT,
        Text.DENTIST to AXIS_DENTIST,
        Text.SKATE to AXIS_SKATE,
        Text.HOME to AXIS_HOME,
        Text.CONTACT to AXIS_CONTACT,
        Text.PENDING_TEA to AXIS_TEA,
        Text.EXPIRED_ESPRESSO to AXIS_TEA,
        Queries.NOTEBOOK to AXIS_NOTEBOOK,
        Queries.TEA_PARAPHRASE to AXIS_TEA,
        Queries.VEHICLE_PARAPHRASE to AXIS_VEHICLE,
        Queries.SPOUSE_COREFERENCE to AXIS_SPOUSE_ALLERGY,
        Queries.BROTHER_COREFERENCE to AXIS_BROTHER_HOME,
        Queries.GOAL_PARAPHRASE to AXIS_CABIN_GOAL,
        Queries.SEAT_PARAPHRASE to AXIS_FLIGHT_SEAT,
        Queries.DENTIST_DIRECT to AXIS_DENTIST,
        Queries.SKATE_PARAPHRASE to AXIS_SKATE,
        Queries.HOME_PARAPHRASE to AXIS_HOME,
        Queries.BROTHER_PHONE_NEGATIVE to AXIS_PHONE,
        Queries.DENTIST_APPOINTMENT_NEGATIVE to AXIS_APPOINTMENT,
        Queries.WIFE_BIRTHDAY_NEGATIVE to AXIS_BIRTHDAY,
        Queries.BLOOD_TYPE_NONE to AXIS_BLOOD,
    )

    object Queries {
        const val NOTEBOOK = "What is my notebook color?"
        const val TEA_PARAPHRASE = "What's my preferred hot drink after I wake up?"
        const val VEHICLE_PARAPHRASE = "What vehicle do I drive?"
        const val SPOUSE_COREFERENCE = "What food does my spouse have to avoid?"
        const val BROTHER_COREFERENCE = "Where does he live?"
        const val GOAL_PARAPHRASE = "What's my big purchase goal?"
        const val SEAT_PARAPHRASE = "Where do I like to sit on a plane?"
        const val DENTIST_DIRECT = "Who is my dentist?"
        const val SKATE_PARAPHRASE = "What did I do for fun as a kid?"
        const val HOME_PARAPHRASE = "Where is home for me?"
        const val BROTHER_PHONE_NEGATIVE = "What is the phone number for my brother?"
        const val DENTIST_APPOINTMENT_NEGATIVE = "When is my dentist appointment?"
        const val WIFE_BIRTHDAY_NEGATIVE = "What is the birthday of my wife?"
        const val BLOOD_TYPE_NONE = "What is my blood type?"
        const val EIFFEL_NONE = "How tall is the Eiffel Tower?"
        const val WEATHER_BOUNDARY = "What's the weather in Watertown?"
    }

    /**
     * Deterministic stand-in embedder: strips the service's known role formats and
     * returns the declared concept axis as a 768-wide vector (the service truncates
     * to the stored 256, which keeps every axis). Unknown texts land on an axis
     * orthogonal to every concept.
     */
    class ConceptEngine : MemoryEmbeddingEngine {
        override val modelId: String = EmbeddingModelCatalog.default.id
        override val available: Boolean = true
        var queryEmbedCalls = 0
            private set
        var documentEmbedCalls = 0
            private set

        override fun embed(text: String, role: EmbeddingRole): FloatArray {
            if (role == EmbeddingRole.QUERY) queryEmbedCalls++ else documentEmbedCalls++
            val raw = when {
                text.startsWith(QUERY_PREFIX) -> text.removePrefix(QUERY_PREFIX)
                text.startsWith(DOCUMENT_PREFIX) -> text.removePrefix(DOCUMENT_PREFIX)
                else -> text
            }
            val out = FloatArray(EmbeddingModelCatalog.default.outputDimension)
            val axis = concepts[raw]
            if (axis == null) out[AXIS_NOISE] = 1f else out[axis] = 1f
            return out
        }

        companion object {
            const val QUERY_PREFIX = "task: search result | query: "
            const val DOCUMENT_PREFIX = "title: none | text: "
        }
    }

    /**
     * A fully wired corpus world: a real [MemoryOs] over a temp ledger with the
     * hybrid retriever bound through the production provider seam, plus a second
     * provider-less [MemoryOs] over the same ledger as the lexical-only baseline.
     * The vector projection deliberately contains rows for ineligible records too
     * (pending, expired, superseded — correct stamps, wrong lifecycle state) and
     * one current-lifecycle record whose stamp is stale, so read-time eligibility
     * and currency are exercised, not assumed.
     */
    class World {
        var now: Long = 1_700_000_000_000L
        val engine = ConceptEngine()
        val service = MemoryEmbeddingService(engine)
        val vectors = FakeVectorStore()
        val retriever = MemoryHybridRetrieval(service, vectors)
        private val ledgerFile: java.io.File = java.nio.file.Files.createTempFile("memory-golden-corpus", ".json").toFile().apply { delete(); deleteOnExit() }
        val os: MemoryOs = MemoryOs(MemoryStore(ledgerFile), { now }).also { it.hybridRetrievalProvider = { retriever } }
        val lexicalOs: MemoryOs = MemoryOs(MemoryStore(ledgerFile), { now })
        val byText = LinkedHashMap<String, MemoryRecord>()
        private var eventSeq = 0

        fun add(
            text: String,
            category: MemoryCategory,
            approve: Boolean = true,
            expiresAtMs: Long? = null,
            corrects: MemoryRecord? = null,
        ): MemoryRecord {
            val proposal = MemoryProposal(
                content = text,
                source = MemorySource("golden-${eventSeq++}", "golden corpus", now),
                category = category,
                expiresAtMs = expiresAtMs,
                correctsMemoryId = corrects?.id,
                expectedTargetRevision = corrects?.revision,
            )
            val proposed = os.propose(proposal)
            require(proposed.outcome == MemoryOutcome.CREATED) { "propose $text -> ${proposed.outcome}: ${proposed.message}" }
            val createdId = proposed.memory!!.id
            if (approve) {
                val approved = os.approve(createdId)
                require(approved.outcome == MemoryOutcome.APPROVED) { "approve $text -> ${approved.outcome}: ${approved.message}" }
            }
            val record = os.read().snapshot!!.memories.first { it.id == createdId }
            byText[text] = record
            return record
        }

        /** Populate the projection from the service, except the one deliberate stale stamp. */
        fun indexProjection() {
            val snapshot = os.read().snapshot!!
            for (record in snapshot.memories) {
                if (record.content == Text.CONTACT) {
                    val stale = FloatArray(EmbeddingModelCatalog.default.storedDimension)
                    stale[AXIS_PHONE] = 1f
                    vectors.put(MemoryVectorEntry(record.id, "stale-stamp", service.spec.id, service.spec.storedDimension, stale, now))
                } else {
                    vectors.put(MemoryVectorEntry(
                        memoryId = record.id,
                        contentFingerprint = AutomaticMemory.contentFingerprint(record.content),
                        modelId = service.spec.id,
                        dimension = service.spec.storedDimension,
                        vector = service.embedDocument(record.content),
                        updatedAtMs = now,
                    ))
                }
            }
        }

        fun snapshot(): MemorySnapshot = os.read().snapshot!!
    }

    fun buildWorld(): World {
        val world = World()
        val t0 = world.now
        world.add(Text.NOTEBOOK, MemoryCategory.FACT)
        world.add(Text.TEA, MemoryCategory.PREFERENCE)
        val truck = world.add(Text.TRUCK_SUPERSEDED, MemoryCategory.FACT)
        world.add(Text.CAR, MemoryCategory.FACT, corrects = truck)
        world.add(Text.WIFE, MemoryCategory.PERSON)
        world.add(Text.BROTHER_HOME, MemoryCategory.PERSON)
        world.add(Text.BROTHER_JOB, MemoryCategory.FACT)
        world.add(Text.CABIN, MemoryCategory.GOAL)
        world.add(Text.SEAT, MemoryCategory.PREFERENCE)
        world.add(Text.DENTIST, MemoryCategory.FACT)
        world.add(Text.SKATE, MemoryCategory.FACT)
        world.add(Text.HOME, MemoryCategory.FACT)
        world.add(Text.CONTACT, MemoryCategory.FACT)
        world.add(Text.PENDING_TEA, MemoryCategory.PREFERENCE, approve = false)
        world.add(Text.EXPIRED_ESPRESSO, MemoryCategory.PREFERENCE, expiresAtMs = t0 + 10_000L)
        world.indexProjection()
        // Retrieval runs after the espresso record's expiry.
        world.now = t0 + 20_000L
        return world
    }

    /** In-memory vector store with the same read contract as the SQLite projection. */
    class FakeVectorStore : MemoryVectorStore {
        val rows = linkedMapOf<String, MemoryVectorEntry>()
        var readError: String? = null
        override fun read(modelId: String): MemoryVectorStore.Read =
            readError?.let { MemoryVectorStore.Read(null, it) }
                ?: MemoryVectorStore.Read(rows.values.filter { it.modelId == modelId })
        override fun put(entry: MemoryVectorEntry): Boolean { rows[entry.memoryId] = entry; return true }
        override fun remove(memoryIds: Collection<String>): Boolean { memoryIds.forEach { rows.remove(it) }; return true }
        override fun storedBytes(): Long = rows.values.sumOf { it.vector.size * 4L }
    }
}
