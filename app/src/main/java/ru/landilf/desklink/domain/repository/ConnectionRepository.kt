package ru.landilf.desklink.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.landilf.desklink.domain.model.SshProfile

interface ConnectionRepository {
    val profiles: Flow<List<SshProfile>>
    suspend fun save(
        profile: SshProfile,
        password: String,
        privateKey: String = "",
        passphrase: String = ""
    )

    suspend fun remove(profile: SshProfile)
    suspend fun password(profile: SshProfile): String
    suspend fun privateKey(profile: SshProfile): String
    suspend fun passphrase(profile: SshProfile): String
    suspend fun fingerprint(host: String, port: Int): String?
    suspend fun saveFingerprint(host: String, port: Int, fingerprint: String)
}
