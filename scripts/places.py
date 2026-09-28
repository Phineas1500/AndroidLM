#!/usr/bin/env python3
"""Answering "where to eat, drink or stay" questions from places.db (build_places.py).

  parse(question)          -> PlaceAsk or None: what kind of place, which diet or cuisine, where
  locate(db, ask)          -> the city the question names (or None)
  find(db, ask, city, ...) -> the matching places, ranked
  context(places, ...)     -> the numbered list the model reads

A question takes this route only when it asks for places of a kind (restaurants, cafés, bars,
bakeries, hotels...) or for a diet or cuisine, somewhere this file can place (a city in GeoNames,
or the phone's own position for "near me"). Everything else goes to the Wikipedia pipeline.

The app runs the same logic in Kotlin (app-android/research/.../Places.kt); keep them in step.

    python scripts/places.py places.db "best vegan restaurants in Lisbon" [--voyage voyage.db]
"""
import argparse
import math
import re
import sqlite3
import sys
from dataclasses import dataclass, field

sys.path.insert(0, __import__("os").path.dirname(__file__))
from build_places import (CELL_COLS, GLUTEN_FREE, HALAL, KOSHER, SRC_GUIDE, SRC_OSM, SRC_OVERTURE,  # noqa: E402
                          VEGAN_LIMITED, VEGAN_NO, VEGAN_ONLY, VEGAN_YES, VEGETARIAN_LIMITED,
                          VEGETARIAN_ONLY, VEGETARIAN_YES, city_radius_km, fame_bonus, norm_key)

# ---- the question -----------------------------------------------------------------------------

# what the question asks for: a group of categories (matched against a place's category and the
# categories above it)
GROUPS = {
    "eat": ("restaurant", "casual_eatery", "food_and_drink", "non_alcoholic_beverage_venue"),
    "cafe": ("cafe", "coffee_shop", "coffee_roastery", "non_alcoholic_beverage_venue", "tea_room",
             "bubble_tea_shop", "breakfast_and_brunch_restaurant"),
    "drink": ("alcoholic_beverage_venue",),
    "sweet": ("bakery", "patisserie", "dessert_shop", "ice_cream_shop", "donut_shop", "cupcake_shop",
              "candy_store", "chocolatier", "frozen_yoghurt_shop", "gelato"),
    "stay": ("lodging",),
    # what else a traveller looks for (build_places.py TRAVEL)
    "pharmacy": ("pharmacy_and_drug_store",),
    "health": ("hospital", "emergency_or_urgent_care_facility", "primary_care_or_general_clinic", "dental_clinic"),
    "money": ("atm", "bank_or_credit_union", "currency_exchange"),
    "phone": ("mobile_phone_store", "telecommunications_company"),
    "shop": ("grocery_store", "convenience_store", "shopping_mall", "market"),
    "laundry": ("laundromat", "laundry_service"),
    "coworking": ("coworking_space", "shared_office_space"),
    "post": ("post_office",),
    "police": ("police_station",),
    "embassy": ("embassy",),
    "sights": ("museum", "art_gallery", "zoo", "aquarium", "amusement_park", "theatre_venue", "monument", "historic_site",
               "castle", "palace", "fort", "religious_landmark", "botanical_garden", "park", "national_park", "beach",
               "public_plaza", "hiking_trail"),
    "transport": ("train_station", "bus_station", "metro_station", "airport", "car_rental_service", "bike_rental",
                  "scooter_rental", "ferry_service"),
    "fitness": ("gym",),
}
GROUP_LABEL = {"eat": "places to eat", "cafe": "cafes", "drink": "places to drink", "sweet": "bakeries and sweet shops",
               "stay": "places to stay", "pharmacy": "pharmacies", "health": "hospitals and clinics",
               "money": "ATMs, banks and money changers", "phone": "phone shops", "shop": "shops and markets",
               "laundry": "laundries", "coworking": "coworking spaces", "post": "post offices", "police": "police stations",
               "embassy": "embassies", "sights": "sights", "transport": "stations and transport", "fitness": "gyms"}
