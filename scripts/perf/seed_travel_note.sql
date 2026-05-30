-- ============================================================
-- 压测造数：tb_travel_note 追加 10 万条（保留不删，供 ES 同步对比）
-- 命名规则：压测游记{序号}杭州西湖游玩攻略；user_id 循环现有用户 1/2/4/5/1010；visible=0 公开
-- 执行：docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < seed_travel_note.sql
-- ============================================================

SET SESSION cte_max_recursion_depth = 1000;

INSERT INTO tb_travel_note
    (attraction_id, user_id, title, images, content, liked, comments, create_time, update_time, visible)
WITH RECURSIVE seq100(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq100 WHERE n < 100
)
SELECT
    NULL,
    CASE n % 5 WHEN 0 THEN 1010 WHEN 1 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 4 ELSE 5 END,  -- 循环现有用户
    CONCAT('压测游记', n, '杭州西湖游玩攻略'),
    '/imgs/blogs/4/0.jpg,/imgs/blogs/4/1.jpg,/imgs/blogs/4/2.jpg',
    CONCAT('压测游记内容', n, '：杭州西湖一日游压测记录'),
    n % 100,                                        -- liked
    n % 100,                                        -- comments
    NOW(), NOW(),
    0                                               -- visible=0 公开
FROM (
    SELECT (a.n - 1) * 10000 + (b.n - 1) * 100 + c.n AS n
    FROM seq100 a
    CROSS JOIN seq100 b
    CROSS JOIN seq100 c
    WHERE (a.n - 1) * 10000 + (b.n - 1) * 100 + c.n <= 100000
) nums;
