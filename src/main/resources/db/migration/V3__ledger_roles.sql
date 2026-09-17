-- Layer 3 of invariant enforcement: least privilege. The application role
-- holds no UPDATE or DELETE on journal tables, so immutability does not
-- depend on application discipline. NOLOGIN by default: deployments enable
-- login with their own credentials out-of-band.

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'nummus_app') then
    create role nummus_app nologin;
  end if;
end
$$;

grant usage on schema ledger to nummus_app;

grant select on all tables in schema ledger to nummus_app;
grant insert on ledger.ledger_account, ledger.journal_transaction, ledger.journal_posting
  to nummus_app;

-- Status transitions are legitimate account operations; every other column is immutable.
grant update (status, closed_at) on ledger.ledger_account to nummus_app;

grant usage on all sequences in schema ledger to nummus_app;

alter default privileges in schema ledger
  grant select, insert on tables to nummus_app;
