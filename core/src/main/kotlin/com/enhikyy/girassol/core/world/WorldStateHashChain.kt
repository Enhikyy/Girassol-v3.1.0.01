package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.util.Sha256

/**
 * WorldState Hash Chain — cadeia de auditoria SHA-256 dos eventos canônicos.
 *
 * `event_hash = SHA-256("seq|tick|rules_version|type|actor|payload|prev_hash")` e
 * `prev_hash` é o `event_hash` do evento anterior (o primeiro aponta para [GENESIS_HASH]).
 * A mesma fórmula é usada pelo WorldCore ao emitir, pelo gatilho do SQLite WAL (que recusa
 * inserções com `prev_hash` divergente) e pelo verificador de integridade/replay.
 */
object WorldStateHashChain {
    fun body(seq: Long, tick: Long, rulesVersion: Int, type: String, actorId: String?, payload: String, prevHash: String) =
        "$seq|$tick|$rulesVersion|$type|${actorId ?: "-"}|$payload|$prevHash"

    fun computeHash(seq: Long, tick: Long, rulesVersion: Int, type: String, actorId: String?, payload: String, prevHash: String): String =
        Sha256.hex(body(seq, tick, rulesVersion, type, actorId, payload, prevHash))

    fun computeHash(e: CanonicalEvent) = computeHash(e.seq, e.tick, e.rulesVersion, e.type, e.actorId, e.payload, e.prevHash)

    data class Report(
        val ok: Boolean,
        val checked: Int,
        val firstBadSeq: Long?,
        val reason: String,
        val headHash: String
    )

    /**
     * Verifica uma janela contígua de eventos ordenados por `seq`. [anchorPrevHash] (opcional)
     * é o hash esperado para o `prev_hash` do primeiro evento — GENESIS quando a janela começa em seq 1.
     */
    fun verify(events: List<CanonicalEvent>, anchorPrevHash: String? = null): Report {
        if (events.isEmpty()) return Report(true, 0, null, "vazio", anchorPrevHash ?: GENESIS_HASH)
        var prev: CanonicalEvent? = null
        val first = events.first()
        val anchor = anchorPrevHash ?: if (first.seq == 1L) GENESIS_HASH else null
        if (anchor != null && first.prevHash != anchor) return Report(false, 0, first.seq, "prev_hash do primeiro evento não confere com a âncora", anchor)
        for ((i, e) in events.withIndex()) {
            if (prev != null) {
                if (e.seq != prev.seq + 1) return Report(false, i, e.seq, "lacuna de sequência após ${prev.seq}", prev.hash)
                if (e.prevHash != prev.hash) return Report(false, i, e.seq, "prev_hash não encadeia com o evento ${prev.seq}", prev.hash)
                if (e.tick < prev.tick) return Report(false, i, e.seq, "tick regrediu", prev.hash)
            }
            if (computeHash(e) != e.hash) return Report(false, i, e.seq, "event_hash adulterado", prev?.hash ?: e.prevHash)
            prev = e
        }
        return Report(true, events.size, null, "íntegra", prev!!.hash)
    }

    /** Replay determinístico: o hash da cabeça deve coincidir com o `lastEventHash` do estado. */
    fun verifyHead(events: List<CanonicalEvent>, state: WorldState): Report {
        val r = verify(events)
        if (!r.ok) return r
        return if (events.isNotEmpty() && events.last().seq == state.eventSeq && r.headHash != state.lastEventHash)
            r.copy(ok = false, firstBadSeq = events.last().seq, reason = "cabeça da cadeia ≠ lastEventHash do WorldState")
        else r
    }

    /** SQL do gatilho append-only usado no SQLite WAL (o app o instala em `canonical_events`). */
    val SQLITE_TRIGGERS = listOf(
        """CREATE TRIGGER IF NOT EXISTS trg_events_chain_guard BEFORE INSERT ON canonical_events
           WHEN NEW.seq > 1 AND EXISTS (SELECT 1 FROM canonical_events WHERE seq = NEW.seq - 1 AND event_hash <> NEW.prev_hash)
           BEGIN SELECT RAISE(ABORT, 'girassol: prev_hash quebra a cadeia SHA-256'); END""",
        """CREATE TRIGGER IF NOT EXISTS trg_events_genesis_guard BEFORE INSERT ON canonical_events
           WHEN NEW.seq = 1 AND NEW.prev_hash <> '$GENESIS_HASH'
           BEGIN SELECT RAISE(ABORT, 'girassol: primeiro evento precisa apontar para o GENESIS'); END""",
        """CREATE TRIGGER IF NOT EXISTS trg_events_no_update BEFORE UPDATE ON canonical_events
           BEGIN SELECT RAISE(ABORT, 'girassol: eventos canônicos são imutáveis'); END""",
        "CREATE INDEX IF NOT EXISTS idx_events_hash ON canonical_events(event_hash)"
    )
}
