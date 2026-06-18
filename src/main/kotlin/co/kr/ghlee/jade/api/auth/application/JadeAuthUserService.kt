package co.kr.ghlee.jade.api.auth.application

import co.kr.ghlee.jade.api.auth.domain.JadeAuthUser
import co.kr.ghlee.jade.api.auth.exception.JadeAuthUserNotFoundException
import co.kr.ghlee.jade.api.auth.infrastructure.JadeAuthUserRepository
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JadeAuthUserService(
    private val repository: JadeAuthUserRepository,
    private val credentialEncryptor: CredentialEncryptor,
) {

    @Transactional
    suspend fun upsert(command: JadeAuthUserUpsertCommand): JadeAuthUserResult = withContext(Dispatchers.IO) {
        val encryptedPassword = credentialEncryptor.encrypt(command.jadeHrPassword)
        val user = repository.findByAuthKey(command.authKey)
            ?.apply {
                updateCredential(
                    companyCode = command.companyCode,
                    nickname = command.nickname,
                    jadeHrUserId = command.jadeHrUserId,
                    jadeHrPasswordEnc = encryptedPassword,
                    active = command.active,
                )
            }
            ?: JadeAuthUser(
                authKey = command.authKey,
                nickname = command.nickname,
                companyCode = command.companyCode,
                jadeHrUserId = command.jadeHrUserId,
                jadeHrPasswordEnc = encryptedPassword,
                active = command.active,
            )

        repository.save(user).toResult()
    }

    suspend fun resolveCredential(authKey: String): JadeHrCredential =
        resolvePrincipal(authKey).credential

    suspend fun resolvePrincipal(authKey: String): JadeAuthPrincipal = withContext(Dispatchers.IO) {
        val user = repository.findByAuthKeyAndActiveTrue(authKey)
            ?: throw JadeAuthUserNotFoundException()

        JadeAuthPrincipal(
            authKey = user.authKey,
            nickname = user.nickname?.takeIf { it.isNotBlank() } ?: user.authKey,
            credential = user.toCredential(credentialEncryptor.decrypt(user.jadeHrPasswordEnc)),
        )
    }

    private fun JadeAuthUser.toResult(): JadeAuthUserResult =
        JadeAuthUserResult(
            id = id,
            authKey = authKey,
            nickname = nickname?.takeIf { it.isNotBlank() } ?: authKey,
            companyCode = companyCode,
            jadeHrUserId = jadeHrUserId,
            active = active,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )
}

data class JadeAuthUserUpsertCommand(
    val authKey: String,
    val nickname: String?,
    val companyCode: String,
    val jadeHrUserId: String,
    val jadeHrPassword: String,
    val active: Boolean,
)

data class JadeAuthUserResult(
    val id: Long,
    val authKey: String,
    val nickname: String,
    val companyCode: String,
    val jadeHrUserId: String,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

data class JadeAuthPrincipal(
    val authKey: String,
    val nickname: String,
    val credential: JadeHrCredential,
)
