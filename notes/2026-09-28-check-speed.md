# The source check, faster without losing quality (2026-09-28)

On the phone an answer-first question took 100-140 s, and 65-80 s of it was the source check:
about 48 s reading some 1,100 tokens of Wikipedia passages (about 23 tokens/s), then about
100 tokens of writing. Two changes.

## 1. The answer is ready when its draft is

The draft is the answer; the check comes after it. While the check runs the app now says
"Answered in 37 s. The source check below is still running; a new question stops it.", the
Research button stays enabled, and a new question cancels the check (the engine is told to stop;
on the phone it stopped within 2.3 s and the next question started at once, with clean output).
The timing line reads "Answered in 37 s, checked by 1 min 45 s". Nothing about the answer or
the check changes.

## 2. The check reads the passages that share most with the draft

The check tests the draft's statements, so it needs the passages that share its names and
numbers (and the question's), not all six in full. `check_context` (rag.py; `CheckContext` in
the app, held to it by golden.json) orders the retrieved passages by how many of the draft's and
the question's terms they contain (numbers 3, capitalised words 2, other words of four letters or
more 1) and packs them into 2,000 characters instead of 4,000. A second form also cut each
passage to its sentences that share those terms ("excerpts").

Blind A/B (`scripts/check_ab2.py`, `check_pairs.py`, `check_grades.py`): the 58 answer-first
drafts of the red team (`eval/answers_vm_redteam.jsonl`, without the places questions), each
checked in the follow-up-turn form the app uses, on the VM: today's context, the two shorter ones,
and today's again from an earlier run as a noise floor. Each pair was graded blind (random A/B
order) by a separate grader: did the check help, was it neutral or did it hurt; the errors it
introduced; the real draft errors it left standing; which draft + check reads better.

| Pair (n = 58) | Helped | Hurt | Errors introduced | Draft errors missed | Preferred | Tie |
|---|---|---|---|---|---|---|
| today's, two runs (noise floor) | 24 / 25 | 6 / 3 | 20 / 15 | 24 / 24 | 4 / 11 | 43 |
| today's / passages | 28 / 30 | 4 / 4 | 11 / 10 | 22 / 20 | 13 / 14 | 31 |
| today's / excerpts | 36 / 25 | 4 / 6 | 16 / 18 | 24 / 25 | 26 / 10 | 22 |

Two runs of the same check differ by up to 7 preferences and 5 errors. Whole passages are level
with today's check on every count; excerpts help less often (they drop the context that the
check's additions come from) and lose 10 to 26, well beyond the noise. Whole passages ship
(`ResearchConfig.checkChars = 2000`); excerpts do not.

Cost: the check's prompt on the VM falls from a median 1,274 tokens to 872 (the draft is part of
that prompt there; on the phone it is already in the engine's memory). On the Pixel, six
questions each way (`q_check`: the Byzantine Empire, Kilimanjaro, One Hundred Years of
Solitude, the causes of World War I, the Stoics, the Merge):

| Medians of 6 | Today's check | Passages (shipped) | Excerpts |
|---|---|---|---|
| Check prompt read | 1,031 tokens | 600 tokens | 642 tokens |
| Check | 71 s (64-79) | 50 s (39-62) | 56 s (48-60) |
| Whole question, sending to done | 128 s | 103 s | 119 s |

(`eval/answers_phone_check_*.jsonl`; first words after about 15 s in every build. The excerpts
runs were the hottest of the three, 36-39 C at the start against 29-35 C.) The check is about
30% faster, 21 s per answer-first question, and the answer itself is on screen and marked ready
about 50 s in, as before.

## What the grading also showed

Both forms leave about 20-25 of the 58 drafts' real errors standing, usually because the passages
say nothing about them (Bush's age at the fall of the Wall, the Merge and proposer-builder
separation, the 1930 World Cup), and both sometimes "correct" what was right (Mercury's solar
day). The check's reach is limited by what retrieval finds, not by how much of it the check reads.