# words -> group; a word ending in "s" also matches without it
KIND_WORDS = [
    ("places to eat", "eat"), ("place to eat", "eat"), ("where to eat", "eat"), ("food", "eat"),
    ("restaurant", "eat"), ("eatery", "eat"), ("eateries", "eat"), ("dinner", "eat"), ("lunch", "eat"),
    ("meal", "eat"), ("dining", "eat"), ("eat", "eat"), ("brunch", "cafe"), ("breakfast", "cafe"),
    ("cafe", "cafe"), ("café", "cafe"), ("coffee", "cafe"), ("tea house", "cafe"),
    ("bar", "drink"), ("pub", "drink"), ("beer", "drink"), ("brewery", "drink"), ("breweries", "drink"),
    ("cocktail", "drink"), ("wine", "drink"), ("nightlife", "drink"), ("drinks", "drink"),
    ("bakery", "sweet"), ("bakeries", "sweet"), ("pastry", "sweet"), ("pastries", "sweet"),
    ("dessert", "sweet"), ("ice cream", "sweet"), ("gelato", "sweet"), ("donut", "sweet"),
    ("hotel", "stay"), ("hostel", "stay"), ("guesthouse", "stay"), ("guest house", "stay"),
    ("accommodation", "stay"), ("place to stay", "stay"), ("places to stay", "stay"),
    ("where to stay", "stay"), ("where should i stay", "stay"), ("where can i stay", "stay"),
    ("where to sleep", "stay"), ("bed and breakfast", "stay"), ("b&b", "stay"),
    ("pharmacy", "pharmacy"), ("pharmacies", "pharmacy"), ("chemist", "pharmacy"), ("drugstore", "pharmacy"),
    ("hospital", "health"), ("clinic", "health"), ("doctor", "health"), ("urgent care", "health"),
    ("emergency room", "health"), ("dentist", "health"),
    ("atm", "money"), ("cash machine", "money"), ("withdraw cash", "money"), ("withdraw money", "money"), ("bank", "money"),
    ("currency exchange", "money"), ("exchange money", "money"), ("change money", "money"), ("money changer", "money"),
    ("phone shop", "phone"), ("phone store", "phone"), ("mobile phone shop", "phone"),
    ("supermarket", "shop"), ("grocery", "shop"), ("groceries", "shop"), ("convenience store", "shop"),
    ("shopping mall", "shop"), ("mall", "shop"), ("market", "shop"),
    ("laundry", "laundry"), ("laundromat", "laundry"), ("coworking", "coworking"), ("co-working", "coworking"),
    ("post office", "post"), ("police station", "police"), ("embassy", "embassy"), ("embassies", "embassy"),
    ("consulate", "embassy"),
    ("museum", "sights"), ("gallery", "sights"), ("galleries", "sights"), ("zoo", "sights"), ("aquarium", "sights"),
    ("castle", "sights"), ("palace", "sights"), ("park", "sights"), ("beach", "sights"), ("beaches", "sights"),
    ("temple", "sights"), ("church", "sights"), ("churches", "sights"), ("monument", "sights"), ("hiking", "sights"),
    ("botanical garden", "sights"),
    ("train station", "transport"), ("bus station", "transport"), ("metro station", "transport"),
    ("subway station", "transport"), ("airport", "transport"), ("car rental", "transport"), ("rent a car", "transport"),
    ("bike rental", "transport"), ("rent a bike", "transport"), ("scooter rental", "transport"), ("ferry", "transport"),
    ("gym", "fitness"),
]
DIET_WORDS = [
    ("plant based", "vegan"), ("plant-based", "vegan"), ("vegan", "vegan"),
    ("vegetarian", "vegetarian"), ("veggie", "vegetarian"),
    ("gluten free", "gluten_free"), ("gluten-free", "gluten_free"), ("celiac", "gluten_free"),
    ("coeliac", "gluten_free"), ("halal", "halal"), ("kosher", "kosher"),
]
# words for one kind of place within a group (a softer filter than the group: dropped when it
# leaves fewer than MIN_SUB places), and cuisine words whose category is not "<word>_restaurant"
SUB_WORDS = {"hostel": "hostel", "bed and breakfast": "bed_and_breakfast", "b&b": "bed_and_breakfast",
             "guest house": "guest_house", "guesthouse": "guest_house", "campsite": "campground",
             "camping": "campground", "hotel": "hotel", "bakery": "bakery", "bakeries": "bakery",
             "ice cream": "ice_cream_shop", "gelato": "ice_cream_shop", "donut": "donut_shop",
             "dessert": "dessert_shop", "brewery": "brewery", "breweries": "brewery", "pub": "pub",
             "brunch": "breakfast_and_brunch_restaurant", "breakfast": "breakfast_and_brunch_restaurant",
             "coffee": "coffee_shop", "steak": "steakhouse", "tapas": "tapas_bar",
             "dim sum": "dim_sum_restaurant", "noodle": "noodles_restaurant", "taco": "taco_restaurant",
             "burger": "burger_restaurant", "dumpling": "dumpling_restaurant", "curry": "indian_restaurant",
             "street food": "food_stand", "food truck": "food_truck_stand", "juice": "smoothie_juice_bar",
             "bubble tea": "bubble_tea_shop", "wine bar": "wine_bar", "cocktail bar": "cocktail_bar",
             "hospital": "hospital", "dentist": "dental_clinic", "urgent care": "emergency_or_urgent_care_facility",
             "emergency room": "emergency_or_urgent_care_facility", "clinic": "primary_care_or_general_clinic",
             "doctor": "primary_care_or_general_clinic", "atm": "atm", "cash machine": "atm", "withdraw cash": "atm",
             "withdraw money": "atm", "bank": "bank_or_credit_union", "currency exchange": "currency_exchange",
             "exchange money": "currency_exchange", "change money": "currency_exchange",
             "money changer": "currency_exchange", "supermarket": "grocery_store", "grocery": "grocery_store",
             "groceries": "grocery_store", "convenience store": "convenience_store", "shopping mall": "shopping_mall",
             "mall": "shopping_mall", "market": "market", "museum": "museum", "gallery": "art_gallery",
             "galleries": "art_gallery", "zoo": "zoo", "aquarium": "aquarium", "castle": "castle", "palace": "palace",
             "park": "park", "beach": "beach", "beaches": "beach", "temple": "religious_landmark",
             "church": "religious_landmark", "churches": "religious_landmark", "monument": "monument",
             "hiking": "hiking_trail", "botanical garden": "botanical_garden", "train station": "train_station",
             "bus station": "bus_station", "metro station": "metro_station", "subway station": "metro_station",
             "airport": "airport", "car rental": "car_rental_service", "rent a car": "car_rental_service",
             "bike rental": "bike_rental", "rent a bike": "bike_rental", "scooter rental": "scooter_rental",
             "ferry": "ferry_service"}
RECOMMEND = re.compile(r"\b(best|good|great|top|recommend\w*|suggest\w*|where|find|any|list|options?|"
                       r"places?|spots?|cheap|affordable|nice|popular|famous|must|should i|can i|could i|"
                       r"favou?rite|near|nearby)\b")
# a question about a place's food or drink in general, not a request for places
GENERAL = re.compile(r"\b(what('s| is| are) (the )?\w+( \w+)? (like|scene)|typical|traditional|cuisine of|dishes|known for|famous for|specialit(y|ies)|must[- ]try)\b")
KNOWLEDGE = re.compile(r"\b(history|origins?|invented|why|who (founded|owns|opened)|when (was|did)|how (did|do|does|is|are) \w+ (made|cooked|prepared)|"
                       r"oldest|first|largest|biggest|how many)\b")
# a trip question with more in it than places (what to see, getting around, an itinerary): the
# travel guide answers it, not a list of places
TRIP = re.compile(r"\b(what (should|can|to) (i|we) (see|do|visit)|things to (do|see)|sights|sightseeing|get(ting)? around|"
                  r"itinerary|how (do|can|should) (i|we) get|\d+ days|(two|three|four|five|six|seven|few) days|a week in|weekend in)\b")
