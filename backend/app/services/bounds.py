"""Cap provider text to the contract's maxLength before validation (WS22-07).

A real VLM can run long. Rather than turn an over-long but useful answer into a fallback, every
string field with a max_length in the response model (description 400, answer 600, summary 1200,
spoken_ack / clarification 200, route instruction 300) is cut at the last sentence end that fits,
else the last word, so the phone still speaks a complete-sounding sentence. Nested models and
lists of models (route steps) are handled. Nothing here is logged.
"""

import re
import types
import typing
from typing import Any

from annotated_types import MaxLen
from pydantic import BaseModel

_SENTENCE_END = re.compile(r"[.!?…](?=\s|$)")


def truncate_text(text: str, limit: int) -> str:
    """Longest prefix within `limit` that ends a sentence, else a word, else a hard cut."""
    if len(text) <= limit:
        return text
    head = text[:limit]
    ends = [m.end() for m in _SENTENCE_END.finditer(head)]
    space = head.rstrip().rfind(" ")
    # Prefer a full sentence; take a word cut only when it keeps much more of the answer.
    if ends and (ends[-1] >= limit // 3 or space < limit // 3):
        return head[: ends[-1]].rstrip()
    if space > 0:
        # space < limit, so adding the full stop stays within the limit.
        return head[:space].rstrip(" ,;:-") + "."
    return head


def _max_len(field) -> int | None:
    for meta in field.metadata:
        if isinstance(meta, MaxLen):
            return meta.max_length
    return None


def _model_types(annotation: Any) -> list[type[BaseModel]]:
    """BaseModel classes inside an annotation: M, M | None, list[M]."""
    if isinstance(annotation, type) and issubclass(annotation, BaseModel):
        return [annotation]
    origin = typing.get_origin(annotation)
    if origin in (list, typing.Union, types.UnionType):
        return [m for arg in typing.get_args(annotation) for m in _model_types(arg)]
    return []


def cap_strings(data: Any, model: type[BaseModel]) -> Any:
    """Return a copy of `data` with over-long string fields trimmed to `model`'s limits."""
    if not isinstance(data, dict):
        return data
    out = dict(data)
    for name, field in model.model_fields.items():
        key = field.alias or name
        value = out.get(key)
        if isinstance(value, str):
            limit = _max_len(field)
            if limit is not None and len(value) > limit:
                out[key] = truncate_text(value, limit)
            continue
        nested = _model_types(field.annotation)
        if not nested:
            continue
        if isinstance(value, dict):
            out[key] = cap_strings(value, nested[0])
        elif isinstance(value, list):
            out[key] = [cap_strings(v, nested[0]) for v in value]
    return out
