package co.kr.ghlee.jade.api.auth.application

import co.kr.ghlee.jade.api.auth.domain.JadeAuthUser
import co.kr.ghlee.jade.api.auth.exception.JadeLoginFailedException
import co.kr.ghlee.jade.api.auth.infrastructure.JadeAuthUserRepository
import co.kr.ghlee.jade.api.jadehr.application.AttendanceCalendarService
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrLoginException
import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 화면 로그인. 입력한 아이디·비밀번호로 JadeHR 에 실제 로그인해 확인한다.
 * - 성공했는데 DB 비밀번호와 다르면(JadeHR 비밀번호 변경) DB 비밀번호를 바꾼다.
 * - 처음 로그인한 아이디면 DB 에 새로 등록한다 (자동 출퇴근·세션 갱신에 쓰인다).
 * 틀린 비밀번호가 반복되면 JadeHR 계정이 잠길 수 있어, 같은 아이디로 연속 실패하면 잠시 막는다.
 */
@Service
class LoginService(
    private val repository: JadeAuthUserRepository,
    private val credentialEncryptor: CredentialEncryptor,
    private val attendanceCalendarService: AttendanceCalendarService,
    private val jadeHrProperties: JadeHrProperties,
) {
    private val failures = ConcurrentHashMap<String, Failure>()

    suspend fun login(userId: String, password: String): LoginUser {
        val id = userId.trim()
        val key = id.lowercase()
        val now = Instant.now()
        failures[key]?.let { f ->
            if (f.count >= MAX_FAILURES && now.isBefore(f.since.plus(LOCK_DURATION))) {
                throw JadeLoginFailedException("로그인 실패가 반복돼 잠시 막았습니다. 10분 뒤 다시 시도해 주세요.")
            }
        }

        val companyCode = withContext(Dispatchers.IO) {
            repository.findAllByJadeHrUserIdAndActiveTrueOrderByIdAsc(id).firstOrNull()?.companyCode
        } ?: jadeHrProperties.defaultCompanyCode

        try {
            attendanceCalendarService.login(JadeHrCredential(companyCode = companyCode, userId = id, password = password))
        } catch (e: JadeHrLoginException) {
            failures.compute(key) { _, f ->
                if (f == null || now.isAfter(f.since.plus(LOCK_DURATION))) Failure(1, now) else f.copy(count = f.count + 1)
            }
            log.info("Jade login failed at JadeHR. userId={}, message={}", id, e.message)
            throw JadeLoginFailedException(e.message?.takeIf { it.isNotBlank() } ?: "아이디 또는 비밀번호가 올바르지 않습니다.")
        }
        failures.remove(key)

        return withContext(Dispatchers.IO) { saveCredential(id, companyCode, password) }
    }

    suspend fun find(userId: String): LoginUser? = withContext(Dispatchers.IO) {
        repository.findAllByJadeHrUserIdAndActiveTrueOrderByIdAsc(userId).firstOrNull()?.toLoginUser()
    }

    /** JadeHR 로그인에 성공한 비밀번호를 DB 에 반영한다 */
    private fun saveCredential(userId: String, companyCode: String, password: String): LoginUser {
        val users = repository.findAllByJadeHrUserIdAndActiveTrueOrderByIdAsc(userId)
        if (users.isEmpty()) {
            // 처음 로그인한 사용자. authKey 는 아이디로 하되 이미 쓰인 키면 기존 행을 되살린다.
            val user = repository.findByAuthKey(userId)
                ?.takeIf { it.jadeHrUserId.equals(userId, ignoreCase = true) }
                ?.apply { changePassword(credentialEncryptor.encrypt(password)) }
                ?: JadeAuthUser(
                    authKey = availableAuthKey(userId),
                    companyCode = companyCode,
                    jadeHrUserId = userId,
                    jadeHrPasswordEnc = credentialEncryptor.encrypt(password),
                    active = true,
                )
            log.info("Jade login registered user. userId={}, authKey={}", userId, user.authKey)
            return repository.save(user).toLoginUser()
        }
        val changed = users.filter { runCatching { credentialEncryptor.decrypt(it.jadeHrPasswordEnc) }.getOrNull() != password }
        if (changed.isNotEmpty()) {
            changed.forEach { it.changePassword(credentialEncryptor.encrypt(password)) }
            repository.saveAll(changed)
            log.info("Jade login updated stored password. userId={}, rows={}", userId, changed.size)
        }
        return users.first().toLoginUser()
    }

    private fun availableAuthKey(userId: String): String =
        generateSequence(0) { it + 1 }
            .map { if (it == 0) userId else "$userId-$it" }
            .first { repository.findByAuthKey(it) == null }

    private fun JadeAuthUser.toLoginUser() =
        LoginUser(id = jadeHrUserId, nickname = nickname?.takeIf { it.isNotBlank() } ?: jadeHrUserId)

    private data class Failure(val count: Int, val since: Instant)

    companion object {
        private val log = LoggerFactory.getLogger(LoginService::class.java)
        private const val MAX_FAILURES = 5
        private val LOCK_DURATION: Duration = Duration.ofMinutes(10)
    }
}

data class LoginUser(
    val id: String,
    val nickname: String,
)
