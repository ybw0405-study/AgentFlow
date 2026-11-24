"""PG 向量索引读写：租户 + 知识库 + 模型三重谓词，换模型即换空间。"""

from __future__ import annotations

import asyncpg


class KnowledgeVectorStore:
    def __init__(self, pool: asyncpg.Pool) -> None:
        self._pool = pool

    async def indexed_hashes(self, tenant_id: str, kb_ids: list[str], model: str) -> dict[str, str]:
        if not kb_ids:
            return {}
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT chunk_id, content_hash FROM knowledge_chunk_embedding"
                " WHERE tenant_id = $1 AND kb_id = ANY($2::text[]) AND model = $3",
                tenant_id,
                kb_ids,
                model,
            )
        return {row["chunk_id"]: row["content_hash"] for row in rows}

    async def upsert(self, tenant_id: str, model: str, rows: list[dict]) -> None:
        if not rows:
            return
        async with self._pool.acquire() as conn:
            await conn.executemany(
                "INSERT INTO knowledge_chunk_embedding"
                " (chunk_id, tenant_id, kb_id, document_id, document_title, ordinal,"
                "  content, content_hash, model, embedding)"
                " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::vector)"
                " ON CONFLICT (chunk_id, model) DO UPDATE SET"
                "  content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,"
                "  document_title = EXCLUDED.document_title, ordinal = EXCLUDED.ordinal,"
                "  embedding = EXCLUDED.embedding, updated_at = now()",
                [
                    (
                        row["chunkId"],
                        tenant_id,
                        row["kbId"],
                        row["documentId"],
                        row["documentTitle"],
                        row["ordinal"],
                        row["content"],
                        row["contentHash"],
                        model,
                        "[" + ",".join(repr(float(x)) for x in row["embedding"]) + "]",
                    )
                    for row in rows
                ],
            )

    async def delete_stale(
        self, tenant_id: str, kb_ids: list[str], model: str, keep: set[str]
    ) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "DELETE FROM knowledge_chunk_embedding"
                " WHERE tenant_id = $1 AND kb_id = ANY($2::text[]) AND model = $3"
                " AND chunk_id <> ALL($4::text[])",
                tenant_id,
                kb_ids,
                model,
                sorted(keep),
            )

    async def search(
        self, tenant_id: str, kb_ids: list[str], model: str, query_vector: list[float], top_k: int
    ) -> list[dict]:
        if not kb_ids:
            return []
        literal = "[" + ",".join(repr(float(x)) for x in query_vector) + "]"
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT chunk_id, kb_id, document_id, document_title, ordinal, content,"
                " 1 - (embedding <=> $5::vector) AS score"
                " FROM knowledge_chunk_embedding"
                " WHERE tenant_id = $1 AND kb_id = ANY($2::text[]) AND model = $3"
                " ORDER BY embedding <=> $5::vector LIMIT $4",
                tenant_id,
                kb_ids,
                model,
                top_k,
                literal,
            )
        return [
            {
                "chunkId": row["chunk_id"],
                "kbId": row["kb_id"],
                "documentId": row["document_id"],
                "documentTitle": row["document_title"],
                "ordinal": row["ordinal"],
                "content": row["content"],
                "score": float(row["score"]),
            }
            for row in rows
        ]
