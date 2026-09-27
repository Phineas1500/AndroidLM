---
license: odbl
language:
- en
pretty_name: AndroidLM offline places (where to eat, drink, stay and what a traveller needs, worldwide; SQLite)
size_categories:
- 10M<n<100M
---

# AndroidLM offline places

`places.db` is the places database of [AndroidLM](https://github.com/Phineas1500/AndroidLM), an
offline research assistant for Android: 21.1 million places worldwide, 12.5 million of them to
eat, drink and stay and 8.6 million of the kinds a traveller looks for (pharmacies, ATMs and money
changers, hospitals and clinics, supermarkets, laundries, coworking spaces, stations, sights), for
questions like "the best vegan restaurants in Lisbon" or "a pharmacy near me", answered on the
phone with no network. It is an ordinary SQLite file (2.9GB) that any SQLite build opens.

## Contents

| Table | Contents |
|---|---|
| `places` | name, category, position (degrees × 10^5), a 0.05-degree grid `cell` for lookups, diet tags (bit flags: vegan only/yes/limited/no, vegetarian only/yes/limited, gluten-free, halal, kosher), street, locality, phone, website, opening hours, cuisine, which sources it came from, Overture's existence confidence, whether it is a chain, a static rank, and `fame`: the monthly views of the place's own English Wikipedia article, when it has one (80,049 places) |
| `kinds` | Overture's categories (434) with the categories above each |
| `guide` | Wikivoyage Eat, Drink, Sleep, See, Do and Buy listings matched to places (132,361 listings, 129,695 places): the guide article, section, price tier and the listing's name. The listing text itself is in Wikivoyage |
| `cities`, `city_names` | GeoNames cities of 1,000 people or more (171,075) and their names, including Latin-script alternate names for cities of 15,000 or more |
| `countries`, `region_names` | Country and first-level region names, for questions like "Paris, Texas" |
| `meta` | build date, sources, licence |

Built by `scripts/build_places.py` in the AndroidLM repository from:

- **Overture Maps places**, release 2026-09-23.1: categories under `food_and_drink` and `lodging`
  with existence confidence 0.3 or more, and the traveller's categories (pharmacies, health, money,
  shops, laundries, coworking, post offices, police, embassies, sights, transport, gyms) with 0.5
  or more; none marked permanently closed.
- **OpenStreetMap** places with a `diet:*` tag or a vegan or vegetarian `cuisine`, pharmacies,
  ATMs and money changers, fetched with `scripts/fetch_osm_diet.py` from QLever's copy of the
  planet: 234,174 matched to an Overture place by name within 120 m (adding their diet tags,
  opening hours and cuisine), the rest added as places of their own. Records with the same name
  and kind within about 300 m are folded into one (21,225,505 before, 21,132,717 after).
- **GeoNames** cities1000, admin1 codes and country information.
- **Wikivoyage** listings (English, September 2026), matched by name near the guide's city.
- **Wikipedia** (English, FineWiki August 2025) and Wikimedia pageviews: a place whose name is an
  article about that kind of place, near the article's coordinates, gets that article's monthly
  views as `fame` (the Louvre, the British Museum). Only the number is stored.

## Licence and attribution

This database contains data from OpenStreetMap and is made available under the
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).

- © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
- Overture Maps Foundation places: CDLA-Permissive-2.0; records from Foursquare Open Source Places
  are Apache-2.0 and records from AllThePlaces are CC0-1.0 (https://docs.overturemaps.org/attribution/).
- GeoNames (https://www.geonames.org), CC BY 4.0.
- Wikivoyage listing names and article titles: Wikivoyage contributors, CC BY-SA 4.0.
- Wikimedia pageviews (the `fame` numbers): CC0.

Map data has no ratings and places close: the app says so with every list.
