import json
import math
from pathlib import Path
import tempfile
import unittest

from safe_loop import Archive, GateConfig, append_audit_record, improvement_step


class GuardedLoopTests(unittest.TestCase):
    def setUp(self):
        self.archive = Archive({"dev": 0.5, "held": 0.5, "risk": 0.1}, seed=7)
        self.log = []

    def run_step(self, mutate, **overrides):
        args = dict(
            archive=self.archive,
            mutate=mutate,
            dev_eval=lambda item: item["dev"],
            heldout_eval=lambda item: item["held"],
            monitor=lambda item: item["risk"],
            blind_judge=lambda parent, child: child["held"] > parent["held"],
            human_approve=lambda decision: True,
            log=self.log,
            config=GateConfig(),
        )
        args.update(overrides)
        return improvement_step(**args)

    def test_valid_candidate_requires_every_gate_and_is_archived(self):
        result = self.run_step(
            lambda parent: {**parent, "dev": 0.7, "held": 0.68, "risk": 0.2}
        )
        self.assertTrue(result["accepted"])
        self.assertEqual("accepted", result["reason"])
        self.assertEqual(2, len(self.archive.nodes))
        self.assertEqual(1, self.archive.nodes[0].succ)
        self.assertEqual(0, self.archive.nodes[1].parent)
        self.assertEqual(1, len(self.log))

    def test_dev_overfit_is_rejected_by_heldout_gap(self):
        result = self.run_step(
            lambda parent: {**parent, "dev": 0.95, "held": 0.6, "risk": 0.1}
        )
        self.assertFalse(result["accepted"])
        self.assertEqual("dev_heldout_gap", result["reason"])
        self.assertEqual(1, len(self.archive.nodes))
        self.assertEqual(1, self.archive.nodes[0].fail)

    def test_monitor_limit_blind_judge_and_human_gate(self):
        risky = self.run_step(
            lambda parent: {**parent, "dev": 0.7, "held": 0.7, "risk": 1.2}
        )
        self.assertEqual("monitor_limit", risky["reason"])

        rejected_by_judge = self.run_step(
            lambda parent: {**parent, "dev": 0.7, "held": 0.7, "risk": 0.1},
            blind_judge=lambda parent, child: False,
        )
        self.assertEqual("blind_judge_rejected", rejected_by_judge["reason"])

        rejected_by_human = self.run_step(
            lambda parent: {**parent, "dev": 0.7, "held": 0.7, "risk": 0.1},
            human_approve=lambda decision: False,
        )
        self.assertEqual("human_rejected", rejected_by_human["reason"])
        self.assertEqual(1, len(self.archive.nodes))

    def test_mutator_cannot_mutate_archived_parent_by_reference(self):
        def mutator(parent):
            parent["dev"] = 0.9
            parent["held"] = 0.9
            return parent

        result = self.run_step(mutator)
        self.assertTrue(result["accepted"])
        self.assertEqual(0.5, self.archive.nodes[0].agent["dev"])
        self.assertEqual(0.9, self.archive.nodes[1].agent["dev"])

    def test_invalid_metric_fails_closed(self):
        result = self.run_step(
            lambda parent: {**parent, "dev": math.nan, "held": 0.8, "risk": 0.0}
        )
        self.assertFalse(result["accepted"])
        self.assertEqual("evaluation_error", result["reason"])
        self.assertEqual("ValueError", result["error_type"])
        self.assertEqual(1, len(self.archive.nodes))

    def test_audit_record_is_append_only_jsonl(self):
        result = self.run_step(
            lambda parent: {**parent, "dev": 0.7, "held": 0.68, "risk": 0.2}
        )
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "audit" / "decisions.jsonl"
            append_audit_record(target, result)
            append_audit_record(target, {"accepted": False, "reason": "follow-up"})
            rows = [json.loads(line) for line in target.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(2, len(rows))
        self.assertEqual(result["candidate_sha256"], rows[0]["candidate_sha256"])
        self.assertEqual("follow-up", rows[1]["reason"])

    def test_archive_parent_selection_is_seeded(self):
        a = Archive({"root": True}, seed=17)
        b = Archive({"root": True}, seed=17)
        for _ in range(8):
            a.record_trial(0, accepted=True)
            b.record_trial(0, accepted=True)
        self.assertEqual([a.select_parent() for _ in range(10)], [b.select_parent() for _ in range(10)])


if __name__ == "__main__":
    unittest.main()
