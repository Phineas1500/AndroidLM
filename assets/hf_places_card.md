---
license: odbl
language:
- en
pretty_name: AndroidLM offline places (where to eat, drink and stay, worldwide; SQLite)
size_categories:
- 10M<n<100M
---

# AndroidLM offline places

`places.db` is the places database of [AndroidLM](https://github.com/Phineas1500/AndroidLM), an
offline research assistant for Android: 12.6 million places to eat, drink and stay, worldwide,
for questions like "the best vegan restaurants in Lisbon" or "cheap hostels near me", answered
on the phone with no network. It is an ordinary SQLite file (1.7GB) that any SQLite build opens.

## Contents

| Table | Contents |
|---|---|
| `places` | name, category, position (degrees × 10^5), a 0.05-degree grid `cell` for lookups, diet tags (bit flags: vegan only/yes/limited/no, vegetarian only/yes/limited, gluten-free, halal, kosher), street, locality, phone, website, opening hours, cuisine, which sources it came from, Overture's existence confidence, whether it is a chain, and a static rank |
| `kinds` | Overture's categories (276 under food_and_drink and lodging) with the categories above each |
| `guide` | Wikivoyage Eat, Drink and Sleep listings matched to places (83,732): the guide article, section, price tier and the listing's name. The listing text itself is in Wikivoyage |
| `cities`, `city_names` | GeoNames cities of 1,000 people or more (171,075) and their names, including Latin-script alternate names for cities of 15,000 or more |
| `countries`, `region_names` | Country and first-level region names, for questions like "Paris, Texas" |
| `meta` | build date, sources, licence |

Built by `scripts/build_places.py` in the AndroidLM repository from:

- **Overture Maps places**, release 2026-09-23.1, categories under `food_and_drink` and `lodging`,
  without places marked permanently closed or with existence confidence under 0.3 (12.5M).
- **OpenStreetMap** places with a `diet:*` tag or a vegan or vegetarian `cuisine`, fetched with
  `scripts/fetch_osm_diet.py` from QLever's copy of the planet (142,500 food places): 93,211
  matched to an Overture place by name within 120 m (adding their diet tags, opening hours and
  cuisine), the rest added as places of their own.
- **GeoNames** cities1000, admin1 codes and country information.
- **Wikivoyage** listings (English, September 2026), matched by name near the guide's city.

## Licence and attribution

This database contains data from OpenStreetMap and is made available under the
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).

- © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
- Overture Maps Foundation places: CDLA-Permissive-2.0; records from Foursquare Open Source Places
  are Apache-2.0 and records from AllThePlaces are CC0-1.0 (https://docs.overturemaps.org/attribution/).
- GeoNames (https://www.geonames.org), CC BY 4.0.
- Wikivoyage listing names and article titles: Wikivoyage contributors, CC BY-SA 4.0.

Map data has no ratings and places close: the app says so with every list.
