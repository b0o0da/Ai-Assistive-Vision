"""Route cache for GET /route (WS22-12, docs/api-contract.md "Fallback messages").

- Fresh hit (younger than `fresh_s`): served without calling Maps (saves quota and latency).
- Provider fails or times out: the last good route for the same origin area and destination is
  served if it is younger than `stale_s`; otherwise the spoken fallback. A made-up route is
  never returned.

Key: origin rounded to 3 decimals (about 100 m), destination lower-cased and trimmed. Kept in
process memory only; nothing is written to disk or logged.
"""

import time
from collections import OrderedDict
from typing import Any


def route_key(origin: str, destination: str) -> str:
    lat, _, lon = origin.partition(",")
    try:
        area = f"{round(float(lat), 3):.3f},{round(float(lon), 3):.3f}"
    except ValueError:
        area = origin.strip()
    return f"{area}|{' '.join(destination.lower().split())}"


class RouteCache:
    def __init__(self, fresh_s: float = 600, stale_s: float = 6 * 3600, max_items: int = 256,
                 clock=time.monotonic) -> None:
        self.fresh_s, self.stale_s, self.max_items = fresh_s, stale_s, max_items
        self.clock = clock
        self._items: OrderedDict[str, tuple[float, dict[str, Any]]] = OrderedDict()

    def put(self, key: str, data: dict[str, Any]) -> None:
        self._items[key] = (self.clock(), data)
        self._items.move_to_end(key)
        while len(self._items) > self.max_items:
            self._items.popitem(last=False)

    def _get(self, key: str, max_age: float) -> dict[str, Any] | None:
        item = self._items.get(key)
        if item is None:
            return None
        stored, data = item
        if self.clock() - stored > max_age:
            if self.clock() - stored > self.stale_s:
                del self._items[key]
            return None
        self._items.move_to_end(key)
        return data

    def fresh(self, key: str) -> dict[str, Any] | None:
        return self._get(key, self.fresh_s)

    def stale(self, key: str) -> dict[str, Any] | None:
        return self._get(key, self.stale_s)

    def clear(self) -> None:
        self._items.clear()


route_cache = RouteCache()
