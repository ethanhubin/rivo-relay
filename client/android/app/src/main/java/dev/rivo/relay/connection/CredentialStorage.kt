package dev.rivo.relay.connection

interface CredentialStorage {
    fun read(name: String): String?
    fun save(name: String, plaintext: String)
    fun remove(name: String)
}
