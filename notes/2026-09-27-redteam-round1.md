# Red team, round one (2026-09-27)

Vitalik's post named one test (the best vegan restaurants in the city he was in) and two general
complaints: the apps were much slower than models that run on a laptop, and weaker at difficult
questions. The places database answered the one test (`notes/2026-09-27-places.md`); this round
looked for the next things he would find. 77 questions in `eval/questions_redteam.jsonl`, in 12
kinds: things a traveller looks for (pharmacy, coworking, SIM card, hospital, cash, museums,
supermarket, laundry), practical travel (visas, tipping, tap water, scams, day trips, transport,
safety, taxi prices, temple etiquette), places questions the first version missed (late food,
pizza, a romantic dinner, breakfast, a village, a neighbourhood, a misspelling), technical
questions (Ethereum, cryptography, distributed systems), calculations, plain facts, broad and
ambiguous questions ("Tell me about Georgia"), the humanities, recent facts, Chinese, health and
edge cases. 72 ran through `rag.py` on the VM (`eval/answers_vm_redteam.jsonl`), 15 in the app on
the Pixel.

## What it found, and what was done

| Finding | Status |
|---|---|
| Questions about places of other kinds (coworking, laundry, hospitals) went to Wikipedia, and the model named places that do not exist | places.db now has what a traveller looks for (8.6M places: pharmacies, ATMs and money changers, hospitals, supermarkets, laundries, coworking, stations, sights); all of those questions take the places route, found in 6-225 ms on the laptop |
| A question in Chinese ("推荐里斯本最好的素食餐厅") went to Wikipedia, with invented restaurants | a question in another script, or in Spanish, French, German, Italian or Portuguese, is translated into English first (a 4 s generation) and answered in its own language |
| Calculations went wrong: Bush's age when the Wall fell (64, he was 65), $10,000 at 5% for 20 years ($16,386, it is $26,533), the Moon at basketball scale (10 m, it is 7.3 m), Lisbon to Porto at 100 km/h from 9am ("3 PM") | a question that needs a calculation gets a few lines of working first (`WORKED_SYSTEM`); on the VM all four came out right, in the same time (below) |
| Follow-ups were read as new questions ("what about Porto?", "when did he win the Nobel prize?") | a follow-up is rewritten from the previous question and answer; on the phone "what about Porto?" became "Tell me the best vegan restaurants in Porto" and "when did he win the Nobel prize?" became "When did Gabriel García Márquez win the Nobel Prize?" (15-17 s) |
| A wrong draft corrected only at the bottom of the source check (the World Cup winner) is easy to read as the answer | when the check lists corrections, a banner above the answer says so |
| The places answer took 60-110 s for what the list already said, and a shorter prompt made the model praise places it knew nothing about ("top-rated", "cozy setting") | a plain request for places is answered by the ranked list, without the model (below); the model writes only for a question that asks for more (tipping, safety, a comparison, opening days) or is in another language, and may not describe a place beyond what the list says |
| Vegan places were labelled "vegetarian restaurant" in answers to vegetarian questions | labelled vegan |
| A neighbourhood or a village ("Shoreditch", "Canggu") is not a city in GeoNames' list, so the question still goes to Wikipedia | open (a gazetteer of well-known neighbourhoods is the next step) |
| The source check is slow: 70-85 s of a 100-140 s answer-first question, mostly reading about 1,100 tokens of sources | open |
| The source check is sometimes pedantic or wrong: it "corrected" the Lisbon-Porto drive to the rail distance, Japan's islands, Mercury's day | open |
| Ethereum questions past the basics (proposer-builder separation, Verkle trees) are thin: Wikipedia has little on them | open (the EIPs, CC0, and the ethereum.org docs are candidate sources) |
| Practical travel questions (visas, tap water, tipping, scams, emergency numbers) | good already, from the model and Wikivoyage |

## Calculations: worked answers (VM, `eval/answers_q2kxl_auto_worked.jsonl`)

`rag.py --worked` (and `ResearchConfig.worked`, on in the app) gives a question that needs a
calculation (`needs_working`: "how old was", "how long does ... take", "how much is ... after",
"if ... were") a system prompt that asks for the facts, each step of the arithmetic, and a last
line starting "Answer:". The 8 calculation questions, before and after:

| Question | Before | Worked |
|---|---|---|
| Lisbon to Porto at 100 km/h from 9am | "3:00 PM", then 12:06 in the working | 12:06 (310 km); the check then "corrected" it to the rail distance |
| Jupiter's volume | 1,321 | 1,321 |
| Great Wall or Colosseum, by how much | 500-700 years | about 770 years |
| 300 km in 2.5 h, how long for 720 km | 6 h | 6 h |
| Japan or the Netherlands, denser | right (not a calculation) | unchanged |
| The Moon at basketball scale | 10 m | 7.3 m |
| Bush's age when the Wall fell | 64 | 65 |
| $10,000 at 5% for 20 years | $16,386 (then worked out $26,389 and $26,533 in the text) | $26,532.98 |

Time to the finished answer is the same (65-89 s on the VM for both).

## Places: the list is the answer

A request for places and nothing more ("Tell me the best vegan restaurants in Lisbon", "a
pharmacy near me", "cheap hostels in Berlin") is answered by the ranked list itself: the app looks
it up before loading the model, and does not load it. The answer's text (for the history and a
follow-up) is the list's first six places as the list shows them. The model is used when the
question needs words: it is in another language, or it asks for more than places (`ADVICE` in
`places.py`: costs, tipping, safety, a comparison, a quality such as romantic or quiet, opening
days). Measured on the Pixel 8 Pro:

- "Tell me the best vegan restaurants in Lisbon" from a cold start: done 61 ms after the question
  (the app's start and the lookup's first file reads came before it), no model loaded.
- The 24 questions of `eval/questions_places.jsonl` (all plain requests), each from a cold start
  (`scripts/phone_eval.sh`, `eval/answers_phone_places_listonly.jsonl`): all 24 on the places
  route, done a median 78 ms after the question (42-143 ms), none loading the model. Before: the
  model's picks were done after a median 68 s.
- "what about Porto?" after it: the model loads (about 20 s), the rewrite takes 15.6 s, then the
  Porto list.
- "Best vegan restaurants in Lisbon, and how much should I tip?": the tipping advice (5-10%, not
  mandatory) and three places, done 44 s after the question, including the model's load.
- "里斯本最好的素食餐厅有哪些？": translated in 4 s, answered in Chinese, done in 37 s.

## Still open, for round two

1. The source check's time: it reads about 1,100 tokens of sources at about 23 tokens/s, then
   writes 110-150 tokens. Options: fewer or shorter passages for the check, a check that says
   only what is wrong (the additions were graded useful before), or showing the answer as done
   while the check runs.
2. The check's pedantry (the rail distance for a drive).
3. Neighbourhoods and villages for places questions.
4. Ethereum depth.
