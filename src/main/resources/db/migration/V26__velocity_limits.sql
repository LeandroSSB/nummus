-- M26 velocity limits: a rolling-24h cap on the gross amount of intents a
-- merchant attempts. Same storage shape as M23: cached current value on the
-- merchant row, the history table gains the column additively (NULL = the
-- knob was unset at that change). The index serves the enforcement window
-- query (created_at range; account scoping rides the existing account index).

alter table merchants.merchant
  add column max_daily_intent_volume numeric(19,4)
    check (max_daily_intent_volume > 0);

alter table merchants.payment_limits_entry
  add column max_daily_intent_volume numeric(19,4);

create index payment_intent_created_at_idx
  on payments.payment_intent (created_at);
