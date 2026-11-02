-- W01 K03：历史材料恢复候选只读清单。
-- 只扫描曾按回执恢复的行；不导出材料正文、密文、密钥或数据库连接信息。
-- 本脚本只读，不包含 UPDATE/DELETE。真实环境执行前按既有备份流程留备份；
-- 分类与纠错必须另行使用受控 CLI/服务，不能在 SQL 中直接改 created_at。
--
-- 使用：
--   psql "$DB_URL" -f scripts/audit-material-time-recovery.sql
-- 输出只保存在本地受控位置，不提交数据库连接信息。

SELECT material_id, version, author_id, created_at, created_at_confidence,
       time_source, time_evidence, time_recovered_by, time_recovered_at,
       legacy_recorded_at
FROM material_response
WHERE time_source = 'RECOVERED_EVIDENCE'
ORDER BY material_id, version;

SELECT count(*) AS recovered_evidence_candidates
FROM material_response
WHERE time_source = 'RECOVERED_EVIDENCE';
