-- ============================================================
-- novel-cloud：清理项目未使用的 19 张表
--
-- 【作用范围】只作用于 `novel-cloud` 业务库。
--   `nacos` / `xxl_job` 是中间件独立数据库，本脚本绝不触碰，删了会导致
--   Nacos 配置中心与 XXL-JOB 调度中心不可用。
--
-- 【为什么可以删】全项目 Java / Mapper XML / 配置检索显示这 19 张表零业务引用：
--   单体版遗留的重复评论表、未实现的支付/消费/阅读历史/作家收入/邀请码表、
--   后台管理系统 sys_* 表、以及一张 test 测试表。对应的实体与 Mapper 已删除。
--
-- 【执行前必须备份】在命令行执行（把 -p 后的口令换成你自己的）：
--   mysqldump -uroot -pzxcv1234 --default-character-set=utf8mb4 `novel-cloud` `author_code` `author_income` `author_income_detail` `book_comment_copy1` `book_comment_reply` `pay_alipay` `pay_wechat` `sys_log` `sys_menu` `sys_role` `sys_role_menu` `sys_user` `sys_user_role` `test` `user_comment` `user_comment_reply` `user_consume_log` `user_pay_log` `user_read_history` > unused_tables_backup.sql
--   恢复：mysql -uroot -p `novel-cloud` < unused_tables_backup.sql
--
-- 【建议先查是否有数据】把下面的查询单独跑一遍，确认没有你需要保留的内容。
-- ============================================================

USE `novel-cloud`;

-- ---------- 1. 执行前检查（可选，先单独运行） ----------
SELECT 'author_code' AS table_name, COUNT(*) AS rows_count FROM `author_code` UNION ALL
SELECT 'author_income' AS table_name, COUNT(*) AS rows_count FROM `author_income` UNION ALL
SELECT 'author_income_detail' AS table_name, COUNT(*) AS rows_count FROM `author_income_detail` UNION ALL
SELECT 'book_comment_copy1' AS table_name, COUNT(*) AS rows_count FROM `book_comment_copy1` UNION ALL
SELECT 'book_comment_reply' AS table_name, COUNT(*) AS rows_count FROM `book_comment_reply` UNION ALL
SELECT 'pay_alipay' AS table_name, COUNT(*) AS rows_count FROM `pay_alipay` UNION ALL
SELECT 'pay_wechat' AS table_name, COUNT(*) AS rows_count FROM `pay_wechat` UNION ALL
SELECT 'sys_log' AS table_name, COUNT(*) AS rows_count FROM `sys_log` UNION ALL
SELECT 'sys_menu' AS table_name, COUNT(*) AS rows_count FROM `sys_menu` UNION ALL
SELECT 'sys_role' AS table_name, COUNT(*) AS rows_count FROM `sys_role` UNION ALL
SELECT 'sys_role_menu' AS table_name, COUNT(*) AS rows_count FROM `sys_role_menu` UNION ALL
SELECT 'sys_user' AS table_name, COUNT(*) AS rows_count FROM `sys_user` UNION ALL
SELECT 'sys_user_role' AS table_name, COUNT(*) AS rows_count FROM `sys_user_role` UNION ALL
SELECT 'test' AS table_name, COUNT(*) AS rows_count FROM `test` UNION ALL
SELECT 'user_comment' AS table_name, COUNT(*) AS rows_count FROM `user_comment` UNION ALL
SELECT 'user_comment_reply' AS table_name, COUNT(*) AS rows_count FROM `user_comment_reply` UNION ALL
SELECT 'user_consume_log' AS table_name, COUNT(*) AS rows_count FROM `user_consume_log` UNION ALL
SELECT 'user_pay_log' AS table_name, COUNT(*) AS rows_count FROM `user_pay_log` UNION ALL
SELECT 'user_read_history' AS table_name, COUNT(*) AS rows_count FROM `user_read_history`;

-- ---------- 2. 确认无误后再执行以下 DROP ----------
DROP TABLE IF EXISTS `author_code`;
DROP TABLE IF EXISTS `author_income`;
DROP TABLE IF EXISTS `author_income_detail`;
DROP TABLE IF EXISTS `book_comment_copy1`;
DROP TABLE IF EXISTS `book_comment_reply`;
DROP TABLE IF EXISTS `pay_alipay`;
DROP TABLE IF EXISTS `pay_wechat`;
DROP TABLE IF EXISTS `sys_log`;
DROP TABLE IF EXISTS `sys_menu`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_role_menu`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `test`;
DROP TABLE IF EXISTS `user_comment`;
DROP TABLE IF EXISTS `user_comment_reply`;
DROP TABLE IF EXISTS `user_consume_log`;
DROP TABLE IF EXISTS `user_pay_log`;
DROP TABLE IF EXISTS `user_read_history`;

-- ---------- 3. 复核 ----------
-- SHOW TABLES;  -- 应只剩 14 张业务表

