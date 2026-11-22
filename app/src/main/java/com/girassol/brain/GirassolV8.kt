// ============================================================================
// GirassolV8.kt — Fachada Kotlin do núcleo cortical v8 (Projeto Girassol)
// Pacote: com.girassol.brain
// Contrato: JNI sobre libgirassol_v8.so (arm64-v8a). Todas as chamadas são
// determinísticas (A4) e alocam apenas no lado Kotlin (o hot path nativo
// usa exclusivamente a Arena64 estática — 0 heap nativo).
// ============================================================================
package com.girassol.brain

object GirassolV8 {

    init {
        System.loadLibrary("girassol_v8")
    }

    // -----------------------------------------------------------------------
    // Constantes canônicas (A4): hash FNV-1a-64 do autoteste embarcado,
    // reancorado na execução nativa C++20 x86_64 (-ffp-contract=off) e
    // validado bit a bit contra arm64-v8a no CI (ver girassol_v8_ci.yml).
    // -----------------------------------------------------------------------
    const val EXPECTED_SELF_TEST_HASH: Long = 0x278c01e818c47c50L
    const val CORE_VERSION_EXPECTED: String = "v8.0.0-nofma-nolibm"

    // Limites do envelope do hot path (A1 — Tier 1, Moto G62 5G)
    const val MIN_NEURONS: Int = 64
    const val MAX_NEURONS: Int = 1024

    // Larguras de acumulação do estimador Roofline (C2, Trava 3)
    const val ACC_BINARY64: Int = 64
    const val ACC_BINARY32: Int = 32

    // Bitmask do certificado PC-SM (T5)
    const val GATE_C1_LYAPUNOV: Int = 1
    const val GATE_C2_ROOFLINE: Int = 2
    const val GATE_C3_INTEGRITY: Int = 4
    const val GATE_C4_ORTHOGONALITY: Int = 8
    const val GATE_ACCEPT: Int = 16

    // -----------------------------------------------------------------------
    // API nativa
    // -----------------------------------------------------------------------
    private external fun nativeInitBrain(n: Int): Int
    private external fun nativeStep(xIn: DoubleArray, p: DoubleArray): Double
    private external fun nativeNeuromod(out: DoubleArray): Int
    private external fun nativeRooflineMs(bytes: Long, flops: Long, accw: Int): Double
    private external fun nativeGateVerify(
        lgBefore: Double, lgAfter: Double,
        bytes: Long, flops: Long, accw: Int,
        hashExpected: Long, hashActual: Long, orthMax: Double
    ): Int
    private external fun nativeSelfTest(): Long
    private external fun nativeCoreVersion(): String

    // -----------------------------------------------------------------------
    // Fachada idiomática
    // -----------------------------------------------------------------------

    /** Estado dos 4 canais neuromoduladores (Def. 3.3 do tratado). */
    data class NeuromodState(
        val dopamina: Double,       // erro de predição / surpresa
        val noradrenalina: Double,  // volatilidade / reset de crenças
        val acetilcolina: Double,   // precisão sensorial vs. memória
        val serotonina: Double      // horizonte de desconto temporal
    )

    /** Resultado da verificação do gate PC-SM (Teorema T5). */
    data class GateResult(
        val lyapunovOk: Boolean,       // C1: ΔL_G ≤ 0
        val rooflineOk: Boolean,       // C2: latência ≤ T_budget
        val integrityOk: Boolean,      // C3: FNV-1a arena‖.grs íntegro
        val orthogonalityOk: Boolean,  // C4: ‖U_newᵀ U_old‖ ≤ 64 ulp
    ) {
        val accepted: Boolean
            get() = lyapunovOk && rooflineOk && integrityOk && orthogonalityOk
    }

    /**
     * Inicializa o córtex ED-SDPC com [n] neurônios.
     * @throws IllegalArgumentException se n fora do envelope [MIN_NEURONS, MAX_NEURONS]
     * @throws IllegalStateException se a arena nativa for insuficiente
     */
    fun initBrain(n: Int) {
        require(n in MIN_NEURONS..MAX_NEURONS) {
            "n=$n fora do envelope [$MIN_NEURONS, $MAX_NEURONS]"
        }
        check(nativeInitBrain(n) == 1) { "Arena64 nativa insuficiente para n=$n" }
    }

    /**
     * Executa um passo orientado a eventos (Def. 3.1–3.3).
     * [xIn] entrada sensorial esparsa, [p] predição top-down (ambas n-dim).
     * @return taxa de disparo do passo ∈ [0,1] (alvo homeostático: ≤ 5%)
     */
    fun step(xIn: DoubleArray, p: DoubleArray): Double {
        val act = nativeStep(xIn, p)
        check(act >= 0.0) { "Córtex não inicializado ou dimensão inválida" }
        return act
    }

    /** Lê o vetor neuromodulador atual (DA, NA, ACh, 5HT). */
    fun neuromodState(): NeuromodState {
        val out = DoubleArray(4)
        check(nativeNeuromod(out) == 1) { "Córtex não inicializado" }
        return NeuromodState(out[0], out[1], out[2], out[3])
    }

    /**
     * Estimativa Roofline determinística do gate PC-SM (C2, Teorema T2.b).
     * @param accw ACC_BINARY64 (padrão Tier 1) ou ACC_BINARY32
     * @return latência estimada em milissegundos
     */
    fun rooflineMs(bytes: Long, flops: Long, accw: Int = ACC_BINARY64): Double =
        nativeRooflineMs(bytes, flops, accw)

    /**
     * Verifica o certificado de auto-modificação (Proof-Carrying SM, T5).
     * A mutação só pode ser commitada se [GateResult.accepted] for true.
     */
    fun gateVerify(
        lgBefore: Double, lgAfter: Double,
        bytes: Long, flops: Long, accw: Int = ACC_BINARY64,
        hashExpected: Long, hashActual: Long, orthMax: Double
    ): GateResult {
        val mask = nativeGateVerify(
            lgBefore, lgAfter, bytes, flops, accw,
            hashExpected, hashActual, orthMax
        )
        return GateResult(
            lyapunovOk = (mask and GATE_C1_LYAPUNOV) != 0,
            rooflineOk = (mask and GATE_C2_ROOFLINE) != 0,
            integrityOk = (mask and GATE_C3_INTEGRITY) != 0,
            orthogonalityOk = (mask and GATE_C4_ORTHOGONALITY) != 0
        )
    }

    /**
     * Autoteste de boot (A4/C3): executa o vetor canônico TEST-V8-1 no
     * núcleo nativo e confere o hash FNV-1a-64 contra a âncora nativa.
     * Deve ser chamado no Application.onCreate; retorno false indica
     * violação de paridade (binário adulterado ou ABI incorreta).
     */
    fun selfTestPassed(): Boolean = nativeSelfTest() == EXPECTED_SELF_TEST_HASH

    /** Versão do núcleo nativo (telemetria/auditoria). */
    fun coreVersion(): String = nativeCoreVersion()
}
