-- 定时触发的「档位」：cron 本该跑的那个时间点，与真正跑起来的时间是两件事。
-- 调度器停机后恢复时，last_fired_at 会是一个很晚的时间（比如 09:37），
-- 只有 last_slot_at 才能回答「这一跑代表的是 09:00 那一档，迟到了 37 分钟」。
-- 幂等键也从档位生成：崩溃在派发与记账之间时，重扫会算出同一个档位，走幂等回放而不是再跑一次。
ALTER TABLE workflow_trigger
  ADD COLUMN last_slot_at DATETIME(3) NULL AFTER last_fired_at;
