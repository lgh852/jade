package co.kr.ghlee.jade.api.jadehr.domain

/** JadeHR 신청서의 기본 결재선 (신청서를 열 때 JadeHR 이 신청자·신청서 종류에 맞춰 내려준다) */
data class ApprovalLine(
    val members: List<ApprovalLineMember>,
)

data class ApprovalLineMember(
    /** 결재 순서 */
    val order: Int,
    /** 사번 */
    val empId: String,
    val name: String,
    /** 직책 (팀장·담당 등) */
    val duty: String,
    /** JadeHR 결재 유형: 10 결재 / 30 승인 */
    val apprType: String,
) {
    /** "승인" 단계는 신청 시 S_APPR_EMP_IDS 에 넣지 않는다 (서버가 붙임) */
    val isApproval: Boolean get() = apprType == "10"
}

/** JadeHR 신청 결과 */
data class ApplicationSubmitResult(
    val success: Boolean,
    val message: String,
    /** JadeHR 신청 번호 (성공 시) */
    val applId: String?,
)

/** JadeHR 신청내역의 한 건 */
data class JadeHrApplicationStatus(
    val applId: String,
    val applType: String,
    /** 200 결재중 / 300 반려 / 900 최종완료 */
    val statusCode: String,
    val statusName: String,
)
