package com.battlesbudz.jarvis.v2.memory

/** Canonical storage boundary. A successful update is committed before its value is returned. */
interface MemoryPersistence {
    fun read(): MemoryStore.Read
    fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): MemoryStore.Update<T>
}
