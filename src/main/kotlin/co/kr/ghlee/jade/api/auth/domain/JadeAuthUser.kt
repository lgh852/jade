package co.kr.ghlee.jade.api.auth.domain

import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "jade_auth_users")
class JadeAuthUser(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "auth_key", nullable = false, unique = true, length = 128)
    val authKey: String,

    @Column(name = "nickname", length = 100)
    var nickname: String? = null,

    @Column(name = "company_code", nullable = false, length = 20)
    var companyCode: String,

    @Column(name = "jadehr_user_id", nullable = false, length = 100)
    var jadeHrUserId: String,

    @Column(name = "jadehr_password_enc", nullable = false, columnDefinition = "text")
    var jadeHrPasswordEnc: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun toCredential(password: String): JadeHrCredential =
        JadeHrCredential(
            companyCode = companyCode,
            userId = jadeHrUserId,
            password = password,
        )

    fun updateCredential(
        nickname: String?,
        companyCode: String,
        jadeHrUserId: String,
        jadeHrPasswordEnc: String,
        active: Boolean,
    ) {
        this.nickname = nickname
        this.companyCode = companyCode
        this.jadeHrUserId = jadeHrUserId
        this.jadeHrPasswordEnc = jadeHrPasswordEnc
        this.active = active
        this.updatedAt = LocalDateTime.now()
    }
}
