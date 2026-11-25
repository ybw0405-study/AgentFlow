package com.agentflow.workflow.knowledge;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档知识库：写入时切片，检索时用 BM25 对切片打分。
 *
 * <p>刻意不引入向量库 / embedding 服务：全部计算在本地完成，离线可跑，
 * 也不必把文档内容外送第三方。词项同时包含中文单字与相邻二元组，使中文查询
 * 无需分词器也能召回；ASCII 连续字母数字按整词处理。
 *
 * <p>租户隔离：所有 SQL 带 tenant_id；内部检索接口另按 tenantId 复核每个 kbId，
 * 读不到其它租户的数据，也不泄露其存在性。
 */
@Service
public class KnowledgeService {

    /** 单文档原文上限（字符）。超过直接拒绝，避免一次写入撑爆切片表。 */
    public static final int MAX_DOCUMENT_CHARS = 200_000;
    /** 单文档切片上限。 */
    public static final int MAX_CHUNKS_PER_DOC = 500;
    /** 单次检索扫描的切片上限：过滤条件下推到 SQL 后仍要有硬上限。 */
    public static final int MAX_SCAN_CHUNKS = 20_000;

    private static final int CHUNK_TARGET = 420;
    private static final int CHUNK_MIN = 160;
    private static final int CHUNK_OVERLAP = 60;
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final double SCORE_SCALE = 3.0;

    private final KnowledgeMapper mapper;
    private final DocumentParser parser;

    public KnowledgeService(KnowledgeMapper mapper, DocumentParser parser) {
        this.mapper = mapper;
        this.parser = parser;
    }

    // ---------- 知识库 ----------

    public List<BaseDto> listBases(TenantInfo tenant) {
        return mapper.listBases(tenant.tenantId()).stream().map(this::toBaseDto).toList();
    }

    @Transactional
    public BaseDto createBase(TenantInfo tenant, String name, String description) {
        requireEditor(tenant);
        String id = UUID.randomUUID().toString();
        mapper.insertBase(new KnowledgeMapper.BaseRow(id, tenant.tenantId(), name, description,
                tenant.userId(), null, 0L, 0L));
        return new BaseDto(id, name, description, 0, 0, null);
    }

    @Transactional
    public void deleteBase(TenantInfo tenant, String id) {
        requireEditor(tenant);
        requireBase(tenant.tenantId(), id);
        mapper.deleteChunksByBase(id, tenant.tenantId());
        mapper.deleteDocumentsByBase(id, tenant.tenantId());
        mapper.softDeleteBase(id, tenant.tenantId());
    }

    // ---------- 文档 ----------

    public List<DocumentDto> listDocuments(TenantInfo tenant, String kbId) {
        requireBase(tenant.tenantId(), kbId);
        return mapper.listDocuments(kbId, tenant.tenantId()).stream()
                .map(this::toDocumentDto).toList();
    }

    @Transactional
    public DocumentDto addDocument(TenantInfo tenant, String kbId, String title, String content,
                                   String source) {
        requireEditor(tenant);
        requireBase(tenant.tenantId(), kbId);
        String text = normalise(content);
        if (text.isEmpty()) {
            throw invalid("文档内容不能为空");
        }
        String safeSource = source == null || source.isBlank() ? "TEXT" : source.trim();
        return insertDocument(tenant, kbId, title, safeSource, text);
    }

    /** 文件导入：解析 PDF/Office 提取正文后走与文本导入同一套切片 + 落库。 */
    @Transactional
    public DocumentDto addDocumentFromFile(TenantInfo tenant, String kbId, String title,
                                           String filename, byte[] bytes) {
        requireEditor(tenant);
        requireBase(tenant.tenantId(), kbId);
        DocumentParser.ParsedDocument parsed = parser.parse(filename, bytes);
        String text = normalise(parsed.text());
        if (text.isBlank()) {
            throw invalid("未从文件提取到文本：扫描件/图片型 PDF 请先 OCR 转文本再导入");
        }
        return insertDocument(tenant, kbId, title, parsed.source(), text);
    }

