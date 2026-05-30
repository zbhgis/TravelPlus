-- ============================================================
-- 压测造数：tb_attraction 追加 10 万条（保留不删，供 ES 同步对比）
-- 命名规则：压测景点{序号}{西湖|乌镇|千岛湖|灵隐寺}景区{序号}
-- 执行：docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < seed_attraction.sql
-- ============================================================

SET SESSION cte_max_recursion_depth = 1000;

INSERT INTO tb_attraction
    (name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time, destination)
WITH RECURSIVE seq100(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq100 WHERE n < 100
)
SELECT
    CONCAT('压测景点', n,
           CASE n % 4 WHEN 0 THEN '西湖' WHEN 1 THEN '乌镇' WHEN 2 THEN '千岛湖' ELSE '灵隐寺' END,
           '景区', n),
    1,                                              -- type_id 复用现有值 1
    '/imgs/attraction/1/0.jpg,/imgs/attraction/1/1.jpg',  -- images 复用现有图片路径
    '西湖风景区',
    CONCAT('杭州市压测路', n, '号'),
    ROUND(120 + (n % 1000) / 1000, 6),
    ROUND(30 + (n % 1000) / 1000, 6),
    n % 5000,                                       -- avg_price
    n % 100000,                                     -- sold
    n % 5000,                                       -- comments
    40 + (n % 11),                                  -- score 4.0~5.0（乘10）
    '08:00-17:30',
    NOW(), NOW(),
    '杭州'
FROM (
    SELECT (a.n - 1) * 10000 + (b.n - 1) * 100 + c.n AS n
    FROM seq100 a
    CROSS JOIN seq100 b
    CROSS JOIN seq100 c
    WHERE (a.n - 1) * 10000 + (b.n - 1) * 100 + c.n <= 100000
) nums;