# words that name places to go to: with a city, a request for places even without "best" or "where"
PLACE_NOUNS = re.compile(r"\b(restaurants?|cafes?|caf\u00e9s?|coffee shops?|bars?|pubs?|hotels?|hostels?|bakery|bakeries|"
                         r"eatery|eateries|guest ?houses?|bistros?|brewery|breweries|pharmacy|pharmacies|chemists?|"
                         r"hospitals?|clinics?|dentists?|atms?|banks?|supermarkets?|groceries|grocery stores?|"
                         r"museums?|galleries|gallery|beaches|beach|parks?|gyms?|laundromats?|laundry|coworking|"
                         r"embassy|embassies|markets?|malls?)\b")
# a question about opening hours late in the day
LATE = re.compile(r"\b(open late|late at night|late night|late-night|24 hours|24/7|all night|open now|tonight|after midnight|at night)\b")
HOURS = re.compile(r"\b(open|opening|hours|clos(e|es|ed|ing)|late|tonight|now|today|tomorrow|morning|breakfast|"
                   r"monday|tuesday|wednesday|thursday|friday|saturday|sunday|weekends?|24/7)\b")
LATE_HOURS = re.compile(r"24/7|-\s*(2[2-4]|0[0-5])[:.]")
CRYPTO = re.compile(r"\b(bitcoin|crypto|btc)", re.I)
HERE = re.compile(r"\b(near me|nearby|around me|around here|near here|close to me|close by|where i am|"
                  r"my location|this city|this town|city i am (currently )?in|city i'm (currently )?in|"
                  r"current city|my area|in my city)\b")
ANCHOR = re.compile(r"\b(in|near|around|at|of|for|across|throughout)\s+(?:the\s+)?", re.I)

MIN_SUB = 3
CANDIDATES = 400  # rows read for a lookup without a diet (by the static rank, build_places.py)
CHEAP = re.compile(r"\b(cheap|budget|affordable|inexpensive|low[- ]cost)\b")
FANCY = re.compile(r"\b(upscale|luxury|luxurious|fine dining|splurge|expensive|fancy|high[- ]end|michelin)\b")
# categories that are diets, not cuisines
DIET_CATS = {"vegan_restaurant", "vegetarian_restaurant", "gluten_free_restaurant", "halal_restaurant",
             "kosher_restaurant", "special_diet_restaurant", "health_food_restaurant", "live_and_raw_food_restaurant"}

DIET_MASK = {"vegan": 0, "vegetarian": 0, "gluten_free": GLUTEN_FREE, "halal": HALAL, "kosher": KOSHER}
DIET_KIND = {"vegan": "vegan_restaurant", "vegetarian": "vegetarian_restaurant", "gluten_free": "gluten_free_restaurant",
             "halal": "halal_restaurant", "kosher": "kosher_restaurant"}
VEGAN_NAME = re.compile(r"\b(vegan[oa]?s?|v[eé]gane?s?|plant[- ]based|vegana?s?)\b", re.I)
VEGETARIAN_NAME = re.compile(r"\b(vegetarian[oa]?s?|v[eé]g[eé]tarien(ne)?s?|veggie|vegetariano)\b", re.I)


@dataclass
class PlaceAsk:
    group: str                 # eat / cafe / drink / sweet / stay
    diet: str = None           # vegan / vegetarian / gluten_free / halal / kosher
    sub: str = None            # one category within the group, e.g. ramen_restaurant, hostel
    price: str = None          # "Budget" or "Splurge" (the travel guide's price tiers)
    here: bool = False         # "near me": the phone's position
    restaurant: bool = False   # the question says "restaurant(s)": prefer restaurants to cafes and shops
    question: str = ""
    late: bool = False         # the question asks about late opening hours


def _has(text, phrase):
    return _at(text, phrase) is not None


def _at(text, phrase):
    """Where [phrase] (or its plural in "s") first occurs in [text] as whole words, or None."""
    m = re.search(rf"(?<![a-z0-9]){re.escape(phrase)}s?(?![a-z0-9])", text)
    return m.start() if m else None


def _first(text, pairs):
    """The value of the (phrase, value) pair whose phrase occurs first; at the same place the
    longer phrase, then the earlier pair."""
    best, key = None, None
    for i, (w, v) in enumerate(pairs):
        at = _at(text, w)
        if at is not None and (key is None or (at, -len(w), i) < key):
            best, key = v, (at, -len(w), i)
    return best


def parse(question, cuisines=()):
    """What the question asks for, or None when it does not ask for places. [cuisines]: the
    category names in places.db (to recognise "ramen", "Thai food"...)."""
    q = question.strip()
    low = q.lower()
    if KNOWLEDGE.search(low) or TRIP.search(low):
        return None
    diet = next((d for w, d in DIET_WORDS if _has(low, w)), None)
    # the kind of place named first is the one asked for ("a pharmacy near my hotel")
    group = _first(low, KIND_WORDS)
    # one kind within that group only ("a pharmacy near my hotel" is not about hotels)
    word_group = dict(KIND_WORDS)
    sub = _first(low, [(w, c) for w, c in SUB_WORDS.items() if word_group.get(w, group) == group])
    if sub is None:
        words = set(re.findall(r"[a-z]+", low))
        for cat in cuisines:
            if cat.endswith("_restaurant") and cat not in DIET_CATS:
                stem = cat[: -len("_restaurant")].replace("_", " ")
                if stem and stem not in ("fast food", "casual", "family") and _has(low, stem) \
                        and (" " in stem or stem in words):
                    sub = cat
                    break
    if group is None and diet is None and sub is None:
        return None
    if group is None:
        group = "eat"
    # a request for places: says so (best, where, recommend, places...), or names places in the
    # plural, or asks for a diet or a kind of place; "what is the food like" is not one
    asks = RECOMMEND.search(low) or PLACE_NOUNS.search(low)
    if not asks and (GENERAL.search(low) or not (diet or sub)):
        return None
    here = HERE.search(low) is not None
    if not here and not place_candidates(q):
        return None
    # the best museums or sights of a city are what the model and Wikipedia know well; the list
    # is for those near the phone
    if group == "sights" and not here:
        return None
    price = "Budget" if CHEAP.search(low) else "Splurge" if FANCY.search(low) else None
    return PlaceAsk(group, diet, sub, price, here, _has(low, "restaurant"), q, LATE.search(low) is not None)


