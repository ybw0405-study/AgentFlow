package com.agentflow.workflow.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.tenant.TenantContext.TenantInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 文档切片、中文词项、BM25 相关性与租户隔离的单元测试。 */
class KnowledgeServiceTest {

    private KnowledgeMapper mapper;
    private KnowledgeService service;

    private final TenantInfo editor = new TenantInfo("space-1", "user-1", "EDITOR");
    private final TenantInfo viewer = new TenantInfo("space-1", "user-2", "VIEWER");

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeMapper.class);
        service = new KnowledgeService(mapper, mock(DocumentParser.class));
    }

    @Test
    void baseSelectProjectsEveryRecordColumn() {
        // MyBatis 构造器自动映射要求结果列数与 record 字段数一致：
        // findBase 曾因只查 6 列而在实机返回 500，这里守住两个统计列。
        assertTrue(KnowledgeMapper.BASE_SELECT.contains("documentCount"));
        assertTrue(KnowledgeMapper.BASE_SELECT.contains("chunkCount"));
    }

    @Test
    void normaliseCollapsesWhitespaceAndKeepsParagraphs() {
        assertEquals("第一段\n第二段 词语",
                KnowledgeService.normalise(" 第一段 \r\n\r\n 第二段 词语 "));
        assertEquals("", KnowledgeService.normalise(" \n \t "));
    }

    @Test
    void splitKeepsOverlapAndBoundsChunkCount() {
        String text = "退款流程说明。".repeat(300);
        List<String> chunks = KnowledgeService.split(text);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.size() <= KnowledgeService.MAX_CHUNKS_PER_DOC + 1);
        assertTrue(chunks.stream().allMatch(c -> !c.isBlank()));
        assertTrue(chunks.get(0).length() <= 500);
    }

    @Test
    void tokenizeProducesChineseUnigramsBigramsAndAsciiWords() {
        List<String> terms = KnowledgeService.tokenize("退款 Refund2026");
        assertTrue(terms.contains("退"));
        assertTrue(terms.contains("退款"));
        assertTrue(terms.contains("款"));
        assertTrue(terms.contains("refund2026"));
    }

    @Test
    void addDocumentStoresEveryChunk() {
        baseExists("kb-1", "space-1");
        String text = "退款政策：七天内可申请退款。".repeat(80);
        int expected = KnowledgeService.split(KnowledgeService.normalise(text)).size();
        KnowledgeService.DocumentDto dto =
                service.addDocument(editor, "kb-1", "退款政策", text, "TEXT");
        assertEquals(expected, dto.chunkCount());
        verify(mapper, times(1)).insertDocument(any());
        verify(mapper, times(expected)).insertChunk(any());
    }

    @Test
    void addDocumentRejectsViewerEmptyAndOversize() {
        baseExists("kb-1", "space-1");
        assertThrows(BusinessException.class,
                () -> service.addDocument(viewer, "kb-1", "t", "内容", "TEXT"));
        assertThrows(BusinessException.class,
                () -> service.addDocument(editor, "kb-1", "t", " ", "TEXT"));
        assertThrows(BusinessException.class,
                () -> service.addDocument(editor, "kb-1", "t",
                        "x".repeat(KnowledgeService.MAX_DOCUMENT_CHARS + 1), "TEXT"));
    }

    @Test
    void deleteDocumentAlsoRemovesChunks() {
        when(mapper.findDocument("doc-1", "space-1")).thenReturn(new KnowledgeMapper.DocumentRow(
                "doc-1", "space-1", "kb-1", "t", "TEXT", 10, 1, "user-1", null));
        service.deleteDocument(editor, "doc-1");
        verify(mapper, times(1)).deleteChunksByDocument("doc-1", "space-1");
        verify(mapper, times(1)).softDeleteDocument("doc-1", "space-1");
    }

    @Test
    void searchRanksRelevantChunkFirst() {
        baseExists("kb-1", "space-1");
        when(mapper.listChunksForSearch(eq("space-1"), anyList(), anyInt())).thenReturn(List.of(
                chunk("c-1", "登录失败时请先检查账号状态与网络。"),
                chunk("c-2", "退款流程：在订单页提交退款申请，七天内可全额退款。"),
                chunk("c-3", "发票开具需要三个工作日。")));
        KnowledgeService.SearchResult result =
                service.search(editor, List.of("kb-1"), "退款流程", 5, 0.0);
        assertEquals(1, result.results().size());
        assertEquals("c-2", result.results().get(0).chunkId());
        assertTrue(result.results().get(0).score() > 0.0);
        assertTrue(result.results().get(0).content().contains("退款"));
        assertEquals(3, result.scannedChunks());
    }

    @Test
    void searchHonoursTopKAndMinScore() {
        baseExists("kb-1", "space-1");
        when(mapper.listChunksForSearch(eq("space-1"), anyList(), anyInt())).thenReturn(List.of(
                chunk("c-1", "退款退款退款"),
                chunk("c-2", "退款说明"),
                chunk("c-3", "退款政策")));
        KnowledgeService.SearchResult capped =
                service.search(editor, List.of("kb-1"), "退款", 2, 0.0);
        assertEquals(2, capped.results().size());
        assertTrue(capped.results().get(0).score() >= capped.results().get(1).score());

        KnowledgeService.SearchResult strict =
                service.search(editor, List.of("kb-1"), "退款", 5, 1.0);
        assertTrue(strict.results().isEmpty());
    }

    @Test
    void crossTenantBaseIsSilentlyIgnored() {
        when(mapper.findBase("kb-other", "space-1")).thenReturn(null);
        KnowledgeService.SearchResult result =
                service.searchInternal("space-1", List.of("kb-other"), "退款", 5, 0.0);
        assertTrue(result.results().isEmpty());
        verify(mapper, never()).listChunksForSearch(any(), anyList(), anyInt());
    }

    @Test
    void emptyQueryReturnsNoHitsWithoutScanning() {
        baseExists("kb-1", "space-1");
        KnowledgeService.SearchResult result =
                service.search(editor, List.of("kb-1"), " ", 5, 0.0);
        assertTrue(result.results().isEmpty());
        verify(mapper, never()).listChunksForSearch(any(), anyList(), anyInt());
    }

    @Test
    void englishTermsAreCaseInsensitive() {
        baseExists("kb-1", "space-1");
        when(mapper.listChunksForSearch(eq("space-1"), anyList(), anyInt()))
                .thenReturn(List.of(chunk("c-1", "Refund policy applies within seven days.")));
        KnowledgeService.SearchResult result =
                service.search(editor, List.of("kb-1"), "REFUND", 5, 0.0);
        assertFalse(result.results().isEmpty());
    }

    private void baseExists(String id, String tenantId) {
        when(mapper.findBase(id, tenantId)).thenReturn(new KnowledgeMapper.BaseRow(
                id, tenantId, "知识库", null, "user-1", null, 1L, 3L));
    }

    private static KnowledgeMapper.ChunkRow chunk(String id, String content) {
        return new KnowledgeMapper.ChunkRow(id, "space-1", "kb-1", "doc-1", 0,
                "操作手册", content, KnowledgeService.tokenize(content).size());
    }
}
