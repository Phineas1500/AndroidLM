# Fuller restaurant answers (2026-09-29)

In the Vitalik-bar round ([2026-09-28-vitalik-bar.md](2026-09-28-vitalik-bar.md)) the
restaurant answers lost mostly on thinness. The graders' notes: "B's text names only three
places, with nothing but a distance for each", against a web answer of 8-11 places with where
each is and what it serves. One more fault: the Portuguese question got an English answer.

## The change

The model read six places. Each came with its kind, cuisine and distance, plus the travel
guide's and Wikipedia's words when there were any. It was asked for three to five one-liners
that say nothing the list does not.

It now reads eight places, each also with its street and its opening hours. It is asked for:
- six to eight places, a sentence or two each: what the place is and serves, its street, and
  what the guide or Wikipedia says;
- a closing line that map data can be out of date;
- the whole answer in the language of the question.

It still adds no dishes, prices, ratings, praise or remarks about areas that the list does not
give, unless it is a famous place it knows well.

The code:
- Python: `context_lines_v2` and `PLACES_SYSTEM_V2` in places.py.
- Kotlin: `PlacesText.describeV2` and `PLACES_SYSTEM_V2`, held to places.py by PlacesGoldenTest.
- The answer's limit is 800 tokens, up from 360.

## On the VM

The 20 restaurant questions went through `scripts/places_answers.py` on llama-server (same
model). Each comparison was graded blind, three answers per question side by side, by one
grader. Answers, sets, keys and grades are in `eval/*places_v*`.

| | Share of the reference | Errors | Better than the first version |
|---|---|---|---|
| First version | 41% | 30 | |
| Eight places with street, area and hours (v2) | 58% | 73 | 17 of 20 (worse on 1) |

- **v2's errors by kind:** closed places 38, hours 13, not vegan 10, areas 6, filler 6.
- **Why so many:** closed places are the map data's age, and they grow with the number of
  places named. The hours are map data, sometimes out of date or garbled. The areas were a guess
  (the nearest district of the cities table within 1.5 km), and about one in twelve was wrong
  (Organi Chiado "in Baixa").

A second grader compared v2 with a v3 without areas or hours:

| | Share of the reference | Errors |
|---|---|---|
| v2 | 74% | 23 |
| v3 | 70% | 14 |

v3 was worse on 7 questions and better on 1. This grader had no web search left and checked
places in OpenStreetMap, so it found few closures. It gave the reference 1 error where the first
grader gave 35: graders differ a lot, which is why each comparison was graded in one sitting.

**Shipped:** v2's lines and v3's prompt, which asks for no remarks about streets or areas. The
hours stay, because they are the same map data the app shows in each place's details. The areas
go, because they were our own guess.

## On the phone

The 20 restaurant questions were asked in the app on the Pixel 8 Pro (build 30997f4, places v21).
The three "near me" questions had a test GPS position. One grader scored, blind and side by side,
the app's answers from the Vitalik-bar round (places v18, the first version), the new ones
(`eval/answers_phone_places2.jsonl`) and the web reference (`eval/sets_places_phone.json`, key
and grades alongside):

| The 20 restaurant questions, on the phone | Mean /10 | Share of the reference | Errors | First words (median) | Done (median) |
|---|---|---|---|---|---|
| Before (1.2.0 and earlier) | 3.98 | 45% | 12 | 16 s | about 40 s |
| Fuller answers | 6.28 | **71%** | 21 | 26 s | 119 s (88-190 s) |
| Internet search + frontier AI | 8.88 | 100% | 3 | | |

- **Per question:** the fuller answers are better on all 20. The Portuguese question now gets a
  Portuguese answer.
- **Errors:** the extra ones are details:
  - hours garbled or contradicting themselves (Terra Mamma, Střecha);
  - a phone number given as an address (a data slip);
  - two addresses put in the wrong district of Hong Kong;
  - vegetarian places called vegan (Saravana Bhavan, Vegetalia).
- **Closed places:** this grader, with no web search left, checked closures in OpenStreetMap and
  HappyCow only. The VM's first grader, which searched the web, counted many more closed places
  (38 in 73 errors). Naming more places names more closed ones.
- **Time:** an answer is done after about two minutes instead of forty seconds. The model writes
  about 480 tokens at about 5 a second. The list of places is still on screen in 0.2 s, and the
  answer starts streaming after about 26 s.
- **Possible trim:** five or six places instead of eight would take about 30 s off; not measured.
