# Guarded RSI harness

This is an external research/CI tool, not part of the Android APK and not an autonomous source-code editor.

## Acceptance sequence

1. Select a parent from the lineage archive using seeded Thompson sampling over descendant trial outcomes.
2. Produce a candidate from a deep copy of the selected parent.
3. Compare parent and candidate on development and held-out evaluators.
4. Reject non-finite measurements, monitor violations, insufficient held-out improvement, or an excessive development/held-out gap.
5. Present an anonymized A/B pair in randomized order to the blind judge.
6. Require an explicit human approval callback before archiving the candidate.
7. Append a compact JSONL decision record with the candidate SHA-256 digest.

## Important boundary

safe_loop.py only orchestrates gates. It does not sandbox or execute candidate code. The mutation, benchmark, held-out, and monitor callbacks must be provided by an isolated runner (disposable CI job/container), with no production signing secrets, no app data, no persistent runner credentials, and no network access unless the experiment explicitly requires a separately reviewed test service.

The held-out dataset must not be made available to the mutator. The blind judge receives an A/B pair without candidate labels; it returns A, B, or tie. The approval callback receives the measured decision and must return a Boolean.

Run the standard-library tests:

    python3 -m unittest discover -s tools/guarded_rsi -p 'test_*.py' -v

No code, model weights, app configuration, or release artifact is changed by this harness. Promotion to a real repository branch or model update remains a separate, human-authorized action.
