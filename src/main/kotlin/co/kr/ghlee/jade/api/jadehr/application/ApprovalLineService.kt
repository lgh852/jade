package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import org.springframework.stereotype.Service

/** 신청 팝업에 보여줄 JadeHR 기본 결재선 */
@Service
class ApprovalLineService(
    private val jadeAuthUserService: JadeAuthUserService,
    private val jadeHrClient: JadeHrClient,
    private val properties: JadeHrProperties,
) {

    suspend fun get(jadeHrUserId: String, type: AttendanceApplicationType): ApprovalLineView {
        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val session = jadeHrClient.login(principal.credential)
        val line = jadeHrClient.fetchApprovalLine(session, type.jadeHrApplType, type.label, type.formUrl)
        val excluded = properties.defaultExcludedApproverIds.toSet()

        return ApprovalLineView(
            type = type,
            members = line.members.map {
                ApprovalLineMemberView(
                    order = it.order,
                    empId = it.empId,
                    name = it.name,
                    duty = it.duty,
                    apprType = it.apprType,
                    // "승인" 단계는 신청할 때 빼거나 넣을 수 없다 (JadeHR 이 붙임)
                    removable = it.isApproval,
                    excludedByDefault = it.isApproval && it.empId in excluded,
                )
            },
        )
    }
}

data class ApprovalLineView(
    val type: AttendanceApplicationType,
    val members: List<ApprovalLineMemberView>,
)

data class ApprovalLineMemberView(
    val order: Int,
    val empId: String,
    val name: String,
    val duty: String,
    /** 10 결재 / 30 승인 */
    val apprType: String,
    /** 화면에서 결재선에서 뺄 수 있는지 */
    val removable: Boolean,
    /** 기본으로 빼 둘지 */
    val excludedByDefault: Boolean,
)
