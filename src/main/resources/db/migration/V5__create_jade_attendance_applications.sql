-- 우리 화면에서 올린 JadeHR 신청서(재택·휴가·출퇴근 등록 요청) 기록.
-- JadeHR 캘린더는 결재가 끝나야 반영되므로, 결재 진행 중에는 이 기록으로 캘린더 표시·자동 출퇴근 판단을 한다.
-- status: REQUESTED(신청 완료, 결재 진행 중) / FAILED(신청 실패)
create table jade_attendance_applications (
    id bigint not null auto_increment,
    jadehr_user_id varchar(100) not null,
    appl_type varchar(20) not null,
    work_date date not null,
    status varchar(20) not null,
    leave_type_code varchar(10) null,
    leave_type_name varchar(50) null,
    leave_span varchar(10) null,
    in_out_class varchar(10) null,
    start_time time null,
    end_time time null,
    reason varchar(500) null,
    jadehr_appl_id varchar(30) null,
    message varchar(500) null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    key idx_jade_attendance_applications_user_date (jadehr_user_id, work_date)
);
