package co.kr.ghlee.jade.api.jadehr.domain

enum class AttendanceRecordType {
    START,
    END,
}

data class AttendanceRecordPrepareResult(
    val type: AttendanceRecordType,
    val workDate: String?,
    val employeeId: String?,
    val gubun: String,
    val dsClass: String,
    val recordMethod: String,
    val validateMethod: String?,
    val form: Map<String, String>,
)

data class AttendanceRecordResult(
    val type: AttendanceRecordType,
    val success: Boolean,
    val message: String?,
    val validationMessage: String?,
    val etcData: Map<String, String>,
)