CAPITALISED = re.compile(r"[A-Z][\w'\u2019.-]*(?:\s+(?:[A-Z][\w'\u2019.-]*|de|da|do|del|la|le|el|of|upon|am|sur|an|on|y|i)(?=\s+[A-Z]))*(?:\s+[A-Z][\w'\u2019.-]*)?")
CLAUSE_END = re.compile(r"[?!;:()\[\]]|\s(?:that|which|with|for|open|serving|where|who|to|and|or|but|if|while|when|because|so|I)\s")
# capitalised words that start questions and are also names of small towns
NOT_PLACES = {"best", "tell", "where", "what", "which", "who", "how", "can", "could", "should", "would",
              "any", "list", "give", "show", "find", "recommend", "suggest", "please", "i", "is", "are",
              "top", "good", "great", "cheap", "vegan", "vegetarian", "halal", "kosher", "some", "the"}


def place_candidates(q):
    """Where the question may name a place, most likely first: the words after each "in / near /
    around ..." (the last one first), then each run of capitalised words. (text, anchored)"""
    out = []
    for m in reversed(list(ANCHOR.finditer(q))):
        rest = CLAUSE_END.split(q[m.end():])[0].strip(" .,'\"")
        if rest:
            out.append((rest, True))
    for m in CAPITALISED.finditer(q):
        out.append((m.group(0).strip(" .,'\""), False))
    return out


# ---- where ------------------------------------------------------------------------------------

@dataclass
class City:
    id: int
    name: str
    country: str
    admin1: str
    lat: float
    lon: float
    population: int
    capital: int = 0
    country_name: str = ""

    @property
    def radius_km(self):
        return city_radius_km(self.population)

    def label(self):
        parts = [self.name]
        if self.admin1 and self.country in ("US", "CA", "AU", "IN", "BR", "MX") and self.admin1 != self.name:
            parts.append(self.admin1)
        parts.append(self.country_name or self.country)
        return ", ".join(parts)


PRIMARY_WEIGHT = 3  # a city whose own name is the one asked for counts as three times its size
CAPITAL_WEIGHT = 5  # and a national capital by its own name five times again ("Washington": the city, not the state)


def locate(db, ask):
    """The city the question names: for each candidate (place_candidates), the longest run of
    words at its start that names a city, narrowed by a country or region named after it
    ("Paris, Texas"). Among cities of that name the largest wins, a city whose own name it is
    (not an alternate name: "Porto" is also a name of Bordeaux) counting three times its size.
    After "in/near/around", a state or province counts too, by the people in all of its cities, and
    stands for its largest city ("Bali": Denpasar, not Bāli in West Bengal). National capitals count
    five times their size ("Washington"). A candidate after "in/near/around" is taken when it is capitalised or
    a city of 100,000 or more; a bare capitalised run only in the latter case. The word the question
    names its cuisine by is not a place ("thank you in Thai": Thai is also a name of Alor Setar)."""
    cuisine = norm_key(sub_word(ask.sub)) if ask.sub else None
    for text, anchored in place_candidates(ask.question):
        words = norm_key(text).split()
        capital = text[:1].isupper()
        for n in range(min(len(words), 5), 0, -1):
            key = " ".join(words[:n])
            if (not anchored and key in NOT_PLACES) or key == cuisine:
                continue
            ids = [r[0] for r in db.execute("select city from city_names where key = ?", (key,))]
            cities = [City(*r) for r in db.execute(
                f"select id, name, country, admin1, lat, lon, population, capital from cities where id in ({','.join('?' * len(ids))})",
                ids)] if ids else []
            rest = words[n:]
            if rest and cities:
                hinted = _hinted(db, cities, rest)
                if hinted:
                    cities = hinted
            best = max(cities, key=lambda c: (_weight(c, key), -c.id), default=None)
            weight = _weight(best, key) if best else 0
            if anchored:
                region, people = _region_city(db, key)
                if region is not None and people > weight:
                    best = region
            if best is None:
                continue
            if not ((anchored and capital) or best.population >= 100_000):
                continue
            row = db.execute("select name from countries where code = ?", (best.country,)).fetchone()
            best.country_name = row[0] if row else ""
            return best
    return None


def _weight(c, key):
    if norm_key(c.name) != key:
        return c.population  # an alternate name (Hong Kong was once "Victoria")
    return c.population * PRIMARY_WEIGHT * (CAPITAL_WEIGHT if c.capital else 1)


def _region_city(db, key):
    """The state or province named [key] with the most people in its cities: its largest city,
    and those people. (None, 0) when no region has that name."""
    best, people = None, 0
    for cc, a1 in db.execute("select country, admin1 from region_names where key = ? and admin1 <> '' order by country, admin1",
                             (key,)).fetchall():
        n = db.execute("select coalesce(sum(population), 0) from cities where country = ? and admin1 = ?", (cc, a1)).fetchone()[0]
        r = db.execute("select id, name, country, admin1, lat, lon, population, capital from cities "
                       "where country = ? and admin1 = ? order by population desc, id limit 1", (cc, a1)).fetchone()
        if r and n > people:
            best, people = City(*r), n
    return best, people


def _hinted(db, cities, rest):
    """The cities in the country or region named by some run of the words after the city name."""
    for i in range(len(rest)):
        for j in range(len(rest), i, -1):
            key = " ".join(rest[i:j])
            regions = db.execute("select country, admin1 from region_names where key = ?", (key,)).fetchall()
            if regions:
                ok = [c for c in cities if any(c.country == cc and (a1 == "" or a1 == c.admin1) for cc, a1 in regions)]
                if ok:
                    return ok
    return None


