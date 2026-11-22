package com.enhikyy.girassol.ui.components

import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.AgentView
import com.enhikyy.girassol.core.world.ObjectView
import com.enhikyy.girassol.core.world.SmartObjectType

/** v3.9 — rótulos em português natural para a interface (nada de "Ocioso" permanente). */
object CitizenText {
    fun action(a: ActionType?): String = when (a) {
        null, ActionType.OCIOSO -> "Decidindo o próximo passo"
        ActionType.COMER -> "Comendo"
        ActionType.DESCANSAR -> "Descansando"
        ActionType.BANHO -> "Tomando banho"
        ActionType.COLHER -> "Colhendo na horta"
        ActionType.RECICLAR -> "Reciclando na composteira"
        ActionType.MANTER_USINA -> "Cuidando da usina"
        ActionType.PESQUISAR -> "Pesquisando no laboratório"
        ActionType.ESTUDAR -> "Estudando na biblioteca"
        ActionType.SOCIALIZAR -> "Conversando na praça"
        ActionType.CONTRIBUIR -> "Contribuindo com os comuns"
        ActionType.CONSTRUIR -> "Construindo na oficina"
        ActionType.EXPLORAR -> "Explorando a cidade"
    }

    fun place(t: SmartObjectType): String = when (t) {
        SmartObjectType.CASA -> "em casa"
        SmartObjectType.HORTA -> "na horta"
        SmartObjectType.COMPOSTEIRA -> "na composteira"
        SmartObjectType.USINA_SOLAR -> "na usina"
        SmartObjectType.LABORATORIO -> "no laboratório"
        SmartObjectType.ESCOLA -> "na biblioteca"
        SmartObjectType.PRACA -> "na praça"
        SmartObjectType.OFICINA -> "na oficina"
    }

    /**
     * v4.1 — linha de status do HUD. Comer só é "Comendo em casa" quando o cidadão está de fato
     * dentro de casa ([AgentView.insideHome]); a caminho mostra "Indo para casa → Comer".
     */
    fun status(a: AgentView, place: String): String = when {
        a.action == ActionType.COMER && a.insideHome -> "Comendo em casa"
        a.action == ActionType.COMER -> "Indo para casa → Comer"
        else -> "${action(a.action)} · $place"
    }

    /** Postura sentada no rig: digitando no terminal ou comendo dentro de casa. */
    fun seated(a: AgentView): Boolean = a.typing || (a.action == ActionType.COMER && a.insideHome)

    /** Lugar mais próximo (até ~4 tiles); senão "a caminho". */
    fun nearestPlace(objects: List<ObjectView>, fx: Float, fz: Float): String {
        val o = objects.minByOrNull { val dx = it.x + 0.5f - fx; val dz = it.z + 0.5f - fz; dx * dx + dz * dz } ?: return "pela cidade"
        val dx = o.x + 0.5f - fx; val dz = o.z + 0.5f - fz
        return if (dx * dx + dz * dz <= 16f) place(o.type) else "a caminho"
    }
}
