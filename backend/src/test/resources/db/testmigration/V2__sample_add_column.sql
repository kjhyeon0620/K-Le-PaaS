-- Test-only migration: proves a follow-up version applies after both a fresh V1 and a baselined database.
alter table users add column flyway_sample varchar(10) default 'v2';
