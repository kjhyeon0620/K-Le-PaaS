-- Test-only migration (high version so real migrations never collide): proves a follow-up version applies after the baseline.
alter table users add column flyway_sample varchar(10) default 'sample';