# ---- which places -----------------------------------------------------------------------------

@dataclass
class Place:
    id: int
    name: str
    kind: str
    kinds: tuple          # the category and the ones above it
    alt: str
    diet: int
    src: int
    conf: int
    chain: int
    lat: float
    lon: float
    street: str
    locality: str
    phone: str
    website: str
    hours: str
    cuisine: str
    fame: int = 0         # monthly views of the place's own Wikipedia article
    wiki: str = None      # that article's title
    km: float = 0.0
    tier: int = 9
    score: float = 0.0
    sub: bool = False
    guide: list = field(default_factory=list)   # (article, section, tier, listing)
    why: list = field(default_factory=list)      # short reasons, for the list and the model


def cells_around(lat, lon, km):
    dlat = km / 110.54
    dlon = km / (111.32 * max(math.cos(math.radians(lat)), 0.01))
    r0, r1 = int(math.floor((lat - dlat + 90.0) * 20.0)), int(math.floor((lat + dlat + 90.0) * 20.0))
    c0, c1 = int(math.floor((lon - dlon + 180.0) * 20.0)), int(math.floor((lon + dlon + 180.0) * 20.0))
    cells = []
    for r in range(max(r0, 0), min(r1, 3599) + 1):
        for c in range(c0, c1 + 1):
            cells.append(r * CELL_COLS + (c % CELL_COLS))
    return cells


def distance_km(lat1, lon1, lat2, lon2):
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return 6371.0 * math.hypot(x, y)


def _kinds(db):
    out = {}
    for i, name, parents in db.execute("select id, name, parents from kinds"):
        out[i] = (name, tuple(p for p in parents.split(",") if p) + (name,))
    return out


def in_group(ask, path):
    """A category (with the categories above it) belongs to what the question asks for."""
    if "lodging" in path:
        return ask.group == "stay"
    if ask.group == "eat":
        # drinks-only venues only when a diet or a kind of place narrows the list, and the
        # question does not ask for restaurants
        return "food_and_drink" in path and (
            "alcoholic_beverage_venue" not in path or (bool(ask.diet or ask.sub) and not ask.restaurant))
    return any(g in path for g in GROUPS[ask.group])


DIET_KINDS = {"vegan": ("vegan_restaurant", "vegetarian_restaurant"), "vegetarian": ("vegan_restaurant", "vegetarian_restaurant"),
              "gluten_free": ("gluten_free_restaurant",), "halal": ("halal_restaurant",), "kosher": ("kosher_restaurant",)}
DIET_BITS_ANY = {"vegan": VEGAN_ONLY | VEGAN_YES | VEGAN_LIMITED | VEGETARIAN_ONLY,
                 "vegetarian": VEGETARIAN_ONLY | VEGETARIAN_YES | VEGETARIAN_LIMITED | VEGAN_ONLY | VEGAN_YES | VEGAN_LIMITED,
                 "gluten_free": GLUTEN_FREE, "halal": HALAL, "kosher": KOSHER}
DIET_NAME_LIKE = {"vegan": ["%vegan%", "%végan%", "%plant%based%", "%vegetarian%", "%végétarien%", "%veggie%"],
                  "vegetarian": ["%vegetarian%", "%végétarien%", "%veggie%", "%vegan%", "%végan%"],
                  "gluten_free": ["%gluten%free%", "%sin gluten%", "%sans gluten%", "%glutenfrei%", "%senza glutine%", "%sin tacc%"],
                  "halal": ["%halal%"], "kosher": ["%kosher%"]}


def diet_tier(p, diet):
    """0: the place is all about the diet; 1: vegetarian place, for a vegan question; 2: serves it;
    3: limited options; None: not known to serve it."""
    names = (p.kind,) + tuple(a for a in (p.alt or "").split(",") if a)
    if diet == "vegan":
        # OpenStreetMap's "vegetarian only" outweighs a vegan category from the other source
        # (Lotos in Buenos Aires, Pine Tree Cafe in Singapore): the place is vegetarian
        if p.diet & VEGAN_ONLY or VEGAN_NAME.search(p.name) or \
                ("vegan_restaurant" in names and not p.diet & VEGETARIAN_ONLY):
            return 0
        if p.diet & VEGAN_NO:
            return None
        if p.diet & VEGETARIAN_ONLY or "vegetarian_restaurant" in names or VEGETARIAN_NAME.search(p.name):
            return 1
        if p.diet & VEGAN_YES:
            return 2
        if p.diet & VEGAN_LIMITED:
            return 3
        return None
    if diet == "vegetarian":
        if p.diet & (VEGETARIAN_ONLY | VEGAN_ONLY) or {"vegetarian_restaurant", "vegan_restaurant"} & set(names) \
                or VEGETARIAN_NAME.search(p.name) or VEGAN_NAME.search(p.name):
            return 0
        if p.diet & (VEGETARIAN_YES | VEGAN_YES):
            return 2
        if p.diet & (VEGETARIAN_LIMITED | VEGAN_LIMITED):
            return 3
        return None
    if DIET_KIND[diet] in names or any(_like(p.name, pat) for pat in DIET_NAME_LIKE[diet]):
        return 0
    if p.diet & DIET_MASK[diet]:
        return 2
    return None


def _like(text, pattern):
    """SQL LIKE (case-insensitive for ASCII) in Python."""
    rx = "".join(".*" if ch == "%" else "." if ch == "_" else re.escape(ch) for ch in pattern)
    return re.fullmatch(rx, text, re.I | re.S) is not None


DIET_LABEL = {
    ("vegan", 0): "vegan", ("vegan", 1): "vegetarian", ("vegan", 2): "vegan options", ("vegan", 3): "a few vegan options",
    ("vegetarian", 0): "vegetarian", ("vegetarian", 2): "vegetarian options", ("vegetarian", 3): "a few vegetarian options",
}


