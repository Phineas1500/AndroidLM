# Against a 1.7B model (2026-09-24)

The bounty asks for queries a 1B-class model fails. We ran Qwen3-1.7B, the model used by the
other entry on this bounty so far, on all 72 of our eval questions and graded it beside our
pipeline.

## Method

- Small model: `ggml-org/Qwen3-1.7B-GGUF`, `Qwen3-1.7B-Q4_K_M.gguf` (revision daeb8e2d, SHA-256
  d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5), answering from memory with
  the same system prompt as our drafts, thinking off:

  ```sh
  RAG_DB=corpus/wiki.db RAG_MODE=none RUN_SUFFIX=_fa QUESTIONS=$PWD/eval/questions_everything.jsonl \
    ./scripts/eval_models.sh fa17b=models/Qwen3-1.7B-Q4_K_M.gguf
  ```

  Output: `eval/answers_fa17b_none_fa.jsonl` (72 answers, none truncated, median 144 tokens).
  An app that ships built-in notes would answer from a note when a question matches one; this
  run measures the model on its own.
- Ours: Qwen3.6-35B-A3B UD-Q2_K_XL with the full pipeline (`rag.py --mode auto`, router and
  source check), from `answers_q2kxl_auto_batchcheck.jsonl` (general and long-tail) and
  `answers_q2kxl_auto_trv2_router.jsonl` (travel). These were produced on the ARM server; the
  phone runs the same pipeline, so its answers match in substance but not word for word.
- Grading: two Claude subagents with the rubric of the earlier rounds (0-10, 10 = a frontier
  model with web search; long-tail against gold answers and key facts), calibrated on the
  earlier grade files. The graders knew which answer came from which system. Pairs:
  `eval/pairs_small_vs_ours_*.json`; grades: `eval/grades_small_vs_ours_*.json`.

## Results

| | Questions | Qwen3-1.7B | Ours | Ours higher |
|---|---|---|---|---|
| General research (explain, compare, synthesis, multi-hop, medical, numeric, abstain) | 28 | 4.16 | 7.41 | 27 |
| Travel | 20 | 1.98 | 6.17 | 20 |
| Long-tail (obscure subjects) | 24 | 1.25 | 7.21 | 24 |
| All | 72 | 2.58 | 7.00 | 71 |

- Long-tail: key facts found 8/120 (small) vs 85/120 (ours); answers with uncorrected invented
  specifics 23/24 vs 3/24.
- General and travel: the grader rated 21 of 48 small-model answers fundamentally wrong or
  fabricated, including 15 of the 20 travel answers. Confident errors counted: small 77, ours 91.
  Ours are about three times longer (19,105 vs 6,454 words), so per 100 words the rates are 1.19
  vs 0.48, and ours are mostly side details in long itineraries while the small model's are the
  core of the answer.
- The one question the small model won: cmp-03 (malaria vs dengue, 6.5 vs 6). Our answer was cut
  off before treatment, named a non-existent vaccine, and its source check wrongly "corrected"
  the draft.

Typical small-model failures: invented Nobel co-laureates for penicillin; light reaching
Neptune in 35 minutes; India's population as 1.3 times Canada's; Bangkok sights listed for Luang
Prabang; Mount Fuji placed in Hokkaido; a plot summary of a novel that does not exist; the 1983
Harrods bombing set in "a Marks & Spencer store in Harrods" with 10 dead; Pink Floyd's "Hey, Hey,
Rise Up!" described as an American Revolution satire featuring John Mulaney.

Demo questions chosen from this run: `submission/questions.md`.
