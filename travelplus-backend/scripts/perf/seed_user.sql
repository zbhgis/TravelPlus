-- ============================================================
-- 压测造数：tb_user 追加 2 万条（保留不删，供 ES 同步对比）
-- 命名规则：phone=153 + 8位序号（11位唯一，避开已存在 13910000005/15310000009 等）、
--          nick_name=压测用户{序号}小鱼、password 复用现有 BCrypt 哈希
-- 执行：docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < seed_user.sql
-- ============================================================

SET SESSION cte_max_recursion_depth = 1000;

INSERT INTO tb_user
    (phone, password, nick_name, icon, bg_image, points, balance, create_time, update_time)
WITH RECURSIVE seq100(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq100 WHERE n < 100
)
SELECT
    CONCAT('153', LPAD(n, 8, '0')),                 -- 15300000001..15300020000，11位唯一
    '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K',
    CONCAT('压测用户', n, '小鱼'),
    '/imgs/user/avatar/default.png',
    '',
    0, 0.00,
    NOW(), NOW()
FROM (
    SELECT (a.n - 1) * 200 + (b.n - 1) * 2 + c.n AS n
    FROM seq100 a
    CROSS JOIN seq100 b
    CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2) c
    WHERE (a.n - 1) * 200 + (b.n - 1) * 2 + c.n <= 20000
) nums;
