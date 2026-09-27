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
| The places answer took 60-110 s for what the list already said, and a shorter prompt made the model praise places it knew nothing about ("top-rated", "cozy setting") | the model reads more about each place (its Wikipedia article's start, its cuisine) and may not describe a place beyond that (below) |
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

## Places: the model writes from a richer list

The first answer to "the model takes 60-110 s to repeat what the list says" was to let the list
be the answer for a plain request, without the model: 24 of 24 places questions were then done a
median 78 ms after the question from a cold start (`eval/answers_phone_places_listonly.jsonl`).
That was reversed the same day: this is an AI research app, a list with no model in it reads as a
map search, and the rule that decided when the model wrote was one more hand-written word list.
The model now writes every places answer again; the list is still on screen in about 0.1 s (while
the model loads, on a cold start), and the model's lines follow.

What changed is what the model reads, so that it has something true to say: besides the kind of
place, the diet, the distance and the travel guide's words, each of the six places it reads now
has its cuisine (from OpenStreetMap) and, for a place with its own Wikipedia article, the start
of that article ("Sushi Yoshitake is a Michelin 3-star sushi restaurant in Ginza ..."; places.db
format 4 stores the article's title in `places.wiki`). The prompt forbids saying anything about
a place that the list does not say (no praise, ratings, atmosphere, dishes, prices), except for a
famous place the model knows well; with it the model stopped inventing "top-rated" and "cozy".

Adding the articles showed that the check deciding whether an article is about a place looked at
the whole first sentence: a place called "Linkin Park" got the band's article ("park"), a "Grand
Budapest Hotel" the film's, "Real Madrid" the football club's. It now reads only what the
sentence says the subject is ("is a sushi restaurant", up to "in", "located", "founded" and the
like) and rejects films, bands, series, people and sports clubs; the Colosseum, the Empire State
Building and Hyde Park, which the old check missed, now count.

Measured on the Pixel 8 Pro (other runs):

- "what about Porto?" after the Lisbon question: the rewrite takes 15.6 s, then the Porto list.
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
