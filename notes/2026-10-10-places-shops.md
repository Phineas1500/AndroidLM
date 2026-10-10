# Places: shops by kind and places to go out (2026-10-10)

`places.db` listed where to eat, drink and stay and what a traveller needs (pharmacies, ATMs,
supermarkets, stations, sights). A question about another kind of place went to the general
route, which answers from the model first and checks it against Wikipedia afterwards. On the
Pixel 8 Pro under GrapheneOS, with 1.7.1:

- **"Where are the arcades in Paraguay?":** named shopping malls, a "Kidzania" and casinos, some
  real, several not.
- **"Video game shops in Santiago, Chile":** named chains with "multiple locations", such as "El
  Rincón del Videojuego", "Game House" and "Mundo Gamer", and Sodimac, a hardware chain, as a video
  game seller.

The source check below each answer said the sources did not mention any such place, but the list
above it read as fact. Overture Maps, which `places.db` is built from, has these places. The build
had filtered them out.

## What was added

Overture places 2026-09-23.1 has 80.5 million named places that are not closed. 1.7 kept 20.6
million of them. Counted at the build's confidence threshold for non-food places (0.5):

| Option | More places | About |
|---|---|---|
| Travel shops (electronics, books, toys and video games, sporting goods, outdoor, bikes, hardware, clothes, shoes, department stores, second-hand, souvenirs, eyewear, luggage, liquor, tobacco, music, pets...) | 5.1 M | 0.70 GB |
| Places to go out (arcades, bowling, escape rooms, climbing, cinemas, casinos, live music, comedy...) | 0.5 M | 0.07 GB |
| All of shopping, entertainment and sports | 11.3 M | 1.56 GB |
| Everything Overture has | ~43 M | ~6 GB, over the 50 GB cap |

The build now keeps 39 more categories (`MORE` in `scripts/build_places.py`). Only
`hardware_store` comes from the home-and-garden branch: the whole branch has 1.6 million places.
That is 4.1 million more places, and `places.db` grew from 2.90 to 3.48 GB, so the five files take
46.2 GB.

The parser (`scripts/places.py`, mirrored in `Places.kt`) has 27 new groups and the words for them.
Examples: "video game shops", "bookshops", "where can I buy wine", "camping gear", "bike repair",
"souvenirs", "arcades", "escape rooms", "bowling", "cinemas", "live music", "comedy clubs".

- **Longer phrases win:** "wine shop" is a liquor store and "wine bar" still a bar; "camping gear" is
  an outdoor shop, not a campsite; "pet food" is a pet shop, not a restaurant.
- **"Shopping arcade" is a shop.**
- **Not routed:** "Who invented the arcade game Pong?" and "Is it safe to go climbing in
  Patagonia?" are not places questions.

## Nothing changes for the places that were there

The new places are kept apart wherever the build compares places with each other:

- **OpenStreetMap matches:** OSM's diet-tagged places are matched only to the earlier categories.
  The first build, without this, matched 1,182 more of them. That could merge a vegan restaurant
  into a same-named shop, and the vegan category came out 10 places smaller.
- **Duplicates:** places are folded only within the earlier categories, or within the new ones.
- **Travel-guide listings:** Wikivoyage listings match only the earlier categories (132,361
  matched, as in v21).
- **Name counts:** for the earlier places, how common a name is gets counted among the earlier
  places only. That count decides whether a place may take a Wikipedia article's fame.

Checks, against v21 with the 1.7 code:

- **Parse:** 264 questions from the eval sets parse exactly as before. One more, "Where can I buy a
  SIM card in Tbilisi?", went to a list of phone shops. That word was taken back out, because
  Wikivoyage's answer (which carrier, bring a passport) is the better one.
- **Lists:** 300 old-style questions in 20 cities (vegan restaurants, hostels, pharmacies, ATMs,
  coffee, sushi, hospitals, gyms...). 294 give the same list and count. 4 differ only in the count,
  by one. 2 lists differ, both where the build's arbitrary row order breaks a tie:
  - **Medellín:** a Wikivoyage listing went to the other of two records at the same coordinates.
  - **Tokyo:** two gyms of almost the same rank swapped at the 400-candidate cut.
