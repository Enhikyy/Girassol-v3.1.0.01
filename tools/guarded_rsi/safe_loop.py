"""Human-gated orchestration for offline RSI experiments.

This module does not execute candidate code and is not a sandbox. Callbacks that
build, test, or run a candidate must be supplied by an isolated runner (for
example, a disposable CI job with no secrets or network access). A candidate
is archived only after dev, held-out, monitor, blind-judge, and human gates pass.
"""
from __future__ import annotations

from collections.abc import Callable
from copy import deepcopy
from dataclasses import asdict, dataclass, field
from hashlib import sha256
import json
import math
import random
from pathlib import Path
from typing import Any


@dataclass
class Node:
    agent: Any
    parent: int | None
    id: int
    succ: int = 0
    fail: int = 0
    children: list[int] = field(default_factory=list)


class Archive:
    """Small DGM-style lineage archive with Thompson parent selection.

    succ/fail are child-trial outcomes (clade productivity), not raw parent
    benchmark scores. That keeps parent selection tied to descendants.
    """

    def __init__(self, root_agent: Any, seed: int = 0) -> None:
        self.nodes = [Node(agent=deepcopy(root_agent), parent=None, id=0)]
        self._rng = random.Random(seed)

    def select_parent(self) -> int:
        samples = [
            (self._rng.betavariate(node.succ + 1, node.fail + 1), -node.id, node.id)
            for node in self.nodes
        ]
        return max(samples)[2]

    def add_child(self, parent_id: int, agent: Any) -> int:
        if parent_id < 0 or parent_id >= len(self.nodes):
            raise IndexError("parent_id does not exist")
        node_id = len(self.nodes)
        self.nodes.append(Node(agent=deepcopy(agent), parent=parent_id, id=node_id))
        self.nodes[parent_id].children.append(node_id)
        return node_id

    def record_trial(self, parent_id: int, accepted: bool) -> None:
        node = self.nodes[parent_id]
        if accepted:
            node.succ += 1
        else:
            node.fail += 1


@dataclass(frozen=True)
class GateConfig:
    min_dev_gain: float = 0.0
    min_heldout_gain: float = 0.0
    max_dev_heldout_gap: float = 0.25
    monitor_limit: float = 1.0

    def __post_init__(self) -> None:
        values = (self.min_dev_gain, self.min_heldout_gain,
                  self.max_dev_heldout_gap, self.monitor_limit)
        if not all(math.isfinite(value) for value in values):
            raise ValueError("gate thresholds must be finite")
        if self.max_dev_heldout_gap < 0.0 or self.monitor_limit < 0.0:
            raise ValueError("gap and risk limits cannot be negative")


def _fingerprint(candidate: Any) -> str:
    try:
        payload = json.dumps(candidate, sort_keys=True, separators=(",", ":"), default=repr)
    except (TypeError, ValueError):
        payload = repr(candidate)
    return sha256(payload.encode("utf-8")).hexdigest()


def _finite_metric(callback: Callable[[Any], float], candidate: Any) -> float:
    value = float(callback(candidate))
    if not math.isfinite(value):
        raise ValueError("evaluation returned a non-finite metric")
    return value


def improvement_step(
    archive: Archive,
    mutate: Callable[[Any], Any],
    dev_eval: Callable[[Any], float],
    heldout_eval: Callable[[Any], float],
    monitor: Callable[[Any], float],
    blind_judge: Callable[[Any, Any], bool],
    human_approve: Callable[[dict[str, Any]], bool],
    log: list[dict[str, Any]],
    config: GateConfig = GateConfig(),
) -> dict[str, Any]:
    """Propose one candidate and pass it through independent acceptance gates.

    The mutator sees only a deep-copied parent. The held-out evaluator and
    approval callback are not passed to it. The caller must keep held-out data,
    credentials, and runner internals outside the mutator's environment.
    """
    parent_id = archive.select_parent()
    parent = archive.nodes[parent_id].agent
    decision: dict[str, Any] = {
        "parent": parent_id,
        "candidate_sha256": None,
        "dev_gain": None,
        "heldout_gain": None,
        "risk": None,
        "accepted": False,
        "reason": "",
    }

    try:
        child = mutate(deepcopy(parent))
        decision["candidate_sha256"] = _fingerprint(child)
        parent_dev = _finite_metric(dev_eval, parent)
        child_dev = _finite_metric(dev_eval, child)
        parent_heldout = _finite_metric(heldout_eval, parent)
        child_heldout = _finite_metric(heldout_eval, child)
        dev_gain = child_dev - parent_dev
        heldout_gain = child_heldout - parent_heldout
        risk = float(monitor(child))
        decision.update(dev_gain=dev_gain, heldout_gain=heldout_gain, risk=risk)

        if not math.isfinite(risk) or risk < 0.0:
            decision["reason"] = "invalid_monitor_value"
        elif risk > config.monitor_limit:
            decision["reason"] = "monitor_limit"
        elif dev_gain <= config.min_dev_gain:
            decision["reason"] = "no_dev_gain"
        elif heldout_gain <= config.min_heldout_gain:
            decision["reason"] = "no_heldout_gain"
        elif dev_gain - heldout_gain > config.max_dev_heldout_gap:
            decision["reason"] = "dev_heldout_gap"
        else:
            blind_verdict = blind_judge(deepcopy(parent), deepcopy(child))
            if not isinstance(blind_verdict, bool):
                decision["reason"] = "invalid_blind_judge_result"
            elif not blind_verdict:
                decision["reason"] = "blind_judge_rejected"
            else:
                approval = human_approve(dict(decision))
                if not isinstance(approval, bool):
                    decision["reason"] = "invalid_human_approval"
                elif not approval:
                    decision["reason"] = "human_rejected"
                else:
                    child_id = archive.add_child(parent_id, child)
                    decision.update(accepted=True, child=child_id, reason="accepted")
        if decision["accepted"]:
            pass
    except Exception as exc:  # fail closed; do not archive a candidate on evaluator errors
        decision["reason"] = "evaluation_error"
        decision["error_type"] = type(exc).__name__

    archive.record_trial(parent_id, bool(decision["accepted"]))
    log.append(dict(decision))
    return decision


def append_audit_record(path: str | Path, decision: dict[str, Any]) -> None:
    """Append one compact, hash-addressable JSONL decision record."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(decision, sort_keys=True, separators=(",", ":"), allow_nan=False)
    with target.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")
        handle.flush()
