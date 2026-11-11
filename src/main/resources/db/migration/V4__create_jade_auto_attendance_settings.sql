create table jade_auto_attendance_settings (
    id bigint not null auto_increment,
    jadehr_user_id varchar(100) not null,
    enabled boolean not null,
    start_time time null,
    end_time time null,
    -- 실행 상태: 날짜 단위로 기록하고 날짜가 바뀌면 새로 시작한다
    -- status: SUCCESS(등록 성공) / FAILED(실패, 재시도 대상) / SKIPPED(재택 아님 등으로 건너뜀)
    start_run_date date null,
    start_status varchar(20) null,
    start_attempts int not null default 0,
    start_message varchar(500) null,
    end_run_date date null,
    end_status varchar(20) null,
    end_attempts int not null default 0,
    end_message varchar(500) null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_jade_auto_attendance_settings_user (jadehr_user_id)
);
