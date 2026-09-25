package io.bigmoeonedge.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.androidlm.research.PhaseTiming
import org.androidlm.research.ResearchPhase
import org.androidlm.research.ResearchSource
import org.androidlm.research.RouteDecision

/**
 * Where the engine is in its lifecycle. Unlike the old per-run boolean pair, a session process
 * outlives a single generation: it loads once (LOADING), then sits READY between prompts, and
 * flips to GENERATING only while a prompt is being answered.
 */
enum class EngineState { IDLE, LOADING, READY, GENERATING, ERROR }

/**
 * One committed message in the multi-turn transcript. metrics is a compact per-turn line.
 * reasoning is the model's thinking span (assistant turns only), shown as a collapsible block
 * above the answer; empty when the model did not reason.
 */
data class ChatTurn(val role: String, val text: String, val metrics: String = "", val reasoning: String = "")

/**
 * AndroidLM research mode: what the screen shows of one ResearchPipeline run (plan, route,
 * sources, answer, source check), filled in by RunService as the pipeline reports events.
 * The engine state stays GENERATING for the whole run, searches included.
 */
data class ResearchUi(
    val question: String,
    val runId: Int = 0,                    // RunService's run counter; 0 while the model still loads
    val phase: ResearchPhase = ResearchPhase.PLANNING,
    val titles: List<String>? = null,      // null until the plan is in
    val route: RouteDecision? = null,
    val routeThreshold: Long = 0,
    val sources: List<ResearchSource>? = null, // null until the search is done
    val sourcesDropped: Int = 0,
    val answer: String = "",
    val check: String? = null,             // null when no source check has started
    val timings: List<PhaseTiming> = emptyList(),
    val error: String? = null,
) {
    val running get() = phase != ResearchPhase.DONE && phase != ResearchPhase.CANCELLED && phase != ResearchPhase.FAILED
}

/**
 * Prompt reading in progress: [done] of [total] new prompt tokens as of [updatedMs]
 * (SystemClock.elapsedRealtime), read at about [tokensPerSecond]. The engine reports once per
 * chunk (512 tokens), so the screen interpolates between reports at that rate.
 */
data class Prefill(val done: Int, val total: Int, val startedMs: Long, val updatedMs: Long, val tokensPerSecond: Double) {
    /** Estimated tokens read by [nowMs]. */
    fun estimate(nowMs: Long): Double =
        minOf(total.toDouble(), done + (nowMs - updatedMs) / 1000.0 * tokensPerSecond)

    /** Estimated seconds left at [nowMs]. */
    fun secondsLeft(nowMs: Long): Double = maxOf(0.0, (total - estimate(nowMs)) / tokensPerSecond)
}

/** Immutable snapshot of the session + current generation, observed by the Compose UI. */
data class UiState(
    val state: EngineState = EngineState.IDLE,
    val telemetry: Telemetry = Telemetry(),
    val answer: String = "",
    val reasoning: String = "",      // in-flight thinking span; streams before the answer while the model reasons
    val summary: String = "",
    val error: String? = null,
    val ioMode: String? = null,     // effective read mode reported by the engine (direct / buffered)
    val cpuTempC: Double? = null,   // SoC/CPU temperature (°C), sampled while generating (battery fallback)
    val sessionSig: String? = null, // signature of the loaded session (AppSettings.sessionSignature)
    // How the loaded model can honour "Thinking off", reported once at BMOE_READY: "template" (its
    // chat template reads the flag), "prefill" (it does not, so the engine closes the reasoning span
    // in the prompt), or "none" (neither — the model always reasons, and the switch is hidden rather
    // than left there doing nothing). Null until a session reports it. See docs/telemetry.md.
    val thinkControl: String? = null,
    // Experts the loaded model routes per token, from BMOE_READY. Null = nothing loaded yet,
    // 0 = not MoE. Settings needs it because "Drop cold experts" is a fraction of 1/top-k, so the
    // same percentage means something very different on a narrow routing.
    val nExpertUsed: Int? = null,
    val transcript: List<ChatTurn> = emptyList(), // committed turns; the in-flight answer is `answer`
    val streaming: Boolean = true,  // is the loaded session using the MoE streamer (vs mmap baseline)?
    // AndroidLM research mode: the run in progress or the last one finished; null in plain chat.
    val research: ResearchUi? = null,
    // The engine is reading a prompt (BMOE_PREFILL), until its first token; null otherwise.
    val prefill: Prefill? = null,
) {
    val loading get() = state == EngineState.LOADING
    val generating get() = state == EngineState.GENERATING
    val ready get() = state == EngineState.READY
    val busy get() = state == EngineState.LOADING || state == EngineState.GENERATING
}

/**
 * Single source of truth shared between the RunService (writer) and the UI (reader). The service
 * pushes updates as the session process reports progress; the UI collects the StateFlow. One
 * session at a time, one generation at a time within it, so a single flow is enough.
 */
object RunBus {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Reset the per-generation fields for a new prompt, preserving session state and signature. */
    fun resetGeneration() = _state.update {
        it.copy(telemetry = Telemetry(), answer = "", reasoning = "", summary = "", error = null)
    }

    fun update(block: (UiState) -> UiState) = _state.update(block)
}