    private DocumentDto insertDocument(TenantInfo tenant, String kbId, String title,
                                       String source, String text) {
        if (text.length() > MAX_DOCUMENT_CHARS) {
            throw invalid("文档超过 " + MAX_DOCUMENT_CHARS + " 字符，请拆分后再导入");
        }
        List<String> chunks = split(text);
        if (chunks.size() > MAX_CHUNKS_PER_DOC) {
            throw invalid("切片超过 " + MAX_CHUNKS_PER_DOC + " 段，请拆分文档");
        }
        String docId = UUID.randomUUID().toString();
        String safeTitle = title == null || title.isBlank() ? "未命名文档" : title.trim();
        mapper.insertDocument(new KnowledgeMapper.DocumentRow(docId, tenant.tenantId(), kbId,
                safeTitle, source, text.length(), chunks.size(), tenant.userId(), null));
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            mapper.insertChunk(new KnowledgeMapper.ChunkRow(UUID.randomUUID().toString(),
                    tenant.tenantId(), kbId, docId, i, safeTitle, chunk,
                    tokenize(chunk).size()));
        }
        return new DocumentDto(docId, kbId, safeTitle, source, text.length(),
                chunks.size(), null);
    }

    @Transactional
    public void deleteDocument(TenantInfo tenant, String id) {
        requireEditor(tenant);
        KnowledgeMapper.DocumentRow row = mapper.findDocument(id, tenant.tenantId());
        if (row == null) {
            throw notFound();
        }
        mapper.deleteChunksByDocument(id, tenant.tenantId());
        mapper.softDeleteDocument(id, tenant.tenantId());
    }

    // ---------- 检索 ----------

    /** 用户侧自测：kbIds 先按本租户重新解析，传进来的越权 id 直接忽略。 */
    public SearchResult search(TenantInfo tenant, List<String> kbIds, String query,
                               int topK, double minScore) {
        return searchForTenant(tenant.tenantId(), kbIds, query, topK, minScore);
    }

    /** 引擎侧内部检索：tenantId 来自执行上下文，逐 kbId 复核归属。 */
    public SearchResult searchInternal(String tenantId, List<String> kbIds, String query,
                                       int topK, double minScore) {
        return searchForTenant(tenantId, kbIds, query, topK, minScore);
    }

    private SearchResult searchForTenant(String tenantId, List<String> kbIds, String query,
                                         int topK, double minScore) {
        String q = query == null ? "" : query.trim();
        int limit = Math.max(1, Math.min(topK, 50));
        double floor = Math.max(0.0, Math.min(minScore, 1.0));
        List<String> owned = distinctOwnedBases(tenantId, kbIds);
        if (owned.isEmpty() || q.isEmpty()) {
            return new SearchResult(List.of(), q, 0, 0);
        }
        List<KnowledgeMapper.ChunkRow> candidates =
                mapper.listChunksForSearch(tenantId, owned, MAX_SCAN_CHUNKS);
        if (candidates.isEmpty()) {
            return new SearchResult(List.of(), q, 0, 0);
        }
        List<String> queryTerms = tokenize(q);
        Set<String> uniqueTerms = new HashSet<>(queryTerms);
        List<Map<String, Integer>> frequencies = new ArrayList<>(candidates.size());
        List<Integer> lengths = new ArrayList<>(candidates.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0.0;
        for (KnowledgeMapper.ChunkRow row : candidates) {
            Map<String, Integer> freq = new HashMap<>();
            for (String term : tokenize(row.content())) {
                freq.merge(term, 1, Integer::sum);
            }
            int length = Math.max(1, row.tokenCount());
            frequencies.add(freq);
            lengths.add(length);
            totalLength += length;
            for (String term : uniqueTerms) {
                if (freq.containsKey(term)) {
                    documentFrequency.merge(term, 1, Integer::sum);
                }
            }
        }
        double avgLength = Math.max(1.0, totalLength / candidates.size());
        double documentCount = candidates.size();
        List<HitDto> hits = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            KnowledgeMapper.ChunkRow row = candidates.get(i);
            Map<String, Integer> freq = frequencies.get(i);
            double raw = 0.0;
            for (String term : uniqueTerms) {
                int tf = freq.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (documentCount - df + 0.5) / (df + 0.5));
                double norm = BM25_K1 * (1.0 - BM25_B
                        + BM25_B * lengths.get(i) / avgLength);
                raw += idf * (tf * (BM25_K1 + 1.0)) / (tf + norm);
            }
            if (raw <= 0.0) {
                continue;
            }
            // 有界归一化：分数落在 (0,1)，minScore 才有稳定含义。
            double score = 1.0 - Math.exp(-raw / SCORE_SCALE);
            if (score < floor) {
                continue;
            }
            hits.add(new HitDto(row.id(), row.documentId(), row.documentTitle(), row.kbId(),
                    row.ordinal(), row.content(), round(score)));
        }
        hits.sort(Comparator.comparingDouble(HitDto::score).reversed()
                .thenComparing(HitDto::chunkId));
        List<HitDto> top = hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
        return new SearchResult(top, q, hits.size(), candidates.size());
    }

    // ---------- 内部工具 ----------

    private List<String> distinctOwnedBases(String tenantId, List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> owned = new ArrayList<>();
        for (String kbId : kbIds) {
            if (kbId == null || kbId.isBlank() || !seen.add(kbId)) {
                continue;
            }
            if (mapper.findBase(kbId, tenantId) != null) {
                owned.add(kbId);
            }
        }
        return owned;
    }

    private KnowledgeMapper.BaseRow requireBase(String tenantId, String id) {
        KnowledgeMapper.BaseRow row = mapper.findBase(id, tenantId);
        if (row == null) {
            throw notFound();
        }
        return row;
    }

    private static void requireEditor(TenantInfo tenant) {
        if (!tenant.atLeast("EDITOR")) {
            throw new BusinessException(ErrorCode.WF_FORBIDDEN_001,
                    "editor role required");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.COM_VALIDATION_001, message);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.COM_NOT_FOUND_001, "resource not found");
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private BaseDto toBaseDto(KnowledgeMapper.BaseRow row) {
        return new BaseDto(row.id(), row.name(), row.description(), row.documentCount(),
                row.chunkCount(), row.createdAt() == null ? null
                        : row.createdAt().toInstant().toString());
    }

    private DocumentDto toDocumentDto(KnowledgeMapper.DocumentRow row) {
        return new DocumentDto(row.id(), row.kbId(), row.title(), row.source(),
                row.charCount(), row.chunkCount(), row.createdAt() == null ? null
                        : row.createdAt().toInstant().toString());
    }

    /** 归一空白：保留段落换行，压掉行内连续空格，去掉不可见控制字符。 */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u0000", "").replace("\ufeff", "");
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
                    out.deleteCharAt(out.length() - 1);
                }
                // 连续空行折叠成一个：段间空行对检索没有信息量，只会浪费切片字符。
                if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                pendingSpace = false;
            } else if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0 && out.charAt(out.length() - 1) != '\n';
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /**
     * 按字符窗口切片，优先在标点/换行处断开，段间保留重叠以维持上下文完整。
     * 纯确定性算法：同一文档每次导入得到相同切片，便于复现与调试。
     */
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(length, start + CHUNK_TARGET);
            int lower = Math.max(start + CHUNK_MIN, end - 80);
            if (lower < end) {
                int breakAt = -1;
                for (int i = end - 1; i >= lower; i--) {
                    if (isBreakPoint(text.charAt(i))) {
                        breakAt = i + 1;
                        break;
                    }
                }
                if (breakAt > start) {
                    end = breakAt;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
                if (chunks.size() > MAX_CHUNKS_PER_DOC) {
                    break;
                }
            }
            int next = Math.max(start + 1, end - CHUNK_OVERLAP);
            start = next >= length ? length : next;
        }
        return chunks;
    }

    private static boolean isBreakPoint(char c) {
        return c == '\n' || c == '。' || c == '！' || c == '？' || c == '；' || c == '.'
                || c == '!' || c == '?' || c == ';';
    }

    /**
     * 词项：ASCII 字母数字按整词（小写）；CJK 同时产单字与相邻二元组。
     * 中文查询「退款」因此能命中「退款流程」这类连续文本。
     */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return terms;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(terms, ascii);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c) && c < 128) {
                flushCjk(terms, cjk);
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(terms, ascii);
                flushCjk(terms, cjk);
            }
        }
        flushAscii(terms, ascii);
        flushCjk(terms, cjk);
        return terms;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff');
    }

    private static void flushAscii(List<String> terms, StringBuilder buffer) {
        if (buffer.length() > 0) {
            terms.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static void flushCjk(List<String> terms, StringBuilder buffer) {
        if (buffer.length() == 0) {
            return;
        }
        String run = buffer.toString();
        for (int i = 0; i < run.length(); i++) {
            terms.add(String.valueOf(run.charAt(i)));
            if (i + 1 < run.length()) {
                terms.add(run.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    public record BaseDto(String id, String name, String description, long documentCount,
                          long chunkCount, String createdAt) {
    }

    public record DocumentDto(String id, String kbId, String title, String source,
                              int charCount, int chunkCount, String createdAt) {
    }

    public record HitDto(String chunkId, String documentId, String documentTitle, String kbId,
                         int ordinal, String content, double score) {
    }

    public record ChunkDto(String chunkId, String kbId, String documentId, String documentTitle,
                           int ordinal, String content) {
    }

    /** 引擎懒索引用：切片清单（控制面仍是权威源，engine 按 content hash 增量嵌入）。 */
    public List<ChunkDto> listChunksInternal(String tenantId, List<String> kbIds, int limit) {
        List<String> owned = distinctOwnedBases(tenantId, kbIds);
        if (owned.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, MAX_SCAN_CHUNKS));
        return mapper.listChunksForSearch(tenantId, owned, cap).stream()
                .map(row -> new ChunkDto(row.id(), row.kbId(), row.documentId(),
                        row.documentTitle(), row.ordinal(), row.content()))
                .toList();
    }

    public record SearchResult(List<HitDto> results, String query, int matchedChunks,
                               int scannedChunks) {
    }
}
