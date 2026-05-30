/*
 Navicat Premium Data Transfer

 Source Server         : local
 Source Server Type    : MySQL
 Source Server Version : 50622
 Source Host           : localhost:3306
 Source Schema         : travelplus

 Target Server Type    : MySQL
 Target Server Version : 50622
 File Encoding         : 65001

 Date: 02/03/2022 23:12:54
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_travel_note
-- ----------------------------
DROP TABLE IF EXISTS `tb_travel_note`;
CREATE TABLE `tb_travel_note`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `attraction_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '景点id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '游记的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '游记的文字描述',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 26 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_travel_note
-- ----------------------------
INSERT INTO `tb_travel_note` VALUES (4, 4, 2, '西湖断桥｜白堤漫步半日游，杭州的浪漫都藏在这里🌅', '/imgs/blogs/4/0.jpg,/imgs/blogs/4/1.jpg,/imgs/blogs/4/2.jpg', '来杭州怎么能不去西湖断桥！🌉\n从断桥出发沿白堤一路向东，左手西湖右手孤山，风一吹全是荷香和杨柳气息🍃\n\n📍断桥：白娘子与许仙相会的地方，清晨去人少光好，随手一拍都是大片\n📍白堤：全长约1公里，慢慢走半小时，两岸桃柳夹道，春天美到犯规🌸\n📍平湖秋月：白堤尽头，开阔湖面视野一绝，傍晚看落日绝美\n\n全程免费🆓 建议下午4点去，逛到日落正好看晚霞染红湖面\n回来顺便路过宝石山，远眺保俶塔，杭州的温柔一天根本逛不完～', 13, 107, '2026-01-06 10:15:00', '2026-01-08 10:15:00');
INSERT INTO `tb_travel_note` VALUES (5, 1, 2, '杭州灵隐寺｜飞来峰下许个愿，把好运带回家🙏', '/imgs/blogs/5/0.jpg,/imgs/blogs/5/1.jpg,/imgs/blogs/5/2.jpg', '来杭州祈福，一定不能错过灵隐寺！🙏\n千年古刹藏在一片绿意里，香火袅袅，进去心就静了\n\n📍飞来峰：石窟造像密密麻麻刻在岩壁上，宋元石刻超震撼，走在里面像穿越千年\n📍灵隐寺：大雄宝殿巍峨壮观，香火很旺，进门可以领三支清香，诚心祈福\n📍永福寺：藏在灵隐后山，游人少、环境清幽，寺庙里的茶室还能喝杯茶歇脚🍵\n\n💰门票：飞来峰45元，灵隐寺香花券30元\n🚌交通：地铁3号线到黄龙洞换公交，或直接打车到飞来峰景区门口\n\n愿所求皆如愿，所行化坦途～✨', 2, 2, '2026-01-17 14:30:00', '2026-01-19 14:30:00');
INSERT INTO `tb_travel_note` VALUES (6, 10, 1, '杭州西溪湿地｜摇橹船穿过芦苇荡，周末就该这样慢下来🛶', '/imgs/blogs/6/0.jpg,/imgs/blogs/6/1.jpg,/imgs/blogs/6/2.jpg', '谁说杭州只有西湖？西溪湿地的美是另一种温柔🛶\n坐在摇橹船上，船夫慢悠悠摇着橹，穿过一丛丛芦苇荡，耳边只有水声和鸟鸣\n\n📍摇橹船：必体验！环湿地一圈，船速很慢，适合发呆放空\n📍芦苇荡：秋季芦苇开花，整片白茫茫，风一吹像雪浪翻滚，拍照绝了\n📍虾龙滩生态保护区：野生鸟类天堂，运气好能看到白鹭和野鸭🦆\n\n🎫门票＋船票：约120元/人（含电瓶船）\n⏰建议下午2点入园，光线最柔和，湿地日落超治愈\n\n偷得浮生半日闲，把城市的喧嚣都丢在船后～🌿', 11, 2, '2026-01-28 09:00:00', '2026-01-30 09:00:00');
INSERT INTO `tb_travel_note` VALUES (7, 10, 1, '杭州龙井村｜九溪烟树茶园徒步，把春天泡进一杯茶里🍵', '/imgs/blogs/7/0.jpg,/imgs/blogs/7/1.jpg,/imgs/blogs/7/2.jpg', '杭州春天的正确打开方式，是去龙井村喝一杯明前龙井！🍵\n从九溪烟树一路走到龙井村，溪水潺潺、古树参天，每一步都是画\n\n📍九溪烟树：九溪十八涧的起点，雾气缭绕的溪谷，像仙境一样\n📍龙井村：家家户户门前晒着茶叶，狮峰山下茶园一片连一片，绿到洗眼睛\n📍问茶：找家茶农坐下，老板现场冲泡龙井，豆香扑鼻，一杯下去整个人都舒展了\n\n🧭徒步路线：九溪烟树→九溪十八涧→龙井村，全程约2小时，轻松不累\n🚌交通：公交到九溪站，或打车到九溪烟树入口\n\n茶香满山，溪水长流，这就是我理想中的江南春天～🌿', 11, 2, '2026-02-08 16:45:00', '2026-02-10 16:45:00');
INSERT INTO `tb_travel_note` VALUES (9, NULL, 1010, '雷峰塔｜登塔俯瞰西湖全景，白蛇传里的千年传说🌇', '/imgs/blogs/9/0.jpg,/imgs/blogs/9/1.jpg,/imgs/blogs/9/2.jpg', '来杭州，一定要登一次雷峰塔！🌇\n站在塔顶，西湖全景尽收眼底，三面云山一面城，视野开阔到让人失语\n\n📍塔顶观景台：电梯直达，360度俯瞰西湖，雷峰夕照名不虚传\n📍塔基遗址：保留着吴越国古塔的基座，千年历史踩在脚下\n📍白蛇传说：白娘子被压在塔下的传说，让这座塔多了一层浪漫滤镜\n\n💰门票：40元，学生半价\n⏰建议傍晚5点登塔，正好赶上雷峰夕照，落日把整片湖面染成金色\n\n有故事、有风景、有黄昏，雷峰塔值得为它爬一次山～', 15, 3, '2026-02-19 11:20:00', '2026-02-21 11:20:00');
INSERT INTO `tb_travel_note` VALUES (10, NULL, 1011, '苏堤春晓｜六桥烟柳的春日漫游，杭州春天最浪漫的一条路🌸', '/imgs/blogs/10/0.jpg,/imgs/blogs/10/1.jpg,/imgs/blogs/10/2.jpg', '春天来杭州，最不能错过苏堤！🌸\n全长近三公里，六座拱桥依次排开，两岸烟柳如帘，春风一吹全是新绿\n\n📍六桥烟柳：映波、锁澜、望山……每座桥都有自己的名字，慢慢走慢慢看\n📍苏堤春晓：西湖十景之首，清晨雾未散时最美，柳枝拂水像仙境\n📍花港观鱼：堤边顺路逛逛，池中锦鲤成群，春天都活泼起来🐟\n📍长桥夕照：走到堤南尽头，傍晚回望雷峰塔，金色塔影倒映湖中\n\n🚶建议从北往南走，全程约1小时，春天桃花夹道，随手一拍都像明信片\n\n春风十里不如你，苏堤的春天想分你一半～🍃', 30, 3, '2026-03-02 08:40:00', '2026-03-04 08:40:00');
INSERT INTO `tb_travel_note` VALUES (11, NULL, 1012, '六和塔｜钱塘江畔听涛声，登塔远眺江天一色🌊', '/imgs/blogs/11/0.jpg,/imgs/blogs/11/1.jpg,/imgs/blogs/11/2.jpg', '钱塘江畔的六和塔，比西湖多一分壮阔🌊\n七级浮屠层层收窄，站在塔上，钱塘江大桥和滚滚江涛尽收眼底\n\n📍登塔观江：爬完200多级木梯登顶，江风迎面，钱塘江大潮季还能看潮\n📍塔身浮雕：每层檐角挂铃，风起时叮当作响，古意满满\n📍塔下江畔：沿江步道散步，看货轮往来，日落时江面金光粼粼\n\n💰门票：20元（含登塔）\n🚌交通：公交六和塔站下车即到，或从西湖打车20分钟\n\n登高望远，听涛观江，六和塔把杭州的江南与江湖都看尽了～', 8, 10, '2026-03-13 15:10:00', '2026-03-15 15:10:00');
INSERT INTO `tb_travel_note` VALUES (12, NULL, 1010, '清河坊老街｜南宋市井的烟火气，杭州最有人情味的一条街🏮', '/imgs/blogs/12/0.jpg,/imgs/blogs/12/1.jpg,/imgs/blogs/12/2.jpg', '想感受老杭州，来清河坊就对了！🏮\n青石板路两侧是白墙黑瓦的仿宋建筑，古色古香，一路走一路吃\n\n📍老街漫步：胡庆余堂中药博物馆、方回春堂，老字号扎堆\n📍小吃天堂：定胜糕、葱包桧、龙须糖……边走边吃根本停不下来\n📍河坊街夜景：入夜灯笼亮起，整条街像穿越回南宋夜市\n\n⏰建议傍晚去，逛到晚上刚好赶上灯火通明的夜市\n💰部分老字号可免费参观，小吃人均50左右\n\n人间烟火气，最抚凡人心，清河坊就是杭州的市井底色～🏮', 2, 3, '2026-03-24 13:05:00', '2026-03-26 13:05:00');
INSERT INTO `tb_travel_note` VALUES (13, NULL, 1011, '满觉陇｜金秋桂花香满陇，杭州秋天最甜的一条村路🌼', '/imgs/blogs/13/0.jpg,/imgs/blogs/13/1.jpg,/imgs/blogs/13/2.jpg', '杭州的秋天，是从满觉陇的桂花香开始的🌼\n金秋十月，漫山遍野的桂花树开了，空气里全是甜甜的桂花味\n\n📍满陇桂雨：西湖新十景之一，桂花盛开时花瓣如雨飘落\n📍闻香寻桂：沿村道散步，金桂银桂丹桂一路铺开，风一吹香到醉人\n📍桂花美食：桂花糕、桂花藕粉、桂花酒酿，把秋天吃进肚子里\n\n⏰最佳观赏期：10月中下旬，花期约两周\n🚌交通：公交到满觉陇站，或打车到满觉陇村\n\n桂花一开，杭州就变成了甜味的城市，这个秋天请一定来～🍂', 26, 3, '2026-04-04 10:25:00', '2026-04-06 10:25:00');
INSERT INTO `tb_travel_note` VALUES (14, 17, 1013, '千岛湖｜碧水千岛间的慢时光🚤', '/imgs/blogs/14/0.jpg,/imgs/blogs/14/1.jpg,/imgs/blogs/14/2.jpg', '周末躲进千岛湖，才算真正把生活调成了慢速🚤\n千岛湖的美，在于那 1078 座小岛散落在碧蓝湖面上，一岛一景，怎么都看不够\n\n📍梅峰观景台：一定要登顶！俯瞰千岛全景，星罗棋布的小岛像翡翠洒在水面\n📍游船环湖：坐上游船穿行岛间，湖风扑面，水天一色，手机随便拍都是壁纸\n📍中心湖渔乐岛：看完巨网捕鱼表演，再尝一尾现捞的千岛湖鱼头，鲜到眉毛掉\n\n🍜美食推荐：千岛湖鱼头汤必喝，奶白浓香，配一碗米饭就是人间值得\n🚗交通：杭州高铁 1 小时直达，出站打车 20 分钟即到湖区\n\n这里的节奏很慢，湖水很蓝，适合带上喜欢的人来发一整天呆～🍃', 32, 5, '2026-04-15 09:50:00', '2026-04-17 09:50:00');
INSERT INTO `tb_travel_note` VALUES (15, 18, 1014, '乌镇西栅｜枕水江南的夜，一盏灯一条巷🏮', '/imgs/blogs/15/0.jpg,/imgs/blogs/15/1.jpg,/imgs/blogs/15/2.jpg', '乌镇的夜，是江南最温柔的样子🏮\n当夜幕四合，西栅的灯火次第亮起，一条条巷子枕着水，静谧得能听见橹声\n\n📍夜游水道：摇橹船缓缓穿过桥洞，两岸灯笼倒映水中，光影摇曳像走进了画里\n📍草木本色染坊：蓝印花布高高挂起，随风轻摆，午后光线穿过布幔美到失语\n📍昭明书院：闹中取静的古书院，青石砖墙配老树，很适合坐下来发呆\n\n🍜吃一碗书生羊肉面，再配一碟熏豆茶，江南的滋味都在碗里了\n💡建议住一晚西栅民宿，清晨推开窗就是水乡人家的日常\n\n一盏灯、一条巷、一叶舟，乌镇把江南的诗意都揉进了夜色里～🌙', 25, 4, '2026-04-26 17:30:00', '2026-04-28 17:30:00');
INSERT INTO `tb_travel_note` VALUES (16, 19, 1015, '湘湖｜比西湖清静的宝藏湖泊，环湖骑行刚刚好🚴', '/imgs/blogs/16/0.jpg,/imgs/blogs/16/1.jpg,/imgs/blogs/16/2.jpg', '杭州人的私藏后花园，湘湖绝对排得上号🚴\n比起西湖的人潮涌动，湘湖显得格外清静，湖面开阔，微风不燥\n\n📍环湖骑行：沿湖绿道约 8 公里，平缓好骑，一边湖景一边绿林，太治愈了\n📍越王城遗址：山上的古越遗迹，登高能俯瞰整个湘湖，视野一级棒\n📍跨湖桥遗址博物馆：8000 年前的独木舟静静躺着，一秒穿越远古\n\n🎫门票免费，博物馆预约即可，性价比无敌\n🍂秋天来最美，水杉红了，桂花正香，湖风里都是甜味\n\n不需要滤镜的湖水，随手一拍都是治愈系大片，趁人少快冲～🍃', 12, 1, '2026-05-07 12:15:00', '2026-05-09 12:15:00');
INSERT INTO `tb_travel_note` VALUES (17, 20, 1016, '良渚古城遗址｜五千年文明的晨光🌄', '/imgs/blogs/17/0.jpg,/imgs/blogs/17/1.jpg,/imgs/blogs/17/2.jpg', '清晨五点爬起床，只为良渚古城遗址公园的第一缕晨光🌄\n五千年前的城墙遗迹在薄雾里若隐若现，辽阔而震撼\n\n📍莫角山遗址：古城中心的至高平台，站在上面能想象当年王城的恢弘\n📍反山王陵：出土玉琮玉璧的王陵区，隔着玻璃都能感受古人工艺的极致\n📍良渚博物院：镇馆之宝玉琮王一定要看，纹饰精细到起鸡皮疙瘩\n\n🎫门票 60 元含观光车，观光车必坐，园区实在太大\n📷拍照建议：日落时分站上城墙，逆光剪影配稻田，出片率极高\n\n在这片被列入世界遗产的土地上，随便一块土都踩着五千年的历史，敬畏感拉满～🏛️', 18, 2, '2026-05-18 06:40:00', '2026-05-20 06:40:00');
INSERT INTO `tb_travel_note` VALUES (18, NULL, 1017, '钱塘江夜游｜灯光秀与江风，杭州的另一种浪漫🌉', '/imgs/blogs/18/0.jpg,/imgs/blogs/18/1.jpg,/imgs/blogs/18/2.jpg', '谁说杭州只有西湖？钱塘江的夜景才是这座城市的另一面🌉\n夜幕降临，沿江的高楼亮起灯光秀，江面被染成流动的光河\n\n📍城市阳台：免费观景 C 位，正对灯光秀主秀场，视野开阔到震撼\n📍钱塘江大桥：老桥在灯光下像一条银龙横卧江上，复古与摩登同框\n📍夜游船：乘船顺江而下，两岸灯光次第展开，江风拂面超浪漫\n\n⏰灯光秀时间：每晚 7:00 和 8:00 两场，提前 10 分钟占位最佳\n📷拍摄技巧：用慢门长曝光，把车流灯光拉成丝，大片即视感\n\n江风、灯光、船影，这条江把杭州的夜景浪漫值直接拉满～✨', 28, 6, '2026-05-29 19:05:00', '2026-05-31 19:05:00');
INSERT INTO `tb_travel_note` VALUES (19, 5, 1018, '宋城千古情｜一生必看的一场演出🎭', '/imgs/blogs/19/0.jpg,/imgs/blogs/19/1.jpg,/imgs/blogs/19/2.jpg', '来宋城，就是为了看一场《宋城千古情》🎭\n这台号称"一生必看"的演出，把杭州千年的故事搬上舞台，看得人热泪盈眶\n\n📍演出阵容：五个篇章——良渚之光、宋宫宴舞、金戈铁马、西子传说、魅力杭州，每一幕都震撼\n📍舞美特效：水幕、升降舞台、飞天杂技，视觉冲击力直接拉满\n📍宋城街景：演出前后逛仿宋街，穿汉服拍照氛围感绝佳\n\n🎫门票 310 元含演出，建议提前订票选中间位置，视野最佳\n🍜园内小吃不少，定胜糕、龙须糖都值得一试\n\n看懂杭州，从这场演出开始，看完真的会起一身鸡皮疙瘩～🌟', 35, 8, '2026-06-09 14:35:00', '2026-06-11 14:35:00');
INSERT INTO `tb_travel_note` VALUES (20, 11, 1019, '浙江省博物馆｜孤山馆里的杭州千年📜', '/imgs/blogs/20/0.jpg,/imgs/blogs/20/1.jpg,/imgs/blogs/20/2.jpg', '想读懂杭州，先来浙江省博物馆孤山馆区转一圈📜\n西湖边上的老馆，白墙黛瓦，馆藏丰富到可以逛一整天\n\n📍镇馆之宝：玉琮王、越王勾践剑，每一件都是国宝级文物\n📍良渚文化厅：玉器陶器整齐陈列，五千年前的审美惊艳到词穷\n📍孤山馆环境：就在西湖边，逛累了推门就是湖景，完美结合\n\n🎫免费预约，周一闭馆，记得提前在公众号约\n⏰建议预留 2-3 小时，从河姆渡到明清，一部浙江史慢慢看\n\n文物会说话，这座馆把杭州千年的故事都讲给你听了～🏛️', 9, 1, '2026-06-20 10:00:00', '2026-06-22 10:00:00');
INSERT INTO `tb_travel_note` VALUES (21, NULL, 1010, '北高峰日出｜凌晨四点上山，等一场云海日出⛰️', '/imgs/blogs/21/0.jpg,/imgs/blogs/21/1.jpg,/imgs/blogs/21/2.jpg', '凌晨四点的杭州，安静得只剩下脚步声⛰️\n为了北高峰的日出，我提前一天就查好了天气，算好时间出发\n\n📍登山路线：从老和山方向上山，约 1 小时到顶，台阶平缓不算累\n📍山顶视角：北高峰是杭州城区制高点，俯瞰西湖与城市在晨雾中苏醒\n📍云海日出：运气好时能碰上云海，太阳跃出的一瞬间，整片山被染成金色\n\n🎫缆车 30 元单程，体力好建议徒步，晨间山风特别舒服\n💡装备建议：厚外套必备，山顶清晨只有十几度\n\n当你站在山顶看着城市苏醒，会觉得这一夜的辛苦全都值了～🌅', 40, 9, '2026-07-01 04:30:00', '2026-07-03 04:30:00');
INSERT INTO `tb_travel_note` VALUES (22, NULL, 1011, '法喜寺｜年轻人的第一炷香，求姻缘好灵🙏', '/imgs/blogs/22/0.jpg,/imgs/blogs/22/1.jpg,/imgs/blogs/22/2.jpg', '都说杭州年轻人爱去法喜寺，去了才知道，求姻缘是真的灵🙏\n藏在山林里的古寺，绿荫掩映，香火旺得不像话\n\n📍大雄宝殿：香火最旺的地方，请香祈福，心诚则灵\n📍求姻缘：据说寺里求姻缘特别灵验，很多人专程赶来\n📍黄墙拍照：法喜寺的黄墙配绿荫超好看，随手一拍都是大片\n\n🎫门票 10 元，进寺请香另付，心诚则灵\n🚌交通：公交到上天竺站，或骑行穿过茶园，一路风景很好\n\n许个愿，把烦恼留下，把好运带走，年轻人就该去寺庙逛逛～🍀', 22, 7, '2026-07-12 09:15:00', '2026-07-14 09:15:00');
INSERT INTO `tb_travel_note` VALUES (23, 15, 1012, '太子湾春天｜郁金香与樱花的浪漫暴击🌸', '/imgs/blogs/23/0.jpg,/imgs/blogs/23/1.jpg,/imgs/blogs/23/2.jpg', '杭州春天的顶流，太子湾公园必须拥有姓名🌸\n三月中旬的太子湾，郁金香与樱花同框，直接把浪漫值拉满\n\n📍郁金香花海：几十万株郁金香铺成彩色地毯，红粉黄紫连绵成片\n📍樱花大道：河边的樱花树连成一片，风一吹花瓣纷飞，像在下粉色雨\n📍风车草坪：坐在草地上晒太阳，看着花海和风车，治愈到不想走\n\n⏰最佳花期：3 月中下旬，人超多，建议早上 8 点前入园\n📷拍照建议：逆光拍郁金香更通透，蹲低一点避开人群\n\n春天不去太子湾，等于白过了杭州的春天～🌷', 30, 5, '2026-07-23 08:45:00', '2026-07-25 08:45:00');
INSERT INTO `tb_travel_note` VALUES (24, 10, 1020, '杭州动物园｜遛娃好去处，一天看遍奇珍动物🦁', '/imgs/blogs/24/0.jpg,/imgs/blogs/24/1.jpg,/imgs/blogs/24/2.jpg', '周末不知道带娃去哪？杭州动物园必须有姓名🦁\n门票只要 20 块，就能看遍各种奇珍动物，性价比高到离谱\n\n📍大象馆：憨态可掬的大象会跟人互动，孩子看得走不动道\n📍狮虎山：威风凛凛的狮子和东北虎，隔着玻璃都气场十足\n📍金鱼廊：几十种金鱼游来游去，颜色艳丽，拍照超好看\n\n🎫门票 20 元，性价比之王，还有海狮表演别错过\n⏰建议上午去，动物比较活跃，下午容易躲起来睡觉\n\n一天逛完，孩子开心大人省心，遛娃神地认证～🐘', 15, 2, '2026-08-03 10:20:00', '2026-08-05 10:20:00');
INSERT INTO `tb_travel_note` VALUES (25, 9, 1013, '南宋御街｜穿越回临安城的烟火日常🏯', '/imgs/blogs/25/0.jpg,/imgs/blogs/25/1.jpg,/imgs/blogs/25/2.jpg', '走在南宋御街，像一脚踏回了临安城的市井日常🏯\n八百年前皇帝走的御道，如今是杭州最有烟火气的老街\n\n📍中山中路：青石板路两侧梧桐成荫，老建筑保留着民国韵味\n📍凤凰寺：中国东南四大清真寺之一，安静肃穆，值得驻足\n📍鼓楼：南宋城墙遗址上的鼓楼，登楼能望见整条御街延伸向远方\n\n🍜小吃推荐：定胜糕、葱包桧、老杭帮菜，边走边吃停不下来\n🏮夜景更美：华灯初上，老街在灯笼下温柔起来，适合慢慢逛\n\n一条老街，半部杭州史，来这儿感受最接地气的杭州～🏮', 20, 3, '2026-08-07 15:55:00', '2026-08-07 15:55:00');

-- ----------------------------
-- Table structure for tb_travel_note_comment
-- ----------------------------
DROP TABLE IF EXISTS `tb_travel_note_comment`;
CREATE TABLE `tb_travel_note_comment`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `note_id` bigint(20) UNSIGNED NOT NULL COMMENT '游记id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_travel_note_comment
-- ----------------------------
INSERT INTO `tb_travel_note_comment` VALUES (3, 1013, 4, 0, 0, '断桥的日落真的绝，求机位！', 8, 0, '2026-01-08 10:20:00', '2026-01-08 10:20:00');
INSERT INTO `tb_travel_note_comment` VALUES (4, 2, 4, 0, 0, '白堤春天去最美，桃柳夹道超好拍', 5, 0, '2026-01-09 09:30:00', '2026-01-09 09:30:00');
INSERT INTO `tb_travel_note_comment` VALUES (5, 1017, 4, 0, 0, '收藏了，下周就去！', 3, 0, '2026-01-12 20:15:00', '2026-01-12 20:15:00');
INSERT INTO `tb_travel_note_comment` VALUES (6, 1014, 5, 0, 0, '飞来峰石刻超震撼，值得一去', 6, 0, '2026-01-18 11:00:00', '2026-01-18 11:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (7, 1020, 5, 0, 0, '香火很旺，求平安很灵', 4, 0, '2026-01-19 14:20:00', '2026-01-19 14:20:00');
INSERT INTO `tb_travel_note_comment` VALUES (8, 1015, 6, 0, 0, '摇橹船太治愈了，周末安排', 7, 0, '2026-01-29 15:00:00', '2026-01-29 15:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (9, 1, 6, 0, 0, '芦苇荡真的美，秋冬去最合适', 2, 0, '2026-01-31 10:40:00', '2026-01-31 10:40:00');
INSERT INTO `tb_travel_note_comment` VALUES (10, 1018, 7, 0, 0, '九溪烟树徒步路线记下了！', 5, 0, '2026-02-09 09:00:00', '2026-02-09 09:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (11, 1011, 7, 0, 0, '龙井茶配桂花糕，绝配', 4, 0, '2026-02-11 16:30:00', '2026-02-11 16:30:00');
INSERT INTO `tb_travel_note_comment` VALUES (12, 1013, 9, 0, 0, '雷峰夕照名不虚传，值得登塔', 9, 0, '2026-02-20 18:00:00', '2026-02-20 18:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (13, 1016, 9, 0, 0, '塔顶俯瞰西湖视野太开阔了', 6, 0, '2026-02-22 11:45:00', '2026-02-22 11:45:00');
INSERT INTO `tb_travel_note_comment` VALUES (14, 1014, 10, 0, 0, '六桥烟柳，春天去最美', 7, 0, '2026-03-03 10:00:00', '2026-03-03 10:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (15, 1019, 10, 0, 0, '苏堤走完全程要多久呀？', 3, 0, '2026-03-05 13:25:00', '2026-03-05 13:25:00');
INSERT INTO `tb_travel_note_comment` VALUES (16, 1015, 11, 0, 0, '塔下江风真的凉快，夏天避暑好去处', 5, 0, '2026-03-14 15:00:00', '2026-03-14 15:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (17, 1012, 11, 0, 0, '看潮季去能看到大潮吗？', 2, 0, '2026-03-16 09:10:00', '2026-03-16 09:10:00');
INSERT INTO `tb_travel_note_comment` VALUES (18, 1020, 12, 0, 0, '河坊街小吃真的多，定胜糕好吃', 6, 0, '2026-03-25 19:00:00', '2026-03-25 19:00:00');
INSERT INTO `tb_travel_note_comment` VALUES (19, 1017, 13, 0, 0, '满陇桂雨秋天去，香到醉人', 8, 0, '2026-04-05 10:20:00', '2026-04-05 10:20:00');
INSERT INTO `tb_travel_note_comment` VALUES (20, 1010, 13, 0, 0, '桂花藕粉种草了，去尝尝', 4, 0, '2026-04-07 14:40:00', '2026-04-07 14:40:00');

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_follow`(`user_id`, `follow_user_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 32 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------
INSERT INTO `tb_follow` VALUES (3, 1, 1011, '2026-08-06 00:38:49');
INSERT INTO `tb_follow` VALUES (4, 1, 2, '2026-08-06 05:14:22');
INSERT INTO `tb_follow` VALUES (5, 1, 1010, '2026-08-06 05:14:22');
INSERT INTO `tb_follow` VALUES (6, 1011, 1, '2026-08-06 05:14:22');
INSERT INTO `tb_follow` VALUES (7, 1012, 1, '2026-08-06 05:14:22');
INSERT INTO `tb_follow` VALUES (8, 1010, 2, '2026-08-06 05:14:22');
INSERT INTO `tb_follow` VALUES (14, 1013, 1014, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (15, 1014, 1013, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (16, 1015, 1016, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (17, 1016, 1015, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (18, 1017, 1018, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (19, 1018, 1017, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (20, 1019, 1020, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (21, 1020, 1019, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (22, 1013, 1010, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (23, 1015, 1012, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (24, 1017, 1011, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (25, 1019, 1010, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (26, 1010, 1013, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (27, 1010, 1015, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (28, 1010, 1017, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (29, 1011, 1014, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (30, 1011, 1016, '2026-08-07 04:50:23');
INSERT INTO `tb_follow` VALUES (31, 1012, 1018, '2026-08-07 04:50:23');

-- ----------------------------
-- Table structure for tb_seckill_ticket
-- ----------------------------
DROP TABLE IF EXISTS `tb_seckill_ticket`;
CREATE TABLE `tb_seckill_ticket`  (
  `ticket_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的门票的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '生效时间',
  `end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`ticket_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀门票表，与门票是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_seckill_ticket
-- ----------------------------
-- 注：stock 为初始库存 10；运行库中该库存已被秒杀下单消费扣减至 9，种子脚本按初始值写入。
INSERT INTO `tb_seckill_ticket` VALUES (10, 10, '2026-07-25 10:00:00', '2026-08-01 00:00:00', '2026-08-31 23:59:59', '2026-07-25 10:00:00');
-- 千岛湖游船（ticket 21）stock=50、乌镇西栅日游（ticket 22）stock=30，begin 2026-08-07 00:00:00，end +7 天。
INSERT INTO `tb_seckill_ticket` VALUES (21, 50, '2026-08-07 04:50:23', '2026-08-07 00:00:00', '2026-08-14 00:00:00', '2026-08-07 04:50:23');
INSERT INTO `tb_seckill_ticket` VALUES (22, 30, '2026-08-07 04:50:23', '2026-08-07 00:00:00', '2026-08-14 00:00:00', '2026-08-07 04:50:23');

-- ----------------------------
-- Table structure for tb_attraction
-- ----------------------------
DROP TABLE IF EXISTS `tb_attraction`;
CREATE TABLE `tb_attraction`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '景点名称',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '景点类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '景点图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '所在区域，例如西湖风景区',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价（门票参考价），取整数',
  `sold` int(10) UNSIGNED NOT NULL COMMENT '累计接待人次',
  `comments` int(10) UNSIGNED NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '开放时间，例如 08:00-17:30',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_attraction
-- ----------------------------
INSERT INTO `tb_attraction` VALUES (1, '西湖风景名胜区', 6, '/imgs/attraction/1/0.jpg,/imgs/attraction/1/1.jpg', '西湖风景区', '杭州市西湖区龙井路1号', 120.14853, 30.250566, 0, 4215, 3035, 37, '00:00-24:00', '2026-01-01 09:00:00', '2026-01-02 09:00:00');
INSERT INTO `tb_attraction` VALUES (2, '灵隐寺', 5, '/imgs/attraction/2/0.jpg,/imgs/attraction/2/1.jpg,/imgs/attraction/2/2.jpg', '西湖风景区', '杭州市西湖区灵隐路1号', 120.101389, 30.240318, 75, 2160, 1460, 46, '07:00-18:15', '2026-01-01 10:00:00', '2026-01-02 10:00:00');
INSERT INTO `tb_attraction` VALUES (3, '雷峰塔', 2, '/imgs/attraction/3/0.jpg,/imgs/attraction/3/1.png,/imgs/attraction/3/2.png', '西湖风景区', '杭州市西湖区南山路15号', 120.149242, 30.233128, 40, 12035, 8045, 47, '08:00-19:00', '2026-01-01 11:00:00', '2026-01-02 11:00:00');
INSERT INTO `tb_attraction` VALUES (4, '西溪国家湿地公园', 7, '/imgs/attraction/4/0.jpg,/imgs/attraction/4/1.jpg', '西溪湿地', '杭州市西湖区天目山路519号', 120.063009, 30.274085, 80, 13519, 9529, 49, '08:00-17:30', '2026-01-01 12:00:00', '2026-01-02 12:00:00');
INSERT INTO `tb_attraction` VALUES (5, '宋城景区', 3, '/imgs/attraction/5/0.jpg,/imgs/attraction/5/1.jpg,/imgs/attraction/5/2.jpg', '之江度假区', '杭州市西湖区之江路148号', 120.121478, 30.187408, 290, 4125, 2764, 49, '10:00-21:00', '2026-01-02 09:00:00', '2026-01-03 09:00:00');
INSERT INTO `tb_attraction` VALUES (6, '六和塔', 2, '/imgs/attraction/6/0.jpg,/imgs/attraction/6/1.jpg,/imgs/attraction/6/2.jpg', '之江度假区', '杭州市西湖区之江路16号', 120.128899, 30.207098, 30, 9531, 7324, 46, '06:30-18:00', '2026-01-02 10:00:00', '2026-01-03 10:00:00');
INSERT INTO `tb_attraction` VALUES (7, '断桥', 1, '/imgs/attraction/7/0.jpg,/imgs/attraction/7/1.jpg,/imgs/attraction/7/2.jpg', '西湖风景区', '杭州市西湖区白堤东端', 120.152378, 30.257255, 0, 2631, 1320, 47, '00:00-24:00', '2026-01-02 11:00:00', '2026-01-03 11:00:00');
INSERT INTO `tb_attraction` VALUES (8, '苏堤', 1, '/imgs/attraction/8/0.jpg,/imgs/attraction/8/1.jpg,/imgs/attraction/8/2.jpg', '西湖风景区', '杭州市西湖区苏堤', 120.135287, 30.246923, 0, 2406, 1206, 46, '00:00-24:00', '2026-01-02 12:00:00', '2026-01-03 12:00:00');
INSERT INTO `tb_attraction` VALUES (9, '清河坊历史文化街区', 8, '/imgs/attraction/9/0.jpg,/imgs/attraction/9/1.jpg,/imgs/attraction/9/2.jpg', '吴山广场', '杭州市上城区河坊街', 120.169052, 30.246853, 0, 2763, 1363, 44, '09:00-22:00', '2026-01-03 09:00:00', '2026-01-04 09:00:00');
INSERT INTO `tb_attraction` VALUES (10, '杭州动物园', 9, '/imgs/attraction/10/0.jpg,/imgs/attraction/10/1.jpg,/imgs/attraction/10/2.jpg', '西湖风景区', '杭州市西湖区虎跑路40号', 120.141899, 30.22518, 20, 26891, 902, 37, '07:00-17:00', '2026-01-03 10:00:00', '2026-01-04 10:00:00');
INSERT INTO `tb_attraction` VALUES (11, '浙江省博物馆(孤山馆区)', 4, '/imgs/attraction/11/0.jpg,/imgs/attraction/11/1.jpg,/imgs/attraction/11/2.jpg', '西湖风景区', '杭州市西湖区孤山路25号', 120.137619, 30.251764, 0, 35977, 5684, 47, '09:00-17:00', '2026-01-03 11:00:00', '2026-01-04 11:00:00');
INSERT INTO `tb_attraction` VALUES (12, '最忆是杭州演出', 10, '/imgs/attraction/12/0.jpg,/imgs/attraction/12/1.png', '西湖风景区', '杭州市西湖区北山路84号', 120.14523, 30.25861, 260, 6444, 235, 46, '19:30-21:00', '2026-01-03 12:00:00', '2026-01-04 12:00:00');
INSERT INTO `tb_attraction` VALUES (13, '龙井村', 1, '/imgs/attraction/13/0.jpg,/imgs/attraction/13/1.jpg,/imgs/attraction/13/2.jpg', '西湖风景区', '杭州市西湖区龙井村', 120.11532, 30.22845, 0, 18997, 1857, 41, '00:00-24:00', '2026-01-04 09:00:00', '2026-01-05 09:00:00');
INSERT INTO `tb_attraction` VALUES (14, '杭州乐园', 3, '/imgs/attraction/14/0.jpg,/imgs/attraction/14/1.jpg,/imgs/attraction/14/2.jpg', '萧山湘湖', '杭州市萧山区湘湖路999号', 120.24561, 30.15532, 160, 17771, 685, 47, '09:30-17:00', '2026-01-04 10:00:00', '2026-01-05 10:00:00');
INSERT INTO `tb_attraction` VALUES (15, '太子湾公园', 7, '/imgs/attraction/15/0.jpg,/imgs/attraction/15/1.jpg,/imgs/attraction/15/2.jpg', '西湖区', '杭州市西湖区南山路6号', 120.145, 30.232, 0, 3200, 1560, 46, '08:00-17:30', '2026-08-01 09:00:00', '2026-08-02 09:00:00');
INSERT INTO `tb_attraction` VALUES (16, '满觉陇', 1, '/imgs/attraction/16/0.jpg,/imgs/attraction/16/1.jpg,/imgs/attraction/16/2.jpg', '西湖区', '杭州市西湖区满觉陇路75号', 120.131, 30.211, 0, 2100, 980, 45, '全天开放', '2026-08-01 10:00:00', '2026-08-02 10:00:00');
INSERT INTO `tb_attraction` VALUES (17, '千岛湖风景区', 6, '/imgs/attraction/17/0.jpg,/imgs/attraction/17/1.jpg,/imgs/attraction/17/2.jpg', '淳安县', '杭州市淳安县千岛湖镇梦姑路', 119.04727, 29.60438, 150, 9800, 3560, 47, '08:00-17:30', '2026-08-02 09:00:00', '2026-08-03 09:00:00');
INSERT INTO `tb_attraction` VALUES (18, '乌镇景区', 8, '/imgs/attraction/18/0.jpg,/imgs/attraction/18/1.jpg,/imgs/attraction/18/2.jpg', '桐乡市', '浙江省嘉兴市桐乡市乌镇镇', 120.48807, 30.74142, 150, 11500, 4200, 48, '09:00-22:00', '2026-08-02 10:00:00', '2026-08-03 10:00:00');
INSERT INTO `tb_attraction` VALUES (19, '湘湖旅游度假区', 6, '/imgs/attraction/19/0.jpg,/imgs/attraction/19/1.jpg,/imgs/attraction/19/2.jpg', '萧山区', '杭州市萧山区湘湖路', 120.22457, 30.15919, 0, 5200, 1860, 44, '全天开放', '2026-08-03 09:00:00', '2026-08-04 09:00:00');
INSERT INTO `tb_attraction` VALUES (20, '良渚古城遗址公园', 2, '/imgs/attraction/20/0.jpg,/imgs/attraction/20/1.jpg,/imgs/attraction/20/2.jpg', '余杭区', '杭州市余杭区凤都路与104国道交叉口', 119.97658, 30.39779, 60, 6100, 2380, 49, '09:00-17:00', '2026-08-03 10:00:00', '2026-08-04 10:00:00');

-- ----------------------------
-- Table structure for tb_attraction_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_attraction_type`;
CREATE TABLE `tb_attraction_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_attraction_type
-- ----------------------------
INSERT INTO `tb_attraction_type` VALUES (1, '自然风光', '/imgs/types/ms.jpg', 1, '2026-01-02 09:00:00', '2026-01-02 09:00:00');
INSERT INTO `tb_attraction_type` VALUES (2, '人文古迹', '/imgs/types/KTV.jpg', 2, '2026-01-02 09:10:00', '2026-01-02 09:10:00');
INSERT INTO `tb_attraction_type` VALUES (3, '主题乐园', '/imgs/types/lrmf.jpg', 3, '2026-01-02 09:20:00', '2026-01-02 09:20:00');
INSERT INTO `tb_attraction_type` VALUES (4, '博物馆', '/imgs/types/jsyd.jpg', 10, '2026-01-02 09:30:00', '2026-01-02 09:30:00');
INSERT INTO `tb_attraction_type` VALUES (5, '寺庙', '/imgs/types/amzl.jpg', 5, '2026-01-02 10:00:00', '2026-01-02 10:00:00');
INSERT INTO `tb_attraction_type` VALUES (6, '湖泊山水', '/imgs/types/spa.jpg', 6, '2026-01-02 10:10:00', '2026-01-02 10:10:00');
INSERT INTO `tb_attraction_type` VALUES (7, '公园', '/imgs/types/qzyl.jpg', 7, '2026-01-02 10:20:00', '2026-01-02 10:20:00');
INSERT INTO `tb_attraction_type` VALUES (8, '文化街区', '/imgs/types/jiuba.jpg', 8, '2026-01-02 10:30:00', '2026-01-02 10:30:00');
INSERT INTO `tb_attraction_type` VALUES (9, '亲子出游', '/imgs/types/hpg.jpg', 9, '2026-01-02 11:00:00', '2026-01-02 11:00:00');
INSERT INTO `tb_attraction_type` VALUES (10, '演出展览', '/imgs/types/mjmj.jpg', 4, '2026-01-02 11:10:00', '2026-01-02 11:10:00');

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `bg_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '背景图路径',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1021 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` VALUES (1, '13686869696', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '小鱼同学', '/imgs/user/avatar/1.png', '/imgs/user/bg/1.jpg', '2026-01-08 10:00:00', '2026-01-10 10:00:00');
INSERT INTO `tb_user` VALUES (2, '13838411438', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '可可今天不吃肉', '/imgs/user/avatar/2.png', '/imgs/user/bg/2.jpg', '2026-01-15 14:00:00', '2026-01-17 14:00:00');
INSERT INTO `tb_user` VALUES (4, '13456789011', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', 'user_slxaxy2au9f3tanffaxr', '/imgs/user/avatar/4.png', '/imgs/user/bg/4.jpg', '2026-02-01 09:30:00', '2026-02-03 09:30:00');
INSERT INTO `tb_user` VALUES (5, '13456789001', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', 'user_n0bb8mwwg4', '/imgs/user/avatar/5.png', '/imgs/user/bg/5.jpg', '2026-02-10 16:00:00', '2026-02-12 16:00:00');
INSERT INTO `tb_user` VALUES (1010, '13912345678', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '杭城小筑', '/imgs/user/avatar/1010.png', '/imgs/user/bg/1010.jpg', '2026-03-01 10:20:00', '2026-03-03 10:20:00');
INSERT INTO `tb_user` VALUES (1011, '13712345678', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '西湖边的猫', '/imgs/user/avatar/1011.png', '/imgs/user/bg/1011.jpg', '2026-03-08 11:40:00', '2026-03-10 11:40:00');
INSERT INTO `tb_user` VALUES (1012, '15812345678', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '背包客阿杰', '/imgs/user/avatar/1012.png', '/imgs/user/bg/1012.jpg', '2026-03-15 15:10:00', '2026-03-17 15:10:00');
INSERT INTO `tb_user` VALUES (1013, '13712340001', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '西湖听雨人', '/imgs/user/avatar/1013.png', '/imgs/user/bg/1013.jpg', '2026-04-01 09:05:00', '2026-04-03 09:05:00');
INSERT INTO `tb_user` VALUES (1014, '13812340002', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '断桥边喝咖啡', '/imgs/user/avatar/1014.png', '/imgs/user/bg/1014.jpg', '2026-04-08 13:25:00', '2026-04-10 13:25:00');
INSERT INTO `tb_user` VALUES (1015, '13912340003', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '茶园小鹿', '/imgs/user/avatar/1015.png', '/imgs/user/bg/1015.jpg', '2026-04-15 10:45:00', '2026-04-17 10:45:00');
INSERT INTO `tb_user` VALUES (1016, '15012340004', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '杭漂摄影狮', '/imgs/user/avatar/1016.png', '/imgs/user/bg/1016.jpg', '2026-04-22 18:30:00', '2026-04-24 18:30:00');
INSERT INTO `tb_user` VALUES (1017, '15112340005', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '山野拾光', '/imgs/user/avatar/1017.png', '/imgs/user/bg/1017.jpg', '2026-05-01 08:50:00', '2026-05-03 08:50:00');
INSERT INTO `tb_user` VALUES (1018, '15212340006', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '桂花巷姑娘', '/imgs/user/avatar/1018.png', '/imgs/user/bg/1018.jpg', '2026-05-08 12:15:00', '2026-05-10 12:15:00');
INSERT INTO `tb_user` VALUES (1019, '15512340007', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '钱塘观潮客', '/imgs/user/avatar/1019.png', '/imgs/user/bg/1019.jpg', '2026-05-15 14:55:00', '2026-05-17 14:55:00');
INSERT INTO `tb_user` VALUES (1020, '15712340008', '$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K', '良渚拾遗', '/imgs/user/avatar/1020.png', '/imgs/user/bg/1020.jpg', '2026-05-22 09:35:00', '2026-05-24 09:35:00');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `follow_visible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '关注列表是否公开，1=可见，0=不可见',
  `fans_visible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '粉丝列表是否公开，1=可见，0=不可见',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------
INSERT INTO `tb_user_info` VALUES (1010, '杭州', '喜欢在西湖边散步的杭城小居民', 12, 8, 1, NULL, 66, 1, 1, 1, '2026-03-01 10:20:00', '2026-03-03 10:20:00');
INSERT INTO `tb_user_info` VALUES (1011, '杭州', '一只爱晒太阳的猫，最爱西湖的黄昏', 25, 15, 2, NULL, 88, 2, 1, 1, '2026-03-08 11:40:00', '2026-03-10 11:40:00');
INSERT INTO `tb_user_info` VALUES (1012, '杭州', '背着包走过很多城市，最爱还是杭州', 40, 20, 1, NULL, 120, 3, 1, 1, '2026-03-15 15:10:00', '2026-03-17 15:10:00');
INSERT INTO `tb_user_info` VALUES (1013, '杭州', '喜欢在西湖边听雨看湖发呆的人', 2, 2, 1, NULL, 36, 0, 1, 1, '2026-04-01 09:05:00', '2026-04-03 09:05:00');
INSERT INTO `tb_user_info` VALUES (1014, '杭州', '断桥边有一家咖啡店，我常坐一下午', 2, 1, 0, NULL, 52, 0, 1, 1, '2026-04-08 13:25:00', '2026-04-10 13:25:00');
INSERT INTO `tb_user_info` VALUES (1015, '杭州', '龙井茶园里长大的姑娘，最爱九溪烟树', 2, 2, 1, NULL, 78, 0, 1, 1, '2026-04-15 10:45:00', '2026-04-17 10:45:00');
INSERT INTO `tb_user_info` VALUES (1016, '杭州', '扛着相机走遍杭州，记录山水光影', 2, 1, 1, NULL, 91, 0, 1, 1, '2026-04-22 18:30:00', '2026-04-24 18:30:00');
INSERT INTO `tb_user_info` VALUES (1017, '杭州', '周末就往山里跑，专挑人少景美的地方', 2, 2, 0, NULL, 24, 0, 1, 1, '2026-05-01 08:50:00', '2026-05-03 08:50:00');
INSERT INTO `tb_user_info` VALUES (1018, '杭州', '住在桂花巷，秋天整个巷子都是香的', 2, 1, 1, NULL, 65, 0, 1, 1, '2026-05-08 12:15:00', '2026-05-10 12:15:00');
INSERT INTO `tb_user_info` VALUES (1019, '杭州', '每年钱塘潮都要去看，也爱江边落日', 1, 2, 0, NULL, 43, 0, 1, 1, '2026-05-15 14:55:00', '2026-05-17 14:55:00');
INSERT INTO `tb_user_info` VALUES (1020, '杭州', '良渚遗址的忠实访客，常去听五千年故事', 1, 1, 0, NULL, 88, 0, 1, 1, '2026-05-22 09:35:00', '2026-05-24 09:35:00');

-- ----------------------------
-- Table structure for tb_ticket
-- ----------------------------
DROP TABLE IF EXISTS `tb_ticket`;
CREATE TABLE `tb_ticket`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `attraction_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '景点id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '门票标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通票；1,秒杀票',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_ticket
-- ----------------------------
INSERT INTO `tb_ticket` VALUES (1, 1, '西湖游船体验票', '购买当日有效', '全园通用\\n无需预约\\n凭二维码核销\\n不兑现、不找零', 4750, 5000, 0, 1, '2026-01-15 09:00:00', '2026-01-16 09:00:00');
INSERT INTO `tb_ticket` VALUES (10, 1, '西湖游船秒杀票', '限时特惠', '每人限购1张', 1, 100, 1, 1, '2026-08-01 09:00:00', '2026-08-02 09:00:00');
INSERT INTO `tb_ticket` VALUES (21, 17, '千岛湖游船秒杀票', '限时特惠', '每人限购1张\\n凭二维码核销\\n不可退改', 9900, 15000, 1, 1, '2026-08-07 04:50:23', '2026-08-07 04:50:23');
INSERT INTO `tb_ticket` VALUES (22, 18, '乌镇西栅日游秒杀票', '限时特惠', '每人限购1张\\n凭二维码核销\\n不可退改', 12900, 19000, 1, 1, '2026-08-07 04:50:23', '2026-08-07 04:50:23');

-- ----------------------------
-- Table structure for tb_ticket_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_ticket_order`;
CREATE TABLE `tb_ticket_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `ticket_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的门票id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_ticket_order
-- ----------------------------
-- 注：订单是运行时业务数据（运行库有 1 条秒杀订单 622874050099675137），种子脚本不写入，新环境订单表留空。

SET FOREIGN_KEY_CHECKS = 1;