def find(db, ask, lat, lon, radius_km, limit=12):
    """The places around (lat, lon) that answer [ask], best first."""
    kinds = _kinds(db)
    group = GROUPS[ask.group]
    cells = cells_around(lat, lon, radius_km)
    # the group's categories, and the one kind of place asked for wherever it sits ("tapas bars":
    # tapas_bar is a casual eatery, not a bar)
    wanted = [i for i, (name, path) in kinds.items() if in_group(ask, path) or (ask.sub and ask.sub in path)]
    sql = (f"select id, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine, fame, "
           f"{_wiki_column(db)} "
           f"from places where cell in ({','.join('?' * len(cells))}) and kind in ({','.join('?' * len(wanted))})")
    args = cells + wanted
    if ask.diet:
        # a cheap first cut; diet_tier decides
        dk = [i for i, (name, path) in kinds.items() if set(DIET_KINDS[ask.diet]) & set(path)]
        sql += (f" and (diet & ? != 0 or kind in ({','.join('?' * len(dk))}) or alt like ? or alt like ?"
                + "".join(" or name like ?" for _ in DIET_NAME_LIKE[ask.diet]) + ")")
        args += [DIET_BITS_ANY[ask.diet]] + dk + ["%vegan_restaurant%", "%vegetarian_restaurant%"] + DIET_NAME_LIKE[ask.diet]
    sub_rx = re.compile(rf"\b{re.escape(sub_word(ask.sub))}") if ask.sub else None
    # without a diet (which decides the order first) only the best CANDIDATES by the question-free
    # part of the score are read: in a big city that is thousands of rows fewer
    cap = "" if ask.diet else f" order by rank desc, id limit {CANDIDATES}"
    capped = False
    out = None
    if ask.sub:
        # first only the places that may be of the one kind asked for (a superset of is_sub, so
        # the list is the same as filtering everything); all of them when too few are
        dk = [i for i, (name, path) in kinds.items() if ask.sub in path]
        w = sub_word(ask.sub)
        narrowed, n = _places(db, sql + (f" and (kind in ({','.join('?' * len(dk))}) or alt like ? or "
                                         f"replace(lower(cuisine), '_', ' ') like ? or name like ?)") + cap,
                              args + dk + [f"%{ask.sub}%", f"%{w}%", f"%{w}%"], ask, kinds, lat, lon, radius_km, sub_rx)
        subs = [p for p in narrowed if p.sub]
        if len(subs) >= MIN_SUB:
            out, capped = subs, bool(cap) and n == CANDIDATES
    if out is None:
        out, n = _places(db, sql + cap, args, ask, kinds, lat, lon, radius_km, sub_rx)
        capped = bool(cap) and n == CANDIDATES
    if ask.diet in ("vegan", "vegetarian"):
        overrule_branches(out, ask.diet)
    # one kind of place within the group, when there are enough of them
    if ask.sub:
        subs = [p for p in out if p.sub]
        if len(subs) >= MIN_SUB:
            out = subs
    # the travel guide's listings for these places
    if out:
        ids = [p.id for p in out]
        by_id = {p.id: p for p in out}
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            for place, article, section, gtier, listing in db.execute(
                    f"select place, article, section, tier, listing from guide where place in ({','.join('?' * len(chunk))}) "
                    f"order by place, rowid", chunk):
                by_id[place].guide.append((article, section, gtier, listing))
    for p in out:
        p.score = score(p, radius_km, ask.price, ask.late)
    # for "restaurants", restaurants before cafes, bakeries and shops of the same tier
    out.sort(key=lambda p: (p.tier, int(ask.restaurant and "restaurant" not in p.kinds), -p.score, p.id))
    # the same chain at most twice; another record of a place already listed (its name's words all
    # in the other's, within a kilometre: "British Museum, London" after "The British Museum") not at all
    seen, picked = {}, []
    for p in out:
        key = norm_key(p.name)
        if p.chain and seen.get(key, 0) >= 1 or seen.get(key, 0) >= 2:
            continue
        if any(_same_place(p, q) for q in picked):
            continue
        seen[key] = seen.get(key, 0) + 1
        picked.append(p)
        if len(picked) >= limit:
            break
    for p in picked:
        p.why = reasons(p, ask)
    return picked, len(out), capped


DUP_STOP = {"the", "at", "of", "and", "de", "del", "la", "le", "el", "da", "do", "di"}


def _words(name):
    return {w for w in norm_key(name).split() if w not in DUP_STOP}


def _same_place(p, q):
    """[p] is another record of [q]: one name's words all in the other's, within a kilometre."""
    a, b = _words(p.name), _words(q.name)
    return bool(a) and bool(b) and (a <= b or b <= a) and distance_km(p.lat, p.lon, q.lat, q.lon) <= 1.0


OPTIONS_BITS = {"vegan": VEGAN_YES | VEGAN_LIMITED, "vegetarian": VEGETARIAN_YES | VEGETARIAN_LIMITED | VEGAN_YES}


def overrule_branches(out, diet):
    """A branch tagged vegan-only (or vegetarian-only) whose same-name branches are tagged as
    serving options is a tagging slip: it gets the options tier. A place whose category or name
    says the diet keeps its tier."""
    groups = {}
    for p in out:
        groups.setdefault(norm_key(p.name), []).append(p)
    for group in groups.values():
        if len(group) < 2 or not any(m.diet & OPTIONS_BITS[diet] for m in group):
            continue
        for m in group:
            if m.tier == 0 and m.kind not in ("vegan_restaurant", "vegetarian_restaurant") \
                    and not VEGAN_NAME.search(m.name) and not VEGETARIAN_NAME.search(m.name):
                m.tier = 2


def _wiki_column(db):
    """The places' Wikipedia titles: the `wiki` column (places.db format 4), or none in an older file."""
    cols = {r[1] for r in db.execute("pragma table_info(places)")}
    return "wiki" if "wiki" in cols else "null"


