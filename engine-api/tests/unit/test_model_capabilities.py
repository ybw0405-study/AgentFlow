"""模型能力标注：上游字段优先，模型名兜底，都没有就什么都不说。"""

from __future__ import annotations

from app.commands.providers.model_capabilities import (
    TAG_JSON,
    TAG_LONG_CONTEXT,
    TAG_REASONING,
    TAG_VISION,
    describe_model,
)


def test_reads_upstream_capability_fields() -> None:
    facts = describe_model(
        {
            "id": "openai/gpt-4.1",
            "context_length": 1_047_576,
            "top_provider": {"max_completion_tokens": 32_768},
            "supported_parameters": ["response_format", "tools"],
            "architecture": {"input_modalities": ["text", "image"]},
        }
    )

    assert facts is not None
    assert facts.source == "upstream"
    assert facts.context_window == 1_047_576
    assert facts.max_output_tokens == 32_768
    assert set(facts.tags) == {TAG_LONG_CONTEXT, TAG_JSON, TAG_VISION}


def test_reasoning_comes_from_supported_parameters() -> None:
    facts = describe_model({"id": "some-gateway-model", "supported_parameters": ["reasoning"]})

    assert facts is not None
    assert TAG_REASONING in facts.tags


def test_reasoning_recognised_by_upstream_effort_levels() -> None:
    """DeepSeek 的 /models 用 effort.supported_levels 表示「可调思考强度」，只有推理型才有。"""
    facts = describe_model(
        {
            "id": "deepseek-flash",
            "context_window": 1_048_576,
            "max_output_tokens": 393_216,
            "input_modalities": ["text", "image"],
            "effort": {"supported_levels": ["low", "high", "max"], "default_level": "high"},
        }
    )

    assert facts is not None
    assert set(facts.tags) == {TAG_REASONING, TAG_LONG_CONTEXT, TAG_VISION}
    assert facts.context_window == 1_048_576
    assert facts.max_output_tokens == 393_216
    assert facts.source == "upstream"


def test_reasoning_inferred_from_model_name() -> None:
    for model_id in ("deepseek-reasoner", "deepseek-r1-0528", "qwen-qwq-32b", "o3-mini"):
        facts = describe_model({"id": model_id})
        assert facts is not None, model_id
        assert TAG_REASONING in facts.tags, model_id


def test_chat_model_is_not_tagged_as_reasoning() -> None:
    """`gpt-4o-mini` 里含 o1/o3 子串，按词元切分后不该被误判。"""
    for model_id in ("deepseek-chat", "gpt-4o-mini", "gpt-4o", "glm-4-plus"):
        facts = describe_model({"id": model_id})
        assert facts is not None
        assert TAG_REASONING not in facts.tags


def test_no_capability_fields_means_no_tags() -> None:
    facts = describe_model({"id": "acme-model-v2"})

    assert facts is not None
    assert facts.tags == ()
    assert facts.context_window is None
    assert facts.max_output_tokens is None
    assert facts.source == "inferred"


def test_skips_entries_without_id() -> None:
    assert describe_model({"owned_by": "x"}) is None
    assert describe_model({"id": "   "}) is None


def test_long_context_tag_ignores_small_windows() -> None:
    facts = describe_model({"id": "tiny", "context_length": 8_192})

    assert facts is not None
    assert TAG_LONG_CONTEXT not in facts.tags


def test_name_size_suffix_marks_long_context() -> None:
    facts = describe_model({"id": "acme-128k"})

    assert facts is not None
    assert TAG_LONG_CONTEXT in facts.tags
