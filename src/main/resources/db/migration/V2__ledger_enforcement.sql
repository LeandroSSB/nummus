-- Layer 2 of invariant enforcement: the database defends itself against
-- every writer, including the schema owner and raw SQL.

create or replace function ledger.forbid_mutation() returns trigger
language plpgsql as $$
begin
  raise exception 'table % is append-only: % is not permitted', tg_table_name, tg_op;
end;
$$;

create trigger journal_transaction_immutable
  before update or delete on ledger.journal_transaction
  for each row execute function ledger.forbid_mutation();

create trigger journal_posting_immutable
  before update or delete on ledger.journal_posting
  for each row execute function ledger.forbid_mutation();

create trigger ledger_account_immutable
  before delete on ledger.ledger_account
  for each row execute function ledger.forbid_mutation();

-- Balanced-transaction invariant, checked at commit so multi-statement
-- inserts are never rejected mid-flight.

create or replace function ledger.assert_transaction_balanced() returns trigger
language plpgsql as $$
declare
  v_count   integer;
  v_debits  integer;
  v_credits integer;
  v_delta   numeric;
  v_inactive integer;
begin
  select count(*),
         count(*) filter (where direction = 'DEBIT'),
         count(*) filter (where direction = 'CREDIT'),
         coalesce(sum(amount) filter (where direction = 'DEBIT'), 0)
           - coalesce(sum(amount) filter (where direction = 'CREDIT'), 0)
    into v_count, v_debits, v_credits, v_delta
  from ledger.journal_posting
  where transaction_id = new.transaction_id;

  if v_count < 2 or v_debits < 1 or v_credits < 1 then
    raise exception 'transaction % is unbalanced: at least one DEBIT and one CREDIT posting are required (postings: %)',
      new.transaction_id, v_count;
  end if;

  if v_delta <> 0 then
    raise exception 'transaction % is unbalanced: debits minus credits = %',
      new.transaction_id, v_delta;
  end if;

  select count(*) into v_inactive
  from ledger.journal_posting p
  join ledger.ledger_account a on a.id = p.account_id
  where p.transaction_id = new.transaction_id
    and a.status <> 'ACTIVE';

  if v_inactive > 0 then
    raise exception 'transaction % posts to a non-ACTIVE account', new.transaction_id;
  end if;

  return null;
end;
$$;

create constraint trigger journal_posting_balanced
  after insert on ledger.journal_posting
  deferrable initially deferred
  for each row execute function ledger.assert_transaction_balanced();