def _places(db, sql, args, ask, kinds, lat, lon, radius_km, sub_rx):
    """The rows of [sql] as places within the radius that serve the diet asked for, with their
    tier; and how many rows there were."""
    out = []
    rows = db.execute(sql, args).fetchall()
    crypto_ok = CRYPTO.search(ask.question) is not None
    for (pid, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine, fame, wiki) in rows:
        kname, kpath = kinds[kind]
        if ask.group == "money" and not crypto_ok and CRYPTO.search(name):
            continue  # a bitcoin ATM is not where to withdraw cash
        p = Place(pid, name, kname, kpath, alt, diet, src, conf, chain, lat5 / 1e5, lon5 / 1e5, street, locality,
                  phone, website, hours, cuisine, fame, wiki)
        p.km = distance_km(lat, lon, p.lat, p.lon)
        if p.km > radius_km:
            continue
        tier = 0
        if ask.diet:
            t = diet_tier(p, ask.diet)
            if t is None:
                continue
            tier = t
        p.sub = bool(ask.sub) and is_sub(p, ask.sub, sub_rx)
        p.tier = tier
        out.append(p)
    return out, len(rows)


def sub_word(sub):
    return sub.replace("_restaurant", "").replace("_shop", "").replace("_", " ")


def is_sub(p, sub, rx=None):
    """The place is of the one kind asked for: its category (or one above it, or an alternate),
    OpenStreetMap's cuisine, or its name says so. [rx]: the word's pattern, compiled once."""
    if sub in p.kinds or sub in (p.alt or "").split(","):
        return True
    word = sub_word(sub)
    if p.cuisine and word in p.cuisine.lower().replace("_", " "):
        return True
    rx = rx or re.compile(rf"\b{re.escape(word)}")
    return rx.search(p.name.lower()) is not None


def score(p, radius_km, price=None, late=False):
    s = p.conf / 100.0 + fame_bonus(p.fame)
    if late and p.hours and LATE_HOURS.search(p.hours):
        s += 1.0
    if p.src & SRC_GUIDE:
        s += 1.5
        if price and any(g[2] and g[2].lower().startswith(price.lower()[:4]) for g in p.guide):
            s += 1.0
    if p.src & SRC_OSM and p.src & SRC_OVERTURE:
        s += 0.5
    if p.website:
        s += 0.2
    if p.chain:
        s -= 1.0
    s -= 0.6 * min(p.km / max(radius_km, 1.0), 1.0)
    return s


def reasons(p, ask):
    why = []
    if ask.diet:
        lab = DIET_LABEL.get((ask.diet, p.tier))
        if lab is None:
            lab = ask.diet.replace("_", "-") if p.tier == 0 else ask.diet.replace("_", "-") + " options"
        # a vegan place found for a vegetarian question says it is vegan
        if ask.diet == "vegetarian" and p.tier == 0 and diet_tier(p, "vegan") == 0:
            lab = "vegan"
        why.append(lab)
    if p.guide:
        article, section, gtier, listing = p.guide[0]
        why.append(f"in the Wikivoyage guide ({article}" + (f", {gtier}" if gtier else "") + ")")
    return why


def kind_label(kind):
    return kind.replace("_", " ")


def kind_bits(p):
    """The place's category and why it matches: "vegan restaurant" rather than "vegetarian
    restaurant; vegan" for a vegetarian-listed place that OpenStreetMap marks vegan-only."""
    why = [w for w in p.why if not w.startswith("in the Wikivoyage")]
    if why and why[0] in ("vegan", "vegetarian") and p.kind in ("vegan_restaurant", "vegetarian_restaurant"):
        return [why[0] + " restaurant"] + why[1:]
    return [kind_label(p.kind)] + why


def describe(p, n=None, guide_text=None, origin="the centre", brief=False, hours=True, wiki_text=None):
    """One place as the model and the list show it. [brief]: the model's line, without the street
    (the app shows it) and with the cuisine; [hours]: with the opening hours (the model gets them
    when asked about); [wiki_text]: the start of the place's own Wikipedia article."""
    head = f"[{n}] " if n is not None else ""
    bits = kind_bits(p)
    if p.street and not brief:
        bits.append(p.street + (f", {p.locality}" if p.locality else ""))
    cuisines = [c.strip().replace("_", " ") for c in (p.cuisine or "").split(";") if c.strip()]
    if brief and cuisines:
        bits.append("cuisine: " + ", ".join(cuisines))
    bits.append(f"{p.km:.1f} km from {origin}")
    if p.hours and hours:
        bits.append(f"hours: {p.hours}")
    line = f"{head}{p.name}: " + "; ".join(bits) + "."
    if guide_text:
        line += f" The travel guide says: {guide_text}"
    if wiki_text:
        line += f" Wikipedia: {wiki_text}"
    return line


def lead_text(text, n):
    """The start of a Wikipedia article for the model: after its "# Title" line, without a line of
    coordinates and without parentheses (pronunciations, names in other scripts), whole sentences
    up to [n] characters (at least the first, clipped)."""
    m = re.search(r"^# [^\n]*\n+", text, re.M)
    lead = text[m.end():] if m else text
    lines = [l for l in lead.split("\n") if l.strip()]
    while lines and "\u00b0" in lines[0] and len(lines[0]) < 80:
        lines.pop(0)
    para = lines[0] if lines else ""
    for _ in range(2):
        para = re.sub(r"\s*\([^()]*\)", "", para)
    para = re.sub(r"\s+", " ", para).strip()
    out = ""
    for sent in re.split(r"(?<=[a-z0-9)\]])\.\s+", para):
        if not sent.strip(". "):
            continue
        sent = sent.rstrip(".") + "."
        if out and len(out) + 1 + len(sent) > n:
            break
        out = (out + " " + sent).strip()
    return _clip(out, n) if out else None


