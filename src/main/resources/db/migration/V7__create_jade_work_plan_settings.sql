-- 근무 일정 자동 등록 설정: 매주 월요일 JadeHR 근태현황 > 일정등록에서 등록 가능한 날을 이 시간대로 등록한다
create table jade_work_plan_settings (
    id bigint not null auto_increment,
    jadehr_user_id varchar(100) not null,
    enabled boolean not null,
    start_time time not null,
    end_time time not null,
    last_run_at timestamp(6) null,
    last_success boolean null,
    last_message varchar(1000) null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_jade_work_plan_settings_user (jadehr_user_id)
);
