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

3/ The ranked list is on screen in about 0.1 s on a Pixel 8 Pro; the model then recommends from
it, using only what the list says (the travel guide's listing, the place's own Wikipedia article
when it has one). Chinese works too. Tested on 14 cities (Lisbon, Berlin, Chiang Mai,
Tbilisi, Mexico City, Cape Town, Seoul...) and ramen/coffee/hostels/halal/kosher/tapas questions.

4/ Which phone were you on? Your source check ran at 1.1 tok/s, a quarter of what the Pixel 8
Pro does; I want to find out why. github.com/Phineas1500/AndroidLM

Release: https://github.com/Phineas1500/AndroidLM/releases/tag/v1.1.0 (APK + INSTALL.md; places.db at
huggingface.co/datasets/rammingaway/androidlm-places). The speed line matches the 24-question eval
(list after a median 0.15 s, recommendations done after 52 s). The demo clip was recorded with an
earlier 1.1 build; the flow (list first, then the model's picks) is the same.
