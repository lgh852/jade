create table jade_attendance_record_histories (
    id bigint not null auto_increment,
    auth_key varchar(128) not null,
    company_code varchar(20) not null,
    jadehr_user_id varchar(100) not null,
    record_type varchar(20) not null,
    work_date date not null,
    success boolean not null,
    message text null,
    validation_message text null,
    created_at timestamp(6) not null,
    primary key (id),
    key idx_jade_attendance_record_histories_lookup (auth_key, record_type, work_date, success)
);
