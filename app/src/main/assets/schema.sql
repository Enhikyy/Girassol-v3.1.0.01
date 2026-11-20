PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA mmap_size=268435456;

CREATE TABLE IF NOT EXISTS rsi_promotion_ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    world_tick INTEGER NOT NULL,
    proposer_agent_id TEXT NOT NULL,
    baseline_hash TEXT NOT NULL,
    candidate_hash TEXT NOT NULL,
    promoted INTEGER NOT NULL CHECK (promoted IN (0, 1)),
    net_fitness_gain REAL NOT NULL,
    brier_before REAL NOT NULL,
    brier_after REAL NOT NULL,
    reason TEXT NOT NULL,
    created_utc INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rsi_ledger_tick ON rsi_promotion_ledger(world_tick);

CREATE TABLE IF NOT EXISTS subjective_beliefs (
    observer_agent_id TEXT NOT NULL,
    claim_key TEXT NOT NULL,
    domain TEXT NOT NULL,
    belief REAL NOT NULL,
    disbelief REAL NOT NULL,
    uncertainty REAL NOT NULL,
    base_rate REAL NOT NULL,
    provenance_event_id INTEGER,
    updated_tick INTEGER NOT NULL,
    PRIMARY KEY (observer_agent_id, claim_key)
);

CREATE TABLE IF NOT EXISTS ecological_niche_structures (
    structure_id TEXT PRIMARY KEY,
    structure_type TEXT NOT NULL,
    grid_x INTEGER NOT NULL,
    grid_z INTEGER NOT NULL,
    level INTEGER NOT NULL,
    structural_integrity REAL NOT NULL,
    builder_agent_id TEXT NOT NULL,
    built_at_tick INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tsetlin_automata_blobs (
    citizen_id INTEGER PRIMARY KEY,
    num_features INTEGER NOT NULL,
    num_clauses INTEGER NOT NULL,
    automata_blob BLOB NOT NULL,
    updated_tick INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS adico_institutions (
    rule_id TEXT PRIMARY KEY,
    attributes_json TEXT NOT NULL,
    deontic_op TEXT NOT NULL,
    aim_action TEXT NOT NULL,
    conditions_text TEXT NOT NULL,
    sanction_units INTEGER NOT NULL,
    max_quota INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS double_entry_ledger (
    entry_id INTEGER PRIMARY KEY AUTOINCREMENT,
    world_tick INTEGER NOT NULL,
    from_account TEXT NOT NULL,
    to_account TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    quantity REAL NOT NULL,
    canonical_event_hash TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts ON double_entry_ledger(from_account, to_account, resource_type);

CREATE TABLE IF NOT EXISTS bkt_fsrs_mastery (
    citizen_id INTEGER NOT NULL,
    skill_id TEXT NOT NULL,
    mastery_prob REAL NOT NULL,
    difficulty_d REAL NOT NULL,
    stability_s REAL NOT NULL,
    last_reviewed_tick INTEGER NOT NULL,
    verified_executions INTEGER NOT NULL,
    PRIMARY KEY (citizen_id, skill_id)
);

CREATE TABLE IF NOT EXISTS voyager_skill_library (
    skill_id TEXT PRIMARY KEY,
    author_agent_id TEXT NOT NULL,
    preconditions_json TEXT NOT NULL,
    action_sequence_json TEXT NOT NULL,
    verified_success_rate REAL NOT NULL,
    created_at_tick INTEGER NOT NULL
);
