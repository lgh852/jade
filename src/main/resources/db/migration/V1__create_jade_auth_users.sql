create table jade_auth_users (
    id bigint not null auto_increment,
    auth_key varchar(128) not null,
    company_code varchar(20) not null,
    jadehr_user_id varchar(100) not null,
    jadehr_password_enc text not null,
    active boolean not null default true,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    unique key uk_jade_auth_users_auth_key (auth_key)
);
