-- CLI token scope (#49). Existing tokens and pending web-login sessions keep full access.
-- The default also lets the previous release insert rows after an application rollback.
alter table cli_access_tokens add column scope varchar(20) default 'FULL' not null;
alter table cli_auth_sessions add column scope varchar(20) default 'FULL' not null;
