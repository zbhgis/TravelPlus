package com.travelplus.agent.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【企业级客服Agent的实现】平台客服知识库（轻量 RAG 检索层 + 热更新）。
 * <p>
 * 实现细节：
 * - 语料来源：优先读取 travelplus.support.knowledge-path 指定的外部 Markdown（便于运营热更新），
 *   未配置时回退 classpath:knowledge/support-faq.md；按 `## 标题` 切分为知识条目；
 * - 热更新：管理员调用 reload() 重新读取文件并原子替换条目，无需重启；
 * - 检索策略：中文二元组 + 英文/数字词条 + 同义词扩展，标题命中加权、正文词频加权、
 *   按文档频率做 IDF 降权，纯内存计算，零外部依赖；
 * - 接地方式：每轮把 Top3 条目注入 system 提示词并附来源标题；
 *   另提供 search_support_kb 工具供模型显式补充检索；
 * - 可替换性：检索接口保持 search/buildContext 两个方法，后续切 Elasticsearch/向量库只需替换本类实现。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SupportKnowledgeBase {

    /** 单次注入 system 的最大知识条目数 */
    private static final int MAX_CONTEXT_DOCS = 3;

    /** 单条知识注入/返回的最大字符数（控制 token） */
    private static final int MAX_DOC_CHARS = 700;

    /** 高频无区分度二元组（避免"怎么办"这类词把所有文档都拉进来） */
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "如何", "可以", "我的", "请问", "一下", "这个", "那个",
            "你们", "是不", "不是", "有没", "没有", "需要", "现在");

    /**
     * 【客服Agent完善】口语/错别字同义词扩展：用户说"登陆不上""退票""抢票"时，
     * 追加标准词一起参与检索，提升 FAQ 召回率（不替换原词，避免丢失原文语义）。
     */
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put("登陆", "登录");
        SYNONYMS.put("登不上", "登录 失败");
        SYNONYMS.put("退票", "退款");
        SYNONYMS.put("退单", "退款");
        SYNONYMS.put("抢票", "秒杀");
        SYNONYMS.put("抢不到", "秒杀 库存");
        SYNONYMS.put("分数", "积分");
        SYNONYMS.put("优惠卷", "优惠券");
        SYNONYMS.put("没到账", "退款 到账");
        SYNONYMS.put("扣款", "支付 订单");
        SYNONYMS.put("打不开", "登录 功能");
    }

    private final ResourceLoader resourceLoader;
    private final SupportProperties props;

    /** 知识条目（volatile 不可变快照，reload 时整体替换，检索无锁） */
    private volatile List<KbDoc> docs = List.of();

    /** 当前语料来源（classpath 或外部文件路径），供管理端展示 */
    private volatile String source = "";

    public SupportKnowledgeBase(ResourceLoader resourceLoader, SupportProperties props) throws IOException {
        this.resourceLoader = resourceLoader;
        this.props = props;
        load();
    }

    /** 知识条目：标题（回答来源）+ 正文 */
    private record KbDoc(String title, String content) {
    }

    /** 检索得分：文档 + 相关度 */
    private record Scored(KbDoc doc, double score) {
    }

    /**
     * 构建注入 system 的知识库上下文（Top3，带来源标题）。
     *
     * @param query 本轮用户消息
     * @return 格式化上下文；无命中返回空串（不阻断主链路）
     */
    public String buildContext(String query) {
        List<Scored> hits = rank(query, MAX_CONTEXT_DOCS);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("平台知识库（回答平台规则/流程问题时必须以此为准，并在回答末尾注明「来源：标题」）：\n");
        for (Scored s : hits) {
            sb.append("【").append(s.doc().title()).append("】\n")
                    .append(truncate(s.doc().content(), MAX_DOC_CHARS)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 显式检索（供 search_support_kb 工具调用）。
     *
     * @param query 检索关键词
     * @param topK  返回条数
     * @return 知识条目列表 [{title, content}]
     */
    public List<Map<String, String>> search(String query, int topK) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Scored s : rank(query, Math.max(1, topK))) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", s.doc().title());
            item.put("content", truncate(s.doc().content(), MAX_DOC_CHARS));
            out.add(item);
        }
        return out;
    }

    /** 【客服Agent完善】热重载知识库（管理端调用），返回最新统计 */
    public synchronized Map<String, Object> reload() throws IOException {
        load();
        return stats();
    }

    /** 知识库统计：来源、条目数、标题列表、总字数、更新时间 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("docCount", docs.size());
        out.put("titles", docs.stream().map(KbDoc::title).toList());
        out.put("totalChars", docs.stream().mapToInt(d -> d.content().length()).sum());
        out.put("updatedAt", LocalDateTime.now().toString());
        return out;
    }

    /** 加载语料并原子替换（启动 / 热重载共用） */
    private synchronized void load() throws IOException {
        String content;
        String src;
        String externalPath = props.getKnowledgePath();
        if (externalPath != null && !externalPath.isBlank()) {
            Path path = Path.of(externalPath);
            if (!Files.exists(path)) {
                throw new IllegalStateException("客服知识库文件不存在: " + externalPath);
            }
            content = Files.readString(path, StandardCharsets.UTF_8);
            src = path.toAbsolutePath().toString();
        } else {
            Resource res = resourceLoader.getResource("classpath:knowledge/support-faq.md");
            if (!res.exists()) {
                throw new IllegalStateException("客服知识库缺失: classpath:knowledge/support-faq.md");
            }
            content = res.getContentAsString(StandardCharsets.UTF_8);
            src = "classpath:knowledge/support-faq.md";
        }
        List<KbDoc> parsed = new ArrayList<>();
        parse(content, parsed);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("客服知识库为空，请检查 Markdown 的 ## 章节");
        }
        this.docs = List.copyOf(parsed);
        this.source = src;
        log.info("【客服知识库】加载完成: {} 篇 source={}", docs.size(), src);
    }

    /** 解析 Markdown：`## ` 开篇，`# ` 文档总标题忽略 */
    private void parse(String markdown, List<KbDoc> target) {
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("## ")) {
                addDoc(title, body, target);
                title = line.substring(3).trim();
                body.setLength(0);
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        addDoc(title, body, target);
    }

    private void addDoc(String title, StringBuilder body, List<KbDoc> target) {
        if (title == null) {
            return;
        }
        String content = body.toString().trim();
        if (!content.isEmpty()) {
            target.add(new KbDoc(title, content));
        }
    }

    /** 轻量检索：中文二元组 + 英文/数字词条 → IDF 加权打分 → 降序截断 */
    private List<Scored> rank(String query, int limit) {
        List<KbDoc> snapshot = docs;
        Set<String> terms = terms(expand(query));
        if (terms.isEmpty() || snapshot.isEmpty()) {
            return List.of();
        }
        /* 文档频率：出现在多少篇知识里（用于 IDF 降权，"订单"这类全局词权重低） */
        Map<String, Integer> df = new LinkedHashMap<>();
        for (String term : terms) {
            int count = 0;
            for (KbDoc d : snapshot) {
                if (d.title().contains(term) || d.content().contains(term)) {
                    count++;
                }
            }
            df.put(term, count);
        }
        List<Scored> out = new ArrayList<>();
        for (KbDoc d : snapshot) {
            double score = 0;
            for (String term : terms) {
                int titleHit = d.title().contains(term) ? 3 : 0;
                int bodyHit = countOccurrences(d.content(), term);
                int hits = titleHit + bodyHit;
                if (hits == 0) {
                    continue;
                }
                double idf = Math.log((snapshot.size() + 1.0) / (df.getOrDefault(term, 0) + 1.0)) + 1.0;
                score += idf * (1 + Math.log(hits));
            }
            if (score > 0) {
                out.add(new Scored(d, score));
            }
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** 同义词扩展：原文 + 命中词的标准词（空格分隔，供分词器合并） */
    private String expand(String query) {
        String text = query == null ? "" : query;
        StringBuilder sb = new StringBuilder(text);
        for (Map.Entry<String, String> entry : SYNONYMS.entrySet()) {
            if (text.contains(entry.getKey())) {
                sb.append(' ').append(entry.getValue());
            }
        }
        return sb.toString();
    }

    /** 查询分词：保留中文连续段与英文/数字，中文段输出二元组 + 整段（2~8 字） */
    private Set<String> terms(String query) {
        String normalized = query == null ? ""
                : query.toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9]", " ");
        Set<String> out = new LinkedHashSet<>();
        for (String seg : normalized.split("\\s+")) {
            if (seg.isEmpty()) {
                continue;
            }
            boolean ascii = seg.codePoints().allMatch(c -> c < 128);
            if (ascii) {
                if (seg.length() >= 2) {
                    out.add(seg);
                }
                continue;
            }
            if (seg.length() >= 2 && seg.length() <= 8) {
                out.add(seg);
            }
            for (int i = 0; i < seg.length() - 1; i++) {
                String bigram = seg.substring(i, i + 2);
                if (!STOP_TERMS.contains(bigram)) {
                    out.add(bigram);
                }
            }
        }
        return out;
    }

    /** 子串出现次数（封顶 5，避免长文档词频刷分） */
    private int countOccurrences(String content, String term) {
        int count = 0;
        int idx = 0;
        while (count < 5 && (idx = content.indexOf(term, idx)) >= 0) {
            count++;
            idx += term.length();
        }
        return count;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
