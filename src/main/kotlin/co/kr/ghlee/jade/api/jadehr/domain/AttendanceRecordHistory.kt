package co.kr.ghlee.jade.api.jadehr.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "jade_attendance_record_histories")
class AttendanceRecordHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "auth_key", nullable = false, length = 128)
    val authKey: String,

    @Column(name = "company_code", nullable = false, length = 20)
    val companyCode: String,

    @Column(name = "jadehr_user_id", nullable = false, length = 100)
    val jadeHrUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 20)
    val recordType: AttendanceRecordType,

    @Column(name = "work_date", nullable = false)
    val workDate: LocalDate,

    @Column(name = "success", nullable = false)
    val success: Boolean,

    @Column(name = "message", columnDefinition = "text")
    val message: String?,

    @Column(name = "validation_message", columnDefinition = "text")
    val validationMessage: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
