alter table jade_auth_users
    add column nickname varchar(100) null after auth_key;

update jade_auth_users
set nickname = auth_key
where nickname is null;
