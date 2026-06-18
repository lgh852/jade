package co.kr.ghlee.jade.api.auth.infrastructure

import co.kr.ghlee.jade.api.auth.domain.JadeAuthUser
import org.springframework.data.jpa.repository.JpaRepository

interface JadeAuthUserRepository : JpaRepository<JadeAuthUser, Long> {
    fun findByAuthKeyAndActiveTrue(authKey: String): JadeAuthUser?

    fun findByAuthKey(authKey: String): JadeAuthUser?
}