- **Golden test:** the Kotlin port reproduces `places_golden.json` from a new sample of v22: 77
  questions, 18 of them new, and the 46 pipeline tests pass.
- **Older file:** with a `places.db` from before this, a question for a new kind of place goes to
  the general route as before (`covers`, PlacesCoverTest). On the phone, the arcade question did
  that with the new app and the old file.

## The data is only as good as Overture's labels

Where the labels are good, the lists are real places:

- **Santiago:** Mega Games, Punto Game and Tienda Kokiri for video games.
- **Lisbon:** Livraria Buchholz and Tigre de Papel for bookshops.
- **Nairobi:** 20th Century, Prestige and Century Cinemax for cinemas.

Some categories in some places are mostly wrong:

- **Asunción's "arcades":** include a school centre, a dance studio, a shopping centre, a radio club
  and two towns.
- **Nairobi's cinemas:** include "KFC Westgate".

Overture's confidence does not separate these: the school centre has 91, the real GoPlay 71. Places
named like a nearby town are 0.04% of the new places, too few for a filter to matter. The Overture
rows have no alternate categories and no brand to go on either.

Two fixes were tried, and neither was kept:

- **A line for the model, for the new kinds only:** "Map data often gives a place the wrong kind.
  Leave out any place whose name shows it is something else...". It went after the list, so the
  older kinds' prompts stayed the same. On the phone:
  - **Asunción's arcades:** the same school centre, dance studio and radio club, and two more.
  - **Santiago's video game shops:** six instead of eight, one of the two dropped a real shop.
  - **Lisbon's bookshops:** every place got "The list identifies it as a general bookstore
    without further descriptive details".
- **A rule in the build on a name's first word:** a new place is dropped when its first word is
  common (200 places or more), at least 80% of those places are in one other group, and under 2%
  are in its own.
  - It drops 0.7% of the new places. "KFC Westgate" (a cinema) and "Supermercado Stock" (an
    arcade) go.
  - A sample of the drops is full of real shops: a Budapest bookshop, toy, vape and optician
    shops, and a Rolex retailer.
  - The Asunción "Shopping…" arcades stay.
  - Looser thresholds (50 places, 50%, 5%) drop 4.4%, with more real shops among them.

## On the phone

A test build of this code (`wip-places22`) on the Pixel 8 Pro under GrapheneOS:

- **Update:** the Set up card showed "Update available: Places to eat, stay, shop and go out
  (3.5 GB)". Then `places.db` v22 was copied into Downloads, and **Import files…** copied and checked
  it ("the download was deleted").
- **Before the update:** "Where are the arcades in Paraguay?" went to the general route, with no
  error, as with 1.7.1.
- **Old questions:** the Lisbon vegan restaurants and the cheap hostels in Santiago were answered
  word for word as on 1.7.0 and 1.7.1.
- **New questions:** all went to the list, done in 77-90 s. On the general route they had taken
  106-217 s.

| Question | Found | Answer |
|---|---|---|
| Video game shops in Santiago, Chile | 66 | eight video game shops with addresses (Mega Games, Punto Game, Tienda Kokiri, PiedraBruja...) |
| Best bookshops in Lisbon | 186 | Livraria Buchholz, Linha de Sombra, Tigre de Papel, Bivar, Under the Cover, Kingpin Books... |
| Where can I buy camping gear in Cusco? | 20 | sporting goods shops (Tatoo Adventure Gear, Marathon Sports...), after a sentence on trekking gear |
| Cinemas in Nairobi | 39 | 20th Century, Prestige, Westgate, Century Cinemax... and "KFC Westgate" as a cinema |
| Where are the arcades in Paraguay? | 21 | the mislabelled places above, described as arcades |

The crash log stayed empty.
