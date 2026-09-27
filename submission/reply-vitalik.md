# Reply to Vitalik's post (draft; the user posts)

Context: Vitalik tried the bounty's apps (AndroidLM among them, the Mozi screenshot) and wrote
that they were weakest at specialized travel questions; his test is "Tell me the best vegan
restaurants in [city I am currently in]", which none of them did well.

## Option A: one post with a clip

> Thanks for trying AndroidLM! Your vegan-restaurants test was exactly what it couldn't do:
> Wikipedia doesn't list restaurants, so it guessed. v1.1 adds an offline places database
> (21M places: OpenStreetMap + Overture + Wikivoyage). "Best vegan restaurants in Lisbon":
> real places in 0.1s, airplane mode, GPS works for "near me" too.
> [clip]

## Option B: a short thread

1/ Thanks for trying AndroidLM, @VitalikButerin. Your eval ("best vegan restaurants in [city]")
was the gap: Wikipedia has no restaurant lists, so the old app guessed (for Lisbon it named
places that don't exist). Fixed in v1.1, fully offline: [clip]

2/ How: a 2.9GB database of 21M places worldwide: to eat, drink and stay, plus pharmacies,
ATMs, hospitals, supermarkets, stations. Overture Maps places + OpenStreetMap diet tags
(vegan/vegetarian/halal/kosher/gluten-free, opening hours) + GeoNames cities + Wikivoyage's own
recommendations matched to them. "Near me" uses GPS, no network.

3/ The ranked list is the answer, in about 0.1 s on a Pixel 8 Pro, without even loading the
model. The model only writes when the question asks for more ("...and how much should I tip?")
or is in another language (Chinese works). Tested on 14 cities (Lisbon, Berlin, Chiang Mai,
Tbilisi, Mexico City, Cape Town, Seoul...) and ramen/coffee/hostels/halal/kosher/tapas questions.

4/ Which phone were you on? Your source check ran at 1.1 tok/s, a quarter of what the Pixel 8
Pro does; I want to find out why. github.com/Phineas1500/AndroidLM

Numbers to check before posting: the v1.1 release link, the clip, and the speed line (from
notes/2026-09-27-places.md).
