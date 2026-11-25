-- V022：budget_ledger_seen 排序规则对齐 utf8mb4_unicode_ci。
-- V021 首次创建表时未显式指定 COLLATE；此处补齐，使表与库默认排序规则一致，
-- 避免 budget_ledger_seen.execution_id 在大小写/重音敏感场景下的隐式排序差异。
-- 幂等：ALTER ... COLLATE 对已是目标排序规则的表是 no-op。
ALTER TABLE budget_ledger_seen
  COLLATE = utf8mb4_unicode_ci;
