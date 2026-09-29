-- Deploy-only CLI tokens are bound to one repository (#50). Null for every other scope.
-- No foreign key: deleting a repository must not be blocked by tokens; a stale id simply fails the ownership check.
alter table cli_access_tokens add column repository_id bigint;