def guide_text(voyage, p):
    """The listing's own line in the Wikivoyage article, without its name."""
    if voyage is None or not p.guide:
        return None
    article, section, gtier, listing = p.guide[0]
    text = voyage(article)
    if not text:
        return None
    for line in text.split("\n"):
        if line.startswith("- - " + listing):
            rest = line[4 + len(listing):].lstrip(":. ").strip()
            return rest[:400] or None
    return None


# ---- the whole lookup --------------------------------------------------------------------------

HERE_RADII = (2.0, 5.0, 10.0)  # km around the phone, widened until MIN_HERE places match
MIN_HERE = 5
CITY_WIDEN = (1.0, 2.0, 4.0)   # the city's radius, widened when nothing matches
MAX_RADIUS_KM = 50.0


@dataclass
class Lookup:
    label: str          # where: "Lisbon, Portugal" or "your position"
    radius_km: float
    total: int          # places that matched, before the list was cut
    places: list
    capped: bool        # only the best CANDIDATES were read: "total" is a floor
    origin: str         # distances are from "the centre" or "you"
    city: City = None


def lookup(db, ask, here=None):
    """The places for [ask]: around the city it names, or around [here] (lat, lon) for "near me".
    None when the question names no city this database knows, or asks "near me" without [here]."""
    if ask.here:
        if here is None:
            return None
        for r in HERE_RADII:
            places, total, capped = find(db, ask, here[0], here[1], r)
            if total >= MIN_HERE:
                break
        return Lookup("your position", r, total, places, capped, "you")
    city = locate(db, ask)
    if city is None:
        return None
    # nothing of the kind in the city: the nearest there is, up to MAX_RADIUS_KM away
    for m in CITY_WIDEN:
        r = min(city.radius_km * m, MAX_RADIUS_KM)
        places, total, capped = find(db, ask, city.lat, city.lon, r)
        if total > 0:
            break
    return Lookup(city.label(), r, total, places, capped, "the centre", city)


# ---- what the model reads -------------------------------------------------------------------

PLACES_SYSTEM = (
    "You are an offline travel assistant. The question comes with a numbered list of places from "
    "offline map data (OpenStreetMap and Overture Maps), the Wikivoyage travel guide and Wikipedia, "
    "best matches first; the app shows the list, with each place's address, distance and hours, next "
    "to your answer. If the question asks for more than places (costs, tipping, safety, which one "
    "suits), answer that first in a sentence or two, from what you know and from the list. Then "
    "recommend the three to five places that best answer the question, one line each, starting with "
    "its number and name like \"[2] Name:\", then what kind of place it is and what the list says about "
    "it that matters (the travel guide's and Wikipedia's words when quoted, hours when asked about). Say "
    "nothing about a place that the list does not say: no praise, popularity, ratings, atmosphere, "
    "dishes, prices or neighbourhoods, unless it is a famous place you know well. Never describe the "
    "list itself or what it lacks. Unless the question asks for more than places, the answer's first "
    "characters are the first place's number, like \"[1]\". No closing remarks, no LaTeX, no visible "
    "deliberation."
)
MODEL_PLACES = 6     # places the model reads (the list shows up to find()'s limit)
GUIDE_CHARS = 180    # of each travel-guide listing
WIKI_CHARS = 200     # of the start of a place's own Wikipedia article


def what_text(ask):
    what = GROUP_LABEL[ask.group]
    if ask.diet:
        what = ask.diet.replace("_", "-") + " " + what
    return what


def where_text(total, radius_km, label, ask, capped=False):
    return f"{total}{'+' if capped else ''} {what_text(ask)} within {radius_km:.0f} km of {label}"


def places_user(question, where, lines):
    """The user message of the places answer."""
    return "Places (" + where + "):\n\n" + "\n".join(lines) + "\n\nQuestion: " + question


def asks_hours(ask):
    """The question is about when places are open: the model's lines then give their hours."""
    return ask.late or HOURS.search(ask.question.lower()) is not None


def context_lines(places, voyage, origin, hours=True, wiki=None):
    """The model's lines: the first MODEL_PLACES places, with the travel guide's words and the start
    of each one's own Wikipedia article ([voyage], [wiki]: title -> text readers)."""
    out = []
    for i, p in enumerate(places[:MODEL_PLACES], 1):
        wt = wiki(p.wiki) if wiki and p.wiki else None
        out.append(describe(p, i, _clip(guide_text(voyage, p), GUIDE_CHARS), origin, brief=True, hours=hours,
                            wiki_text=lead_text(wt, WIKI_CHARS) if wt else None))
    return out


def _clip(text, n):
    if text is None or len(text) <= n:
        return text
    cut = text[:n]
    sp = cut.rfind(" ")
    return (cut[:sp] if sp > n // 2 else cut) + "…"


def voyage_reader(path):
    import zstandard
    c = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    d = zstandard.ZstdDecompressor()

    def text(title):
        r = c.execute("select block_id, off, len from articles where title = ?", (title,)).fetchone()
        if not r:
            return None
        raw = d.decompress(c.execute("select zdata from blocks where id = ?", (r[0],)).fetchone()[0])
        return raw[r[1]:r[1] + r[2]].decode("utf-8")  # a byte range in the block
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("question")
    ap.add_argument("--voyage")
    ap.add_argument("--wiki", help="wiki.db: the start of each place's own Wikipedia article")
    ap.add_argument("--here", help="lat,lon for 'near me'")
    a = ap.parse_args()
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    cats = [r[0] for r in db.execute("select name from kinds")]
    ask = parse(a.question, cats)
    print("ask:", ask)
    if ask is None:
        return
    here = tuple(map(float, a.here.split(","))) if a.here else None
    lk = lookup(db, ask, here)
    if lk is None:
        print("no place found")
        return
    print("city:", lk.city)
    voyage = voyage_reader(a.voyage) if a.voyage else None
    wiki = voyage_reader(a.wiki) if a.wiki else None
    print(places_user(a.question, where_text(lk.total, lk.radius_km, lk.label, ask, lk.capped),
                      context_lines(lk.places, voyage, lk.origin, asks_hours(ask), wiki)))


if __name__ == "__main__":
    main()
