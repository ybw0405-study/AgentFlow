"""Reciprocal Rank Fusion：词法与向量两路名次融合，不依赖两路分数可比。"""

from __future__ import annotations

RRF_K = 60


def rrf_fuse(lexical: list[dict], vector: list[dict], top_k: int) -> list[dict]:
    scores: dict[str, float] = {}
    hits: dict[str, dict] = {}
    for ranked in (lexical, vector):
        for rank, hit in enumerate(ranked, start=1):
            chunk_id = hit.get("chunkId")
            if not isinstance(chunk_id, str):
                continue
            scores[chunk_id] = scores.get(chunk_id, 0.0) + 1.0 / (RRF_K + rank)
            hits.setdefault(chunk_id, hit)
    ordered = sorted(scores.items(), key=lambda item: (-item[1], item[0]))
    fused = []
    for chunk_id, score in ordered[:top_k]:
        hit = dict(hits[chunk_id])
        hit["score"] = round(score, 6)
        fused.append(hit)
    return fused
