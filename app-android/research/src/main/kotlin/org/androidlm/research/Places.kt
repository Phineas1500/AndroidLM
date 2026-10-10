package org.androidlm.research

import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/*
 * Port of scripts/places.py (and the constants it shares with scripts/build_places.py): questions
 * like "the best vegan restaurants in Lisbon", answered from places.db, a database of places to
 * eat, drink and stay built from Overture Maps, OpenStreetMap, GeoNames and Wikivoyage. Keep the
 * two in step; PlacesGoldenTest compares them.
 */

/** What a places question asks for (places.py `PlaceAsk`). */
data class PlaceAsk(
    /** eat / cafe / drink / sweet / stay */
    val group: String,
    /** vegan / vegetarian / gluten_free / halal / kosher */
    val diet: String? = null,
    /** One category within the group, e.g. ramen_restaurant or hostel. */
    val sub: String? = null,
    /** "Budget" or "Splurge" (the travel guide's price tiers). */
    val price: String? = null,
    /** "near me": the phone's position. */
    val here: Boolean = false,
    /** The question says "restaurant(s)": restaurants rank above cafes and shops. */
    val restaurant: Boolean = false,
    val question: String = "",
    /** The question asks about late opening hours. */
    val late: Boolean = false,
)

/** A GeoNames city (places.py `City`). */
data class City(
    val id: Long,
    val name: String,
    val country: String,
    val admin1: String?,
    val lat: Double,
    val lon: Double,
    val population: Long,
    /** 1 for a national capital (GeoNames PPLC). */
    val capital: Int = 0,
    val countryName: String = "",
) {
    val radiusKm: Double get() = PlacesText.cityRadiusKm(population)

    fun label(): String {
        val parts = mutableListOf(name)
        if (!admin1.isNullOrEmpty() && country in LABEL_REGION && admin1 != name) parts.add(admin1)
        parts.add(countryName.ifEmpty { country })
        return parts.joinToString(", ")
    }

    private companion object {
        val LABEL_REGION = setOf("US", "CA", "AU", "IN", "BR", "MX")
    }
}

/** One travel-guide listing matched to a place: (article, section, tier, listing). */
data class GuideListing(val article: String, val section: String, val tier: String?, val listing: String)

/** A place (places.py `Place`); [kinds] is its category with the categories above it. */
class Place(
    val id: Long,
    val name: String,
    val kind: String,
    val kinds: List<String>,
    val alt: String?,
    val diet: Int,
    val src: Int,
    val conf: Int,
    val chain: Int,
    val lat: Double,
    val lon: Double,
    val street: String?,
    val locality: String?,
    val phone: String?,
    val website: String?,
    val hours: String?,
    val cuisine: String?,
    /** Monthly views of the place's own Wikipedia article. */
    val fame: Int = 0,
    /** That article's title. */
    val wiki: String? = null,
) {
    var km = 0.0
    var tier = 9
    var score = 0.0
    var sub = false
    val guide = ArrayList<GuideListing>()
    var why: List<String> = emptyList()
}

/** places.py `Lookup`: the places that answer a question, and where they were looked for. */
data class PlacesLookup(
    val label: String,
    val radiusKm: Double,
    val total: Int,
    val places: List<Place>,
    /** Only the best [PlacesText.CANDIDATES] were read: [total] is a floor. */
    val capped: Boolean,
    val origin: String,
    val city: City? = null,
)

/** places.py `find`'s result: the list, how many matched, and whether the reading was capped. */
data class Found(val places: List<Place>, val total: Int, val capped: Boolean)

/** The text rules of places.py: parsing the question, and the constants of build_places.py. */
object PlacesText {
    const val CELL_COLS = 7200

    // diet bits (OpenStreetMap diet:* tags), build_places.py
    const val VEGAN_ONLY = 1
    const val VEGAN_YES = 2
    const val VEGAN_LIMITED = 4
    const val VEGAN_NO = 8
    const val VEGETARIAN_ONLY = 16
    const val VEGETARIAN_YES = 32
    const val VEGETARIAN_LIMITED = 64
    const val GLUTEN_FREE = 128
    const val HALAL = 256
    const val KOSHER = 512

    // source bits
    const val SRC_OVERTURE = 1
    const val SRC_OSM = 2
    const val SRC_GUIDE = 4

    // Python's \w and \b are Unicode-aware. On the JVM that takes (?U); Android's regex engine
    // (ICU) is Unicode-aware already and rejects the flag.
    private val U = if (System.getProperty("java.vm.name") == "Dalvik") "" else "(?U)"

    private val FOLD = mapOf(
        'ß' to "ss", 'ø' to "o", 'Ø' to "o", 'æ' to "ae", 'Æ' to "ae", 'œ' to "oe", 'Œ' to "oe",
        'ł' to "l", 'Ł' to "l", 'đ' to "d", 'Đ' to "d", 'ı' to "i", 'þ' to "th", 'ð' to "d",
    )
    private val NOT_KEY = Regex("[^a-z0-9]+")

    /** build_places.py `norm_key`: lower-case ASCII words, accents dropped, anything else a space. */
    fun normKey(s: String): String {
        val folded = buildString(s.length) { for (c in s) FOLD[c]?.let { append(it) } ?: append(c) }
        val nfkd = Normalizer.normalize(folded, Normalizer.Form.NFKD)
        val bare = buildString(nfkd.length) {
            var i = 0
            while (i < nfkd.length) {
                val cp = nfkd.codePointAt(i)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        return NOT_KEY.replace(bare.lowercase(Locale.ROOT), " ").trim()
    }

    /** build_places.py `city_radius_km`. */
    fun cityRadiusKm(pop: Long): Double =
        max(3.0, min(25.0, 2.0 + 4.0 * log10(max(pop, 1000L).toDouble() / 1000.0)))

    val GROUPS: Map<String, List<String>> = mapOf(
        "eat" to listOf("restaurant", "casual_eatery", "food_and_drink", "non_alcoholic_beverage_venue"),
        "cafe" to listOf("cafe", "coffee_shop", "coffee_roastery", "non_alcoholic_beverage_venue", "tea_room", "bubble_tea_shop", "breakfast_and_brunch_restaurant"),
        "drink" to listOf("alcoholic_beverage_venue"),
        "sweet" to listOf("bakery", "patisserie", "dessert_shop", "ice_cream_shop", "donut_shop", "cupcake_shop", "candy_store", "chocolatier", "frozen_yoghurt_shop", "gelato"),
        "stay" to listOf("lodging"),
        "pharmacy" to listOf("pharmacy_and_drug_store"),
        "health" to listOf("hospital", "emergency_or_urgent_care_facility", "primary_care_or_general_clinic", "dental_clinic"),
        "money" to listOf("atm", "bank_or_credit_union", "currency_exchange"),
        "phone" to listOf("mobile_phone_store", "telecommunications_company"),
        "shop" to listOf("grocery_store", "convenience_store", "shopping_mall", "market"),
        "laundry" to listOf("laundromat", "laundry_service"),
        "coworking" to listOf("coworking_space", "shared_office_space"),
        "post" to listOf("post_office"),
        "police" to listOf("police_station"),
        "embassy" to listOf("embassy"),
        "sights" to listOf("museum", "art_gallery", "zoo", "aquarium", "amusement_park", "theatre_venue", "monument", "historic_site", "castle", "palace", "fort", "religious_landmark", "botanical_garden", "park", "national_park", "beach", "public_plaza", "hiking_trail"),
        "transport" to listOf("train_station", "bus_station", "metro_station", "airport", "car_rental_service", "bike_rental", "scooter_rental", "ferry_service"),
        "fitness" to listOf("gym"),
        // shops by kind and places to go for fun (build_places.py MORE)
        "electronics" to listOf("electronics_store", "mobile_phone_repair"),
        "books" to listOf("bookstore"),
        "games" to listOf("toys_and_games_store", "video_game_store"),
        "outdoor" to listOf("sporting_goods_store", "bike_repair_maintenance"),
        "hardware" to listOf("hardware_store"),
        "clothes" to listOf("clothing_store", "shoe_store", "department_store", "second_hand_store"),
        "gifts" to listOf("souvenir_store", "gift_shop"),
        "flowers" to listOf("florist"),
        "jewelry" to listOf("jewelry_store"),
        "eyewear" to listOf("eyewear_store"),
        "luggage" to listOf("luggage_store"),
        "liquor" to listOf("liquor_store"),
        "tobacco" to listOf("tobacco_shop", "smoke_and_vape_store"),
        "music_shop" to listOf("musical_instrument_store", "music_and_dvd_store", "vinyl_record_store"),
        "pets" to listOf("pet_store"),
        "arcade" to listOf("arcade"),
        "bowling" to listOf("bowling_alley"),
        "escape_room" to listOf("escape_room"),
        "laser_tag" to listOf("laser_tag"),
        "karting" to listOf("go_kart_track"),
        "trampoline" to listOf("trampoline_park"),
        "skating" to listOf("ice_skating_rink"),
        "climbing" to listOf("rock_climbing_spot", "rock_climbing_gym"),
        "cinema" to listOf("movie_theater"),
        "casino" to listOf("casino"),
        "live_music" to listOf("music_venue"),
        "comedy" to listOf("comedy_club"),
    )
    val GROUP_LABEL: Map<String, String> = mapOf(
        "eat" to "places to eat",
        "cafe" to "cafes",
        "drink" to "places to drink",
        "sweet" to "bakeries and sweet shops",
        "stay" to "places to stay",
        "pharmacy" to "pharmacies",
        "health" to "hospitals and clinics",
        "money" to "ATMs, banks and money changers",
        "phone" to "phone shops",
        "shop" to "shops and markets",
        "laundry" to "laundries",
        "coworking" to "coworking spaces",
        "post" to "post offices",
        "police" to "police stations",
        "embassy" to "embassies",
        "sights" to "sights",
        "transport" to "stations and transport",
        "fitness" to "gyms",
        "electronics" to "electronics and phone repair shops",
        "books" to "bookshops",
        "games" to "video game and toy shops",
        "outdoor" to "sports, outdoor and bike shops",
        "hardware" to "hardware stores",
        "clothes" to "clothes and shoe shops",
        "gifts" to "souvenir and gift shops",
        "flowers" to "florists",
        "jewelry" to "jewellers",
        "eyewear" to "opticians",
        "luggage" to "luggage shops",
        "liquor" to "liquor stores",
        "tobacco" to "tobacco and vape shops",
        "music_shop" to "music shops",
        "pets" to "pet shops",
        "arcade" to "arcades",
        "bowling" to "bowling alleys",
        "escape_room" to "escape rooms",
        "laser_tag" to "laser tag venues",
        "karting" to "go-kart tracks",
        "trampoline" to "trampoline parks",
        "skating" to "ice rinks",
        "climbing" to "climbing gyms and spots",
        "cinema" to "cinemas",
        "casino" to "casinos",
        "live_music" to "live music venues",
        "comedy" to "comedy clubs",
    )

    private val KIND_WORDS = listOf(
        "places to eat" to "eat",
        "place to eat" to "eat",
        "where to eat" to "eat",
        "food" to "eat",
        "restaurant" to "eat",
        "eatery" to "eat",
        "eateries" to "eat",
        "dinner" to "eat",
        "lunch" to "eat",
        "meal" to "eat",
        "dining" to "eat",
        "eat" to "eat",
        "brunch" to "cafe",
        "breakfast" to "cafe",
        "cafe" to "cafe",
        "café" to "cafe",
        "coffee" to "cafe",
        "tea house" to "cafe",
        "bar" to "drink",
        "pub" to "drink",
        "beer" to "drink",
        "brewery" to "drink",
        "breweries" to "drink",
        "cocktail" to "drink",
        "wine" to "drink",
        "nightlife" to "drink",
        "drinks" to "drink",
        "bakery" to "sweet",
        "bakeries" to "sweet",
        "pastry" to "sweet",
        "pastries" to "sweet",
        "dessert" to "sweet",
        "ice cream" to "sweet",
        "gelato" to "sweet",
        "donut" to "sweet",
        "hotel" to "stay",
        "hostel" to "stay",
        "guesthouse" to "stay",
        "guest house" to "stay",
        "accommodation" to "stay",
        "place to stay" to "stay",
        "places to stay" to "stay",
        "where to stay" to "stay",
        "where should i stay" to "stay",
        "where can i stay" to "stay",
        "where to sleep" to "stay",
        "bed and breakfast" to "stay",
        "b&b" to "stay",
        "pharmacy" to "pharmacy",
        "pharmacies" to "pharmacy",
        "chemist" to "pharmacy",
        "drugstore" to "pharmacy",
        "hospital" to "health",
        "clinic" to "health",
        "doctor" to "health",
        "urgent care" to "health",
        "emergency room" to "health",
        "dentist" to "health",
        "atm" to "money",
        "cash machine" to "money",
        "withdraw cash" to "money",
        "withdraw money" to "money",
        "bank" to "money",
        "currency exchange" to "money",
        "exchange money" to "money",
        "change money" to "money",
        "money changer" to "money",
        "phone shop" to "phone",
        "phone store" to "phone",
        "mobile phone shop" to "phone",
        "supermarket" to "shop",
        "grocery" to "shop",
        "groceries" to "shop",
        "convenience store" to "shop",
        "shopping mall" to "shop",
        "mall" to "shop",
        "market" to "shop",
        "laundry" to "laundry",
        "laundromat" to "laundry",
        "coworking" to "coworking",
        "co-working" to "coworking",
        "post office" to "post",
        "police station" to "police",
        "embassy" to "embassy",
        "embassies" to "embassy",
        "consulate" to "embassy",
        "museum" to "sights",
        "gallery" to "sights",
        "galleries" to "sights",
        "zoo" to "sights",
        "aquarium" to "sights",
        "castle" to "sights",
        "palace" to "sights",
        "park" to "sights",
        "beach" to "sights",
        "beaches" to "sights",
        "temple" to "sights",
        "church" to "sights",
        "churches" to "sights",
        "monument" to "sights",
        "hiking" to "sights",
        "botanical garden" to "sights",
        "train station" to "transport",
        "bus station" to "transport",
        "metro station" to "transport",
        "subway station" to "transport",
        "airport" to "transport",
        "car rental" to "transport",
        "rent a car" to "transport",
        "bike rental" to "transport",
        "rent a bike" to "transport",
        "scooter rental" to "transport",
        "ferry" to "transport",
        "gym" to "fitness",
        // shops by kind (at the same place the longer phrase wins: "wine shop" is not a bar, "camping
        // gear" not a campsite, "pet food" not a restaurant), places to go for fun ("shopping arcade" is a shop)
        "electronics" to "electronics",
        "electronic shop" to "electronics",
        "electronics store" to "electronics",
        "computer shop" to "electronics",
        "computer store" to "electronics",
        "laptop" to "electronics",
        "camera shop" to "electronics",
        "camera store" to "electronics",
        "phone repair" to "electronics",
        "mobile phone repair" to "electronics",
        "repair my phone" to "electronics",
        "fix my phone" to "electronics",
        "charger" to "electronics",
        "bookshop" to "books",
        "bookstore" to "books",
        "book shop" to "books",
        "book store" to "books",
        "bookseller" to "books",
        "buy books" to "books",
        "comic shop" to "books",
        "comic book" to "books",
        "comics" to "books",
        "video game" to "games",
        "game shop" to "games",
        "game store" to "games",
        "games shop" to "games",
        "games store" to "games",
        "gaming shop" to "games",
        "gaming store" to "games",
        "board game" to "games",
        "toy" to "games",
        "toyshop" to "games",
        "sporting goods" to "outdoor",
        "sports shop" to "outdoor",
        "sports store" to "outdoor",
        "sport shop" to "outdoor",
        "outdoor gear" to "outdoor",
        "outdoor shop" to "outdoor",
        "outdoor store" to "outdoor",
        "camping gear" to "outdoor",
        "camping shop" to "outdoor",
        "camping store" to "outdoor",
        "hiking gear" to "outdoor",
        "hiking boots" to "outdoor",
        "climbing gear" to "outdoor",
        "ski shop" to "outdoor",
        "surf shop" to "outdoor",
        "bike shop" to "outdoor",
        "bicycle shop" to "outdoor",
        "bike store" to "outdoor",
        "bicycle store" to "outdoor",
        "bike repair" to "outdoor",
        "bicycle repair" to "outdoor",
        "fix my bike" to "outdoor",
        "hardware store" to "hardware",
        "hardware shop" to "hardware",
        "diy store" to "hardware",
        "tool shop" to "hardware",
        "home improvement store" to "hardware",
        "clothes" to "clothes",
        "clothing" to "clothes",
        "shoe" to "clothes",
        "department store" to "clothes",
        "thrift store" to "clothes",
        "thrift shop" to "clothes",
        "second hand shop" to "clothes",
        "second-hand shop" to "clothes",
        "second hand store" to "clothes",
        "charity shop" to "clothes",
        "vintage shop" to "clothes",
        "vintage clothes" to "clothes",
        "boutique" to "clothes",
        "souvenir" to "gifts",
        "gift shop" to "gifts",
        "gift store" to "gifts",
        "gifts" to "gifts",
        "florist" to "flowers",
        "flower shop" to "flowers",
        "buy flowers" to "flowers",
        "jewelry" to "jewelry",
        "jewellery" to "jewelry",
        "jeweler" to "jewelry",
        "jeweller" to "jewelry",
        "optician" to "eyewear",
        "optometrist" to "eyewear",
        "eyeglasses" to "eyewear",
        "glasses" to "eyewear",
        "sunglasses" to "eyewear",
        "contact lenses" to "eyewear",
        "eyewear" to "eyewear",
        "luggage" to "luggage",
        "suitcase" to "luggage",
        "liquor store" to "liquor",
        "liquor shop" to "liquor",
        "liquor" to "liquor",
        "bottle shop" to "liquor",
        "off licence" to "liquor",
        "off-licence" to "liquor",
        "off license" to "liquor",
        "wine shop" to "liquor",
        "wine store" to "liquor",
        "buy alcohol" to "liquor",
        "buy wine" to "liquor",
        "buy beer" to "liquor",
        "tobacco" to "tobacco",
        "tobacconist" to "tobacco",
        "cigarette" to "tobacco",
        "cigar" to "tobacco",
        "vape" to "tobacco",
        "smoke shop" to "tobacco",
        "music shop" to "music_shop",
        "music store" to "music_shop",
        "record store" to "music_shop",
        "record shop" to "music_shop",
        "vinyl" to "music_shop",
        "musical instrument" to "music_shop",
        "guitar shop" to "music_shop",
        "instrument shop" to "music_shop",
        "pet shop" to "pets",
        "pet store" to "pets",
        "pet supplies" to "pets",
        "pet food" to "pets",
        "dog food" to "pets",
        "cat food" to "pets",
        "arcade" to "arcade",
        "game arcade" to "arcade",
        "amusement arcade" to "arcade",
        "pinball" to "arcade",
        "shopping arcade" to "shop",
        "bowling" to "bowling",
        "escape room" to "escape_room",
        "escape game" to "escape_room",
        "laser tag" to "laser_tag",
        "go kart" to "karting",
        "go-kart" to "karting",
        "karting" to "karting",
        "trampoline" to "trampoline",
        "ice rink" to "skating",
        "ice skating" to "skating",
        "skating rink" to "skating",
        "climbing gym" to "climbing",
        "climbing wall" to "climbing",
        "bouldering" to "climbing",
        "rock climbing" to "climbing",
        "climbing" to "climbing",
        "cinema" to "cinema",
        "movie theater" to "cinema",
        "movie theatre" to "cinema",
        "watch a movie" to "cinema",
        "watch a film" to "cinema",
        "casino" to "casino",
        "live music" to "live_music",
        "music venue" to "live_music",
        "concert venue" to "live_music",
        "comedy club" to "comedy",
        "stand-up comedy" to "comedy",
        "stand up comedy" to "comedy",
        "comedy show" to "comedy",
    )
    private val DIET_WORDS = listOf(
        "plant based" to "vegan", "plant-based" to "vegan", "vegan" to "vegan",
        "vegetarian" to "vegetarian", "veggie" to "vegetarian",
        "gluten free" to "gluten_free", "gluten-free" to "gluten_free", "celiac" to "gluten_free",
        "coeliac" to "gluten_free", "halal" to "halal", "kosher" to "kosher",
    )
    private val SUB_WORDS = listOf(
        "hostel" to "hostel",
        "bed and breakfast" to "bed_and_breakfast",
        "b&b" to "bed_and_breakfast",
        "guest house" to "guest_house",
        "guesthouse" to "guest_house",
        "campsite" to "campground",
        "camping" to "campground",
        "hotel" to "hotel",
        "bakery" to "bakery",
        "bakeries" to "bakery",
        "ice cream" to "ice_cream_shop",
        "gelato" to "ice_cream_shop",
        "donut" to "donut_shop",
        "dessert" to "dessert_shop",
        "brewery" to "brewery",
        "breweries" to "brewery",
        "pub" to "pub",
        "brunch" to "breakfast_and_brunch_restaurant",
        "breakfast" to "breakfast_and_brunch_restaurant",
        "coffee" to "coffee_shop",
        "steak" to "steakhouse",
        "tapas" to "tapas_bar",
        "dim sum" to "dim_sum_restaurant",
        "noodle" to "noodles_restaurant",
        "taco" to "taco_restaurant",
        "burger" to "burger_restaurant",
        "dumpling" to "dumpling_restaurant",
        "curry" to "indian_restaurant",
        "street food" to "food_stand",
        "food truck" to "food_truck_stand",
        "juice" to "smoothie_juice_bar",
        "bubble tea" to "bubble_tea_shop",
        "wine bar" to "wine_bar",
        "cocktail bar" to "cocktail_bar",
        "hospital" to "hospital",
        "dentist" to "dental_clinic",
        "urgent care" to "emergency_or_urgent_care_facility",
        "emergency room" to "emergency_or_urgent_care_facility",
        "clinic" to "primary_care_or_general_clinic",
        "doctor" to "primary_care_or_general_clinic",
        "atm" to "atm",
        "cash machine" to "atm",
        "withdraw cash" to "atm",
        "withdraw money" to "atm",
        "bank" to "bank_or_credit_union",
        "currency exchange" to "currency_exchange",
        "exchange money" to "currency_exchange",
        "change money" to "currency_exchange",
        "money changer" to "currency_exchange",
        "supermarket" to "grocery_store",
        "grocery" to "grocery_store",
        "groceries" to "grocery_store",
        "convenience store" to "convenience_store",
        "shopping mall" to "shopping_mall",
        "mall" to "shopping_mall",
        "market" to "market",
        "museum" to "museum",
        "gallery" to "art_gallery",
        "galleries" to "art_gallery",
        "zoo" to "zoo",
        "aquarium" to "aquarium",
        "castle" to "castle",
        "palace" to "palace",
        "park" to "park",
        "beach" to "beach",
        "beaches" to "beach",
        "temple" to "religious_landmark",
        "church" to "religious_landmark",
        "churches" to "religious_landmark",
        "monument" to "monument",
        "hiking" to "hiking_trail",
        "botanical garden" to "botanical_garden",
        "train station" to "train_station",
        "bus station" to "bus_station",
        "metro station" to "metro_station",
        "subway station" to "metro_station",
        "airport" to "airport",
        "car rental" to "car_rental_service",
        "rent a car" to "car_rental_service",
        "bike rental" to "bike_rental",
        "rent a bike" to "bike_rental",
        "scooter rental" to "scooter_rental",
        "ferry" to "ferry_service",
        "computer shop" to "computer_store",
        "computer store" to "computer_store",
        "laptop" to "computer_store",
        "camera shop" to "camera_and_photography_store",
        "camera store" to "camera_and_photography_store",
        "phone repair" to "mobile_phone_repair",
        "mobile phone repair" to "mobile_phone_repair",
        "repair my phone" to "mobile_phone_repair",
        "fix my phone" to "mobile_phone_repair",
        "comic shop" to "comic_books_store",
        "comic book" to "comic_books_store",
        "comics" to "comic_books_store",
        "video game" to "video_game_store",
        "game shop" to "video_game_store",
        "game store" to "video_game_store",
        "games shop" to "video_game_store",
        "games store" to "video_game_store",
        "gaming shop" to "video_game_store",
        "gaming store" to "video_game_store",
        "board game" to "tabletop_games_store",
        "toy" to "toy_store",
        "toyshop" to "toy_store",
        "outdoor gear" to "outdoor_store",
        "outdoor shop" to "outdoor_store",
        "outdoor store" to "outdoor_store",
        "camping gear" to "outdoor_store",
        "camping shop" to "outdoor_store",
        "camping store" to "outdoor_store",
        "hiking gear" to "outdoor_store",
        "hiking boots" to "outdoor_store",
        "climbing gear" to "outdoor_store",
        "bike shop" to "bike_store",
        "bicycle shop" to "bike_store",
        "bike store" to "bike_store",
        "bicycle store" to "bike_store",
        "bike repair" to "bike_repair_maintenance",
        "bicycle repair" to "bike_repair_maintenance",
        "fix my bike" to "bike_repair_maintenance",
        "shoe" to "shoe_store",
        "department store" to "department_store",
        "thrift store" to "second_hand_store",
        "thrift shop" to "second_hand_store",
        "second hand shop" to "second_hand_store",
        "second-hand shop" to "second_hand_store",
        "second hand store" to "second_hand_store",
        "charity shop" to "second_hand_store",
        "vintage shop" to "second_hand_store",
        "vintage clothes" to "second_hand_store",
        "souvenir" to "souvenir_store",
        "gift shop" to "gift_shop",
        "gift store" to "gift_shop",
        "gifts" to "gift_shop",
        "vape" to "smoke_and_vape_store",
        "smoke shop" to "smoke_and_vape_store",
        "musical instrument" to "musical_instrument_store",
        "guitar shop" to "musical_instrument_store",
        "instrument shop" to "musical_instrument_store",
        "climbing gym" to "rock_climbing_gym",
        "climbing wall" to "rock_climbing_gym",
        "bouldering" to "rock_climbing_gym",
    )
    private val RECOMMEND = Regex(U + "\\b(best|good|great|top|recommend\\w*|suggest\\w*|where|find|any|list|options?|" +
        "places?|spots?|cheap|affordable|nice|popular|famous|must|should i|can i|could i|" +
        "favou?rite|near|nearby)\\b")
    private val PLACE_NOUNS = Regex(U + "\\b(restaurants?|cafes?|cafés?|coffee shops?|bars?|pubs?|hotels?|hostels?|bakery|bakeries|eatery|eateries|guest ?houses?|bistros?|brewery|breweries|pharmacy|pharmacies|chemists?|hospitals?|clinics?|dentists?|atms?|banks?|supermarkets?|groceries|grocery stores?|museums?|galleries|gallery|beaches|beach|parks?|gyms?|laundromats?|laundry|coworking|embassy|embassies|markets?|malls?|shops?|stores?|bookshops?|bookstores?|opticians?|florists?|jewell?ers?|tobacconists?|arcades?|casinos?|cinemas?|venues?|bowling alleys?|escape rooms?|ice rinks?|ice skating|climbing gyms?|bouldering|go[- ]?karts?|go[- ]?karting|karting|trampoline parks?|bowling|laser tag|live music|comedy|clubs?)\\b")
    private val LATE = Regex(U + "\\b(open late|late at night|late night|late-night|24 hours|24/7|all night|open now|tonight|after midnight|at night)\\b")
    private val HOURS = Regex(U + "\\b(open|opening|hours|clos(e|es|ed|ing)|late|tonight|now|today|tomorrow|morning|breakfast|" +
        "monday|tuesday|wednesday|thursday|friday|saturday|sunday|weekends?|24/7)\\b")
    private val LATE_HOURS = Regex("24/7|-\\s*(2[2-4]|0[0-5])[:.]")
    private val CRYPTO = Regex(U + "\\b(bitcoin|crypto|btc)", setOf(RegexOption.IGNORE_CASE))
    private val TRIP = Regex(U + "\\b(what (should|can|to) (i|we) (see|do|visit)|things to (do|see)|sights|sightseeing|get(ting)? around|" +
        "itinerary|how (do|can|should) (i|we) get|\\d+ days|(two|three|four|five|six|seven|few) days|a week in|weekend in)\\b")
    private val GENERAL = Regex(U + "\\b(what('s| is| are) (the )?\\w+( \\w+)? (like|scene)|typical|traditional|cuisine of|dishes|known for|famous for|specialit(y|ies)|must[- ]try)\\b")
    private val KNOWLEDGE = Regex(U + "\\b(history|origins?|invented|why|who (founded|owns|opened)|when (was|did)|how (did|do|does|is|are) \\w+ (made|cooked|prepared)|" +
        "oldest|first|largest|biggest|how many)\\b")
    private val HERE = Regex(U + "\\b(near me|nearby|around me|around here|near here|close to me|close by|where i am|" +
        "my location|this city|this town|city i am (currently )?in|city i'm (currently )?in|" +
        "current city|my area|in my city)\\b")
    private val ANCHOR = Regex(U + "\\b(in|near|around|at|of|for|across|throughout)\\s+(?:the\\s+)?",
        setOf(RegexOption.IGNORE_CASE))
    private val CAPITALISED = Regex(U + "[A-Z][\\w'’.-]*(?:\\s+(?:[A-Z][\\w'’.-]*|de|da|do|del|la|le|el|of|upon|am|sur|an|on|y|i)(?=\\s+[A-Z]))*(?:\\s+[A-Z][\\w'’.-]*)?")
    private val CLAUSE_END = Regex(U + "[?!;:()\\[\\]]|\\s(?:that|which|with|for|open|serving|where|who|to|and|or|but|if|while|when|because|so|I)\\s")
    private const val STRIP = " .,'\""
    private val WORDS = Regex("[a-z]+")

    /** Capitalised words that start questions and are also names of small towns. */
    val NOT_PLACES = setOf("best", "tell", "where", "what", "which", "who", "how", "can", "could", "should", "would",
        "any", "list", "give", "show", "find", "recommend", "suggest", "please", "i", "is", "are",
        "top", "good", "great", "cheap", "vegan", "vegetarian", "halal", "kosher", "some", "the")

    const val MIN_SUB = 3
    /** Rows read for a lookup without a diet, by the static rank (build_places.py). */
    const val CANDIDATES = 400
    private val CHEAP = Regex(U + "\\b(cheap|budget|affordable|inexpensive|low[- ]cost)\\b")
    private val FANCY = Regex(U + "\\b(upscale|luxury|luxurious|fine dining|splurge|expensive|fancy|high[- ]end|michelin)\\b")

    /** Categories that are diets, not cuisines. */
    val DIET_CATS = setOf("vegan_restaurant", "vegetarian_restaurant", "gluten_free_restaurant", "halal_restaurant",
        "kosher_restaurant", "special_diet_restaurant", "health_food_restaurant", "live_and_raw_food_restaurant")

    val DIET_MASK = mapOf("vegan" to 0, "vegetarian" to 0, "gluten_free" to GLUTEN_FREE, "halal" to HALAL, "kosher" to KOSHER)
    val DIET_KIND = mapOf("vegan" to "vegan_restaurant", "vegetarian" to "vegetarian_restaurant",
        "gluten_free" to "gluten_free_restaurant", "halal" to "halal_restaurant", "kosher" to "kosher_restaurant")
    val VEGAN_NAME = Regex(U + "\\b(vegan[oa]?s?|v[eé]gane?s?|plant[- ]based|vegana?s?)\\b",
        setOf(RegexOption.IGNORE_CASE))
    val VEGETARIAN_NAME = Regex(U + "\\b(vegetarian[oa]?s?|v[eé]g[eé]tarien(ne)?s?|veggie|vegetariano)\\b",
        setOf(RegexOption.IGNORE_CASE))

    val DIET_KINDS = mapOf(
        "vegan" to listOf("vegan_restaurant", "vegetarian_restaurant"),
        "vegetarian" to listOf("vegan_restaurant", "vegetarian_restaurant"),
        "gluten_free" to listOf("gluten_free_restaurant"), "halal" to listOf("halal_restaurant"),
        "kosher" to listOf("kosher_restaurant"),
    )
    val DIET_BITS_ANY = mapOf(
        "vegan" to (VEGAN_ONLY or VEGAN_YES or VEGAN_LIMITED or VEGETARIAN_ONLY),
        "vegetarian" to (VEGETARIAN_ONLY or VEGETARIAN_YES or VEGETARIAN_LIMITED or VEGAN_ONLY or VEGAN_YES or VEGAN_LIMITED),
        "gluten_free" to GLUTEN_FREE, "halal" to HALAL, "kosher" to KOSHER,
    )
    val DIET_NAME_LIKE = mapOf(
        "vegan" to listOf("%vegan%", "%végan%", "%plant%based%", "%vegetarian%", "%végétarien%", "%veggie%"),
        "vegetarian" to listOf("%vegetarian%", "%végétarien%", "%veggie%", "%vegan%", "%végan%"),
        "gluten_free" to listOf("%gluten%free%", "%sin gluten%", "%sans gluten%", "%glutenfrei%", "%senza glutine%", "%sin tacc%"),
        "halal" to listOf("%halal%"), "kosher" to listOf("%kosher%"),
    )
    val DIET_LABEL = mapOf(
        ("vegan" to 0) to "vegan", ("vegan" to 1) to "vegetarian", ("vegan" to 2) to "vegan options",
        ("vegan" to 3) to "a few vegan options", ("vegetarian" to 0) to "vegetarian",
        ("vegetarian" to 2) to "vegetarian options", ("vegetarian" to 3) to "a few vegetarian options",
    )

    /** places.py `_has`: [phrase] (or its plural in "s") as a whole word in [text]. */
    fun has(text: String, phrase: String): Boolean = at(text, phrase) != null

    /** places.py `_at`: where [phrase] (or its plural in "s") first occurs in [text] as whole words. */
    fun at(text: String, phrase: String): Int? =
        Regex("(?<![a-z0-9])" + Regex.escape(phrase) + "s?(?![a-z0-9])").find(text)?.range?.first

    /** places.py `_first`: the value whose phrase occurs first; at the same place the longer phrase, then the earlier pair. */
    fun first(text: String, pairs: List<Pair<String, String>>): String? {
        var best: String? = null
        var key: Triple<Int, Int, Int>? = null
        for ((i, pair) in pairs.withIndex()) {
            val a = at(text, pair.first) ?: continue
            val k = Triple(a, -pair.first.length, i)
            if (key == null || compareValuesBy(k, key, { it.first }, { it.second }, { it.third }) < 0) {
                best = pair.second
                key = k
            }
        }
        return best
    }

    /** places.py `parse`. [cuisines]: places.db's category names, in id order. */
    fun parse(question: String, cuisines: List<String>): PlaceAsk? {
        val q = Py.strip(question)
        val low = q.lowercase(Locale.ROOT)
        if (KNOWLEDGE.containsMatchIn(low) || TRIP.containsMatchIn(low)) return null
        val diet = DIET_WORDS.firstOrNull { has(low, it.first) }?.second
        // the kind of place named first is the one asked for ("a pharmacy near my hotel")
        var group = first(low, KIND_WORDS)
        // one kind within that group only ("a pharmacy near my hotel" is not about hotels)
        val wordGroup = KIND_WORDS.toMap()
        var sub = first(low, SUB_WORDS.filter { (wordGroup[it.first] ?: group) == group })
        if (sub == null) {
            val words = WORDS.findAll(low).map { it.value }.toSet()
            for (cat in cuisines) {
                if (cat.endsWith("_restaurant") && cat !in DIET_CATS) {
                    val stem = cat.removeSuffix("_restaurant").replace("_", " ")
                    if (stem.isNotEmpty() && stem !in setOf("fast food", "casual", "family") && has(low, stem) &&
                        (" " in stem || stem in words)
                    ) {
                        sub = cat
                        break
                    }
                }
            }
        }
        if (group == null && diet == null && sub == null) return null
        if (group == null) group = "eat"
        val asks = RECOMMEND.containsMatchIn(low) || PLACE_NOUNS.containsMatchIn(low)
        if (!asks && (GENERAL.containsMatchIn(low) || (diet == null && sub == null))) return null
        val here = HERE.containsMatchIn(low)
        if (!here && placeCandidates(q).isEmpty()) return null
        // the best museums or sights of a city are what the model and Wikipedia know well; the list
        // is for those near the phone
        if (group == "sights" && !here) return null
        val price = if (CHEAP.containsMatchIn(low)) "Budget" else if (FANCY.containsMatchIn(low)) "Splurge" else null
        return PlaceAsk(group, diet, sub, price, here, has(low, "restaurant"), q, LATE.containsMatchIn(low))
    }

    /** places.py `place_candidates`: (text, anchored), most likely first. */
    fun placeCandidates(q: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (m in ANCHOR.findAll(q).toList().asReversed()) {
            val rest = CLAUSE_END.split(q.substring(m.range.last + 1), 2)[0].trim { it in STRIP }
            if (rest.isNotEmpty()) out.add(rest to true)
        }
        for (m in CAPITALISED.findAll(q)) out.add(m.value.trim { it in STRIP } to false)
        return out
    }

    /** SQL LIKE as places.py `_like` does it (case-insensitive). */
    fun like(text: String, pattern: String): Boolean {
        val rx = buildString {
            for (ch in pattern) when (ch) {
                '%' -> append(".*")
                '_' -> append(".")
                else -> append(Regex.escape(ch.toString()))
            }
        }
        return Regex(rx, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matches(text)
    }

    /** places.py `diet_tier`: 0 all about the diet, 1 vegetarian (for a vegan question), 2 serves it, 3 a few options. */
    fun dietTier(p: Place, diet: String): Int? {
        val names = listOf(p.kind) + (p.alt ?: "").split(",").filter { it.isNotEmpty() }
        when (diet) {
            "vegan" -> {
                // OpenStreetMap's "vegetarian only" outweighs a vegan category from the other source
                // (Lotos in Buenos Aires, Pine Tree Cafe in Singapore): the place is vegetarian
                if (p.diet and VEGAN_ONLY != 0 || VEGAN_NAME.containsMatchIn(p.name) ||
                    ("vegan_restaurant" in names && p.diet and VEGETARIAN_ONLY == 0)
                ) return 0
                if (p.diet and VEGAN_NO != 0) return null
                if (p.diet and VEGETARIAN_ONLY != 0 || "vegetarian_restaurant" in names || VEGETARIAN_NAME.containsMatchIn(p.name)) return 1
                if (p.diet and VEGAN_YES != 0) return 2
                if (p.diet and VEGAN_LIMITED != 0) return 3
                return null
            }
            "vegetarian" -> {
                if (p.diet and (VEGETARIAN_ONLY or VEGAN_ONLY) != 0 ||
                    names.any { it == "vegetarian_restaurant" || it == "vegan_restaurant" } ||
                    VEGETARIAN_NAME.containsMatchIn(p.name) || VEGAN_NAME.containsMatchIn(p.name)
                ) return 0
                if (p.diet and (VEGETARIAN_YES or VEGAN_YES) != 0) return 2
                if (p.diet and (VEGETARIAN_LIMITED or VEGAN_LIMITED) != 0) return 3
                return null
            }
        }
        if (DIET_KIND.getValue(diet) in names || DIET_NAME_LIKE.getValue(diet).any { like(p.name, it) }) return 0
        if (p.diet and DIET_MASK.getValue(diet) != 0) return 2
        return null
    }

    /** places.py `in_group`. */
    fun inGroup(ask: PlaceAsk, path: List<String>): Boolean {
        if ("lodging" in path) return ask.group == "stay"
        if (ask.group == "eat") {
            return "food_and_drink" in path &&
                ("alcoholic_beverage_venue" !in path || ((ask.diet != null || ask.sub != null) && !ask.restaurant))
        }
        return GROUPS.getValue(ask.group).any { it in path }
    }

    /** places.py `sub_word`. */
    fun subWord(sub: String): String = sub.replace("_restaurant", "").replace("_shop", "").replace("_", " ")

    /** places.py's pattern for [subWord] in a name, compiled once per lookup. */
    fun subPattern(sub: String): Regex = Regex(U + "\\b" + Regex.escape(subWord(sub)))

    /** places.py `is_sub`. */
    fun isSub(p: Place, sub: String, rx: Regex = subPattern(sub)): Boolean {
        if (sub in p.kinds || sub in (p.alt ?: "").split(",")) return true
        val word = subWord(sub)
        if (!p.cuisine.isNullOrEmpty() && word in p.cuisine.lowercase(Locale.ROOT).replace("_", " ")) return true
        return rx.containsMatchIn(p.name.lowercase(Locale.ROOT))
    }

    private val DUP_STOP = setOf("the", "at", "of", "and", "de", "del", "la", "le", "el", "da", "do", "di")

    private fun words(name: String): Set<String> =
        normKey(name).split(" ").filter { it.isNotEmpty() && it !in DUP_STOP }.toSet()

    /** places.py `_same_place`: one name's words all in the other's, within a kilometre. */
    fun samePlace(p: Place, q: Place): Boolean {
        val a = words(p.name)
        val b = words(q.name)
        return a.isNotEmpty() && b.isNotEmpty() && (b.containsAll(a) || a.containsAll(b)) &&
            distanceKm(p.lat, p.lon, q.lat, q.lon) <= 1.0
    }

    private val OPTIONS_BITS = mapOf("vegan" to (VEGAN_YES or VEGAN_LIMITED), "vegetarian" to (VEGETARIAN_YES or VEGETARIAN_LIMITED or VEGAN_YES))

    /** places.py `overrule_branches`. */
    fun overruleBranches(out: List<Place>, diet: String) {
        val groups = LinkedHashMap<String, MutableList<Place>>()
        for (p in out) groups.getOrPut(normKey(p.name)) { ArrayList() }.add(p)
        val bits = OPTIONS_BITS.getValue(diet)
        for (group in groups.values) {
            if (group.size < 2 || group.none { it.diet and bits != 0 }) continue
            for (m in group) {
                if (m.tier == 0 && m.kind != "vegan_restaurant" && m.kind != "vegetarian_restaurant" &&
                    !VEGAN_NAME.containsMatchIn(m.name) && !VEGETARIAN_NAME.containsMatchIn(m.name)
                ) m.tier = 2
            }
        }
    }

    /** build_places.py `fame_bonus`. */
    fun fameBonus(views: Int): Double = if (views > 0) min(3.0, log10(1 + views / 100.0)) else 0.0

    /** Whether [name] is a crypto ATM's (places.py `CRYPTO`). */
    fun crypto(text: String): Boolean = CRYPTO.containsMatchIn(text)

    /** places.py `score`. */
    fun score(p: Place, radiusKm: Double, price: String? = null, late: Boolean = false): Double {
        var s = p.conf / 100.0 + fameBonus(p.fame)
        if (late && !p.hours.isNullOrEmpty() && LATE_HOURS.containsMatchIn(p.hours)) s += 1.0
        if (p.src and SRC_GUIDE != 0) {
            s += 1.5
            if (!price.isNullOrEmpty() && p.guide.any { !it.tier.isNullOrEmpty() && it.tier.lowercase(Locale.ROOT).startsWith(price.lowercase(Locale.ROOT).take(4)) }) s += 1.0
        }
        if (p.src and SRC_OSM != 0 && p.src and SRC_OVERTURE != 0) s += 0.5
        if (!p.website.isNullOrEmpty()) s += 0.2
        if (p.chain != 0) s -= 1.0
        s -= 0.6 * min(p.km / max(radiusKm, 1.0), 1.0)
        return s
    }

    /** places.py `reasons`. */
    fun reasons(p: Place, ask: PlaceAsk): List<String> {
        val why = ArrayList<String>()
        if (ask.diet != null) {
            var lab = DIET_LABEL[ask.diet to p.tier]
                ?: if (p.tier == 0) ask.diet.replace("_", "-") else ask.diet.replace("_", "-") + " options"
            // a vegan place found for a vegetarian question says it is vegan
            if (ask.diet == "vegetarian" && p.tier == 0 && dietTier(p, "vegan") == 0) lab = "vegan"
            why.add(lab)
        }
        p.guide.firstOrNull()?.let { g ->
            why.add("in the Wikivoyage guide (" + g.article + (if (!g.tier.isNullOrEmpty()) ", " + g.tier else "") + ")")
        }
        return why
    }

    fun kindLabel(kind: String) = kind.replace("_", " ")

    /** places.py `kind_bits`. */
    fun kindBits(p: Place): MutableList<String> {
        val why = p.why.filter { !it.startsWith("in the Wikivoyage") }
        if (why.isNotEmpty() && why[0] in setOf("vegan", "vegetarian") && p.kind in setOf("vegan_restaurant", "vegetarian_restaurant")) {
            return (listOf(why[0] + " restaurant") + why.drop(1)).toMutableList()
        }
        return (listOf(kindLabel(p.kind)) + why).toMutableList()
    }

    /** places.py `asks_hours`: the question is about when places are open. */
    fun asksHours(ask: PlaceAsk): Boolean = ask.late || HOURS.containsMatchIn(ask.question.lowercase(Locale.ROOT))

    /**
     * places.py `describe`: [brief] the model's line (no street, the cuisine), [hours] with the
     * opening hours, [wikiText] the start of the place's own Wikipedia article.
     */
    fun describe(p: Place, n: Int? = null, guideText: String? = null, origin: String = "the centre",
                 brief: Boolean = false, hours: Boolean = true, wikiText: String? = null): String {
        val head = if (n != null) "[$n] " else ""
        val bits = kindBits(p)
        if (brief && !p.cuisine.isNullOrEmpty()) {
            val c = p.cuisine.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { it.replace("_", " ") }
            if (c.isNotEmpty()) bits.add("cuisine: " + c.joinToString(", "))
        }
        if (!p.street.isNullOrEmpty() && !brief) bits.add(p.street + (if (!p.locality.isNullOrEmpty()) ", " + p.locality else ""))
        bits.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (!p.hours.isNullOrEmpty() && hours) bits.add("hours: " + p.hours)
        var line = head + p.name + ": " + bits.joinToString("; ") + "."
        if (!guideText.isNullOrEmpty()) line += " The travel guide says: $guideText"
        if (!wikiText.isNullOrEmpty()) line += " Wikipedia: $wikiText"
        return line
    }

    /**
     * places.py `lead_text`: the start of a Wikipedia article for the model: after its "# Title"
     * line, without a line of coordinates and without parentheses (pronunciations, names in other
     * scripts), whole sentences up to [n] characters (at least the first, clipped).
     */
    fun leadText(text: String, n: Int): String? {
        val m = Regex("^# [^\n]*\n+", RegexOption.MULTILINE).find(text)
        val lead = if (m != null) text.substring(m.range.last + 1) else text
        val lines = lead.split("\n").filter { it.isNotBlank() }.toMutableList()
        while (lines.isNotEmpty() && '\u00b0' in lines[0] && lines[0].length < 80) lines.removeAt(0)
        var para = lines.firstOrNull() ?: ""
        repeat(2) { para = PARENS.replace(para, "") }
        para = SPACES.replace(para, " ").trim()
        var out = ""
        for (piece in SENTENCE_END.split(para)) {
            if (piece.trim('.', ' ').isEmpty()) continue
            val sent = piece.trimEnd('.') + "."
            if (out.isNotEmpty() && out.length + 1 + sent.length > n) break
            out = (out + " " + sent).trim()
        }
        return if (out.isNotEmpty()) clip(out, n) else null
    }

    private val PARENS = Regex(U + "\\s*\\([^()]*\\)")
    private val SPACES = Regex(U + "\\s+")
    private val SENTENCE_END = Regex(U + "(?<=[a-z0-9)\\]])\\.\\s+")

    /** The line under a place's name in the app's list: what it is, how far, whether the guide lists it. */
    fun summary(p: Place, origin: String): String {
        val bits = kindBits(p)
        if (!p.street.isNullOrEmpty()) bits.add(p.street)
        bits.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (p.guide.isNotEmpty()) bits.add("in the travel guide")
        return bits.joinToString(" · ")
    }

    /** Everything known about a place, for the app's pop-up (the model reads [describe]). */
    fun details(p: Place, guideText: String?, origin: String, wikiText: String? = null): String {
        val lines = ArrayList<String>()
        lines.add(kindBits(p).joinToString(" · "))
        if (!p.street.isNullOrEmpty()) lines.add(p.street + (if (!p.locality.isNullOrEmpty()) ", " + p.locality else ""))
        lines.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (!p.hours.isNullOrEmpty()) lines.add("Opening hours: " + p.hours)
        if (!p.phone.isNullOrEmpty()) lines.add("Phone: " + p.phone)
        if (!p.website.isNullOrEmpty()) lines.add("Website: " + p.website)
        p.guide.firstOrNull()?.let { g ->
            val head = "Wikivoyage, " + g.article + (if (!g.tier.isNullOrEmpty()) " (" + g.tier + ")" else "")
            lines.add(if (guideText.isNullOrEmpty()) head else "$head: $guideText")
        }
        if (!wikiText.isNullOrEmpty()) lines.add("Wikipedia, " + p.wiki + ": " + wikiText)
        val from = ArrayList<String>()
        if (p.src and SRC_OVERTURE != 0) from.add("Overture Maps")
        if (p.src and SRC_OSM != 0) from.add("OpenStreetMap")
        if (p.src and SRC_GUIDE != 0) from.add("Wikivoyage")
        lines.add("From " + from.joinToString(", ") + " (offline)")
        return lines.joinToString("\n")
    }

    /** places.py `guide_text`, over the travel guide's article [text]. */
    fun guideLine(text: String?, listing: String): String? {
        if (text.isNullOrEmpty()) return null
        val prefix = "- - $listing"
        for (line in text.split("\n")) {
            if (line.startsWith(prefix)) {
                val rest = Py.strip(line.substring(prefix.length).trimStart(':', '.', ' '))
                return cpPrefix(rest, 400).ifEmpty { null }
            }
        }
        return null
    }

    /** places.py `_clip`: at most [n] code points, cut at a space when one is in the second half. */
    fun clip(text: String?, n: Int): String? {
        if (text == null || text.codePointCount(0, text.length) <= n) return text
        val cut = cpPrefix(text, n)
        val sp = cut.lastIndexOf(' ')
        val spCp = if (sp < 0) -1 else cut.codePointCount(0, sp)
        return (if (spCp > n / 2) cut.substring(0, sp) else cut) + "…"
    }

    private fun cpPrefix(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    const val PLACES_SYSTEM: String =
        "You are an offline travel assistant. The question comes with a numbered list of places from " +
        "offline map data (OpenStreetMap and Overture Maps), the Wikivoyage travel guide and Wikipedia, " +
        "best matches first; the app shows the list, with each place's address, distance and hours, next " +
        "to your answer. If the question asks for more than places (costs, tipping, safety, which one " +
        "suits), answer that first in a sentence or two, from what you know and from the list. Then " +
        "recommend the three to five places that best answer the question, one line each, starting with " +
        "its number and name like \"[2] Name:\", then what kind of place it is and what the list says about " +
        "it that matters (the travel guide's and Wikipedia's words when quoted, hours when asked about). Say " +
        "nothing about a place that the list does not say: no praise, popularity, ratings, atmosphere, " +
        "dishes, prices or neighbourhoods, unless it is a famous place you know well. Never describe the " +
        "list itself or what it lacks. Unless the question asks for more than places, the answer's first " +
        "characters are the first place's number, like \"[1]\". No closing remarks, no LaTeX, no visible " +
        "deliberation."
    const val MODEL_PLACES = 6
    const val GUIDE_CHARS = 180
    const val WIKI_CHARS = 200

    /**
     * places.py PLACES_SYSTEM_V2, with [describeV2]'s lines: six to eight places, each with what it
     * serves, its street and its hours, a line that map data can be out of date, and the answer in
     * the question's language (the first version gave three to five one-liners, thin next to a web
     * answer; notes/2026-09-29-places-answers.md).
     */
    const val PLACES_SYSTEM_V2: String =
        "You are an offline travel assistant. The question comes with a numbered list of places from offline " +
        "map data (OpenStreetMap and Overture Maps), the Wikivoyage travel guide and Wikipedia, best matches " +
        "first; the app shows the list next to your answer. If the question asks for more than places (costs, " +
        "tipping, safety, which one suits), answer that first in a sentence or two. Then recommend six to " +
        "eight places from the list, one or two sentences each, starting with its number and name like \"[2] " +
        "Name:\". For each, say what kind of place it is and what it serves (from its category and cuisine), " +
        "its street, and what the travel guide or Wikipedia says about it; give its hours when the list has " +
        "them. Add no dishes, prices, ratings, praise or remarks about a street or an area that the list does " +
        "not give, unless it is a famous place you know well. Finish with one short line saying that map data " +
        "can be out of date, so check a place is open before going. Write the whole answer in the language of " +
        "the question as it was asked. Never describe the list itself or what it lacks. No LaTeX, no visible " +
        "deliberation."

    /** places.py MODEL_PLACES_V2. */
    const val MODEL_PLACES_V2 = 8

    /** One of places.py `context_lines_v2`'s lines. */
    fun describeV2(p: Place, n: Int, guideText: String?, origin: String, wikiText: String?): String {
        val bits = kindBits(p)
        if (!p.cuisine.isNullOrEmpty()) {
            val c = p.cuisine.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { it.replace("_", " ") }
            if (c.isNotEmpty()) bits.add("cuisine: " + c.joinToString(", "))
        }
        if (!p.street.isNullOrEmpty()) bits.add("address: " + p.street)
        bits.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (!p.hours.isNullOrEmpty()) bits.add("hours: " + p.hours)
        var line = "[$n] " + p.name + ": " + bits.joinToString("; ") + "."
        if (!guideText.isNullOrEmpty()) line += " The travel guide says: $guideText"
        if (!wikiText.isNullOrEmpty()) line += " Wikipedia: $wikiText"
        return line
    }

    /** places.py `list_line`: a place as the app's list shows it. */
    fun listLine(p: Place, n: Int, origin: String): String = "[$n] " + p.name + " — " + summary(p, origin)

    /** places.py `what_text`. */
    fun whatText(ask: PlaceAsk): String {
        var what = GROUP_LABEL.getValue(ask.group)
        if (ask.diet != null) what = ask.diet.replace("_", "-") + " " + what
        return what
    }

    /** places.py `where_text`. */
    fun whereText(total: Int, radiusKm: Double, label: String, ask: PlaceAsk, capped: Boolean = false): String =
        String.format(Locale.US, "%d%s %s within %.0f km of %s", total, if (capped) "+" else "", whatText(ask), radiusKm, label)

    /** places.py `places_user`. */
    fun placesUser(question: String, where: String, lines: List<String>): String =
        "Places (" + where + "):\n\n" + lines.joinToString("\n") + "\n\nQuestion: " + question

    /** places.py `cells_around`. */
    fun cellsAround(lat: Double, lon: Double, km: Double): List<Long> {
        val dlat = km / 110.54
        val dlon = km / (111.32 * max(cos(Math.toRadians(lat)), 0.01))
        val r0 = floor((lat - dlat + 90.0) * 20.0).toInt()
        val r1 = floor((lat + dlat + 90.0) * 20.0).toInt()
        val c0 = floor((lon - dlon + 180.0) * 20.0).toInt()
        val c1 = floor((lon + dlon + 180.0) * 20.0).toInt()
        val cells = ArrayList<Long>()
        for (r in max(r0, 0)..min(r1, 3599)) for (c in c0..c1) cells.add(r.toLong() * CELL_COLS + Math.floorMod(c, CELL_COLS))
        return cells
    }

    /** places.py `distance_km`. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return 6371.0 * hypot(x, y)
    }
}

/**
 * places.db over one connection (places.py's database functions). Not thread-safe; like
 * [Corpus], call it from the one corpus thread.
 */
class Places(private val db: SqlDatabase) {
    /** The categories, in id order (what [PlacesText.parse] matches cuisines against). */
    val categories: List<String>
    private val kinds: Map<Long, Pair<String, List<String>>>

    init {
        val k = LinkedHashMap<Long, Pair<String, List<String>>>()
        for (r in db.query("select id, name, parents from kinds order by id")) {
            val name = r[1] as String
            k[r[0] as Long] = name to ((r[2] as String).split(",").filter { it.isNotEmpty() } + name)
        }
        kinds = k
        categories = k.values.map { it.first }
    }

    /** The places' Wikipedia titles: the `wiki` column (places.db format 4), or none in an older file. */
    private val wikiColumn: String =
        if (db.query("select count(*) from pragma_table_info('places') where name = 'wiki'").first()[0] as Long > 0) "wiki" else "null"

    /**
     * What the question asks for, when this file has places of that kind: a places.db from before
     * 1.8 has no shops by kind or places to go for fun, and such a question goes to Wikipedia.
     */
    fun parse(question: String): PlaceAsk? = PlacesText.parse(question, categories)?.takeIf { covers(it) }

    /** places.py `covers`: some category in this file belongs to what [ask] asks for. */
    fun covers(ask: PlaceAsk): Boolean = kinds.values.any { PlacesText.inGroup(ask, it.second) }

    /** places.py `locate`. */
    fun locate(ask: PlaceAsk): City? {
        // the word the question names its cuisine by is not a place ("thank you in Thai")
        val cuisine = ask.sub?.let { PlacesText.normKey(PlacesText.subWord(it)) }
        for ((text, anchored) in PlacesText.placeCandidates(ask.question)) {
            val key0 = PlacesText.normKey(text)
            val words = if (key0.isEmpty()) emptyList() else key0.split(" ")
            val capital = text.isNotEmpty() && Character.isUpperCase(text.codePointAt(0))
            for (n in min(words.size, 5) downTo 1) {
                val key = words.subList(0, n).joinToString(" ")
                if ((!anchored && key in PlacesText.NOT_PLACES) || key == cuisine) continue
                val ids = db.query("select city from city_names where key = ?", key).map { it[0] as Long }
                var cities = if (ids.isEmpty()) emptyList() else db.query(
                    "select id, name, country, admin1, lat, lon, population, capital from cities where id in (" +
                        ids.joinToString(",") { "?" } + ")", *ids.toTypedArray(),
                ).map { city(it) }
                val rest = words.subList(n, words.size)
                if (rest.isNotEmpty() && cities.isNotEmpty()) hinted(cities, rest)?.let { cities = it }
                // an alternate name counts the city's people once (Hong Kong was once "Victoria")
                fun weight(c: City) =
                    if (PlacesText.normKey(c.name) != key) c.population
                    else c.population * PRIMARY_WEIGHT * (if (c.capital != 0) CAPITAL_WEIGHT else 1L)
                var best = cities.maxWithOrNull(compareBy<City>({ weight(it) }, { -it.id }))
                val w = best?.let { weight(it) } ?: 0L
                if (anchored) {
                    val (region, people) = regionCity(key)
                    if (region != null && people > w) best = region
                }
                if (best == null) continue
                if (!((anchored && capital) || best.population >= 100_000)) continue
                val cn = db.query("select name from countries where code = ?", best.country).firstOrNull()?.get(0) as String?
                return best.copy(countryName = cn ?: "")
            }
        }
        return null
    }

    /**
     * places.py `_region_city`: the state or province named [key] with the most people in its
     * cities, as its largest city, and those people; (null, 0) when no region has that name.
     */
    private fun regionCity(key: String): Pair<City?, Long> {
        var best: City? = null
        var people = 0L
        for (r in db.query("select country, admin1 from region_names where key = ? and admin1 <> '' order by country, admin1", key)) {
            val n = (db.query("select coalesce(sum(population), 0) from cities where country = ? and admin1 = ?", r[0], r[1])
                .first()[0] as Number).toLong()
            val row = db.query(
                "select id, name, country, admin1, lat, lon, population, capital from cities " +
                    "where country = ? and admin1 = ? order by population desc, id limit 1", r[0], r[1],
            ).firstOrNull() ?: continue
            if (n > people) {
                best = city(row)
                people = n
            }
        }
        return best to people
    }

    private fun city(r: Array<Any?>) =
        City(r[0] as Long, r[1] as String, r[2] as String, r[3] as String?, num(r[4]), num(r[5]), r[6] as Long, (r[7] as Long).toInt())

    private fun hinted(cities: List<City>, rest: List<String>): List<City>? {
        for (i in rest.indices) {
            for (j in rest.size downTo i + 1) {
                val key = rest.subList(i, j).joinToString(" ")
                val regions = db.query("select country, admin1 from region_names where key = ?", key)
                    .map { (it[0] as String) to (it[1] as String) }
                if (regions.isNotEmpty()) {
                    val ok = cities.filter { c -> regions.any { (cc, a1) -> c.country == cc && (a1 == "" || a1 == c.admin1) } }
                    if (ok.isNotEmpty()) return ok
                }
            }
        }
        return null
    }

    /** places.py `find`: the places around (lat, lon) that answer [ask], best first, and how many matched. */
    fun find(ask: PlaceAsk, lat: Double, lon: Double, radiusKm: Double, limit: Int = 12): Found {
        val cells = PlacesText.cellsAround(lat, lon, radiusKm)
        // the group's categories, and the one kind of place asked for wherever it sits ("tapas bars":
        // tapas_bar is a casual eatery, not a bar)
        val wanted = kinds.filter { PlacesText.inGroup(ask, it.value.second) || (ask.sub != null && ask.sub in it.value.second) }.keys.toList()
        val sql = StringBuilder(
            "select id, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine, fame, " +
                wikiColumn + " " +
                "from places where cell in (" + cells.joinToString(",") { "?" } + ") and kind in (" + wanted.joinToString(",") { "?" } + ")",
        )
        val args = ArrayList<Any?>(cells + wanted)
        if (ask.diet != null) {
            val dietKinds = PlacesText.DIET_KINDS.getValue(ask.diet)
            val dk = kinds.filter { k -> k.value.second.any { it in dietKinds } }.keys.toList()
            val likes = PlacesText.DIET_NAME_LIKE.getValue(ask.diet)
            sql.append(" and (diet & ? != 0 or kind in (" + dk.joinToString(",") { "?" } + ") or alt like ? or alt like ?")
            likes.forEach { _ -> sql.append(" or name like ?") }
            sql.append(")")
            args.add(PlacesText.DIET_BITS_ANY.getValue(ask.diet).toLong())
            args.addAll(dk)
            args.add("%vegan_restaurant%")
            args.add("%vegetarian_restaurant%")
            args.addAll(likes)
        }
        val subRx = ask.sub?.let { PlacesText.subPattern(it) }
        // without a diet (which decides the order first) only the best CANDIDATES by the
        // question-free part of the score are read: in a big city that is thousands of rows fewer
        val cap = if (ask.diet != null) "" else " order by rank desc, id limit ${PlacesText.CANDIDATES}"
        var capped = false
        var out: ArrayList<Place>? = null
        if (ask.sub != null) {
            // first only the places that may be of the one kind asked for (a superset of isSub, so
            // the list is the same as filtering everything); all of them when too few are
            val dk = kinds.filter { ask.sub in it.value.second }.keys.toList()
            val w = PlacesText.subWord(ask.sub)
            val (narrowed, n) = rows(
                sql.toString() + " and (kind in (" + dk.joinToString(",") { "?" } + ") or alt like ? or " +
                    "replace(lower(cuisine), '_', ' ') like ? or name like ?)" + cap,
                args + dk + listOf("%${ask.sub}%", "%$w%", "%$w%"), ask, lat, lon, radiusKm, subRx,
            )
            val subs = narrowed.filter { it.sub }
            if (subs.size >= PlacesText.MIN_SUB) {
                out = ArrayList(subs)
                capped = cap.isNotEmpty() && n == PlacesText.CANDIDATES
            }
        }
        if (out == null) {
            val (all, n) = rows(sql.toString() + cap, args, ask, lat, lon, radiusKm, subRx)
            out = all
            capped = cap.isNotEmpty() && n == PlacesText.CANDIDATES
        }
        if (ask.diet == "vegan" || ask.diet == "vegetarian") PlacesText.overruleBranches(out, ask.diet)
        if (ask.sub != null) {
            val subs = out.filter { it.sub }
            if (subs.size >= PlacesText.MIN_SUB) out = ArrayList(subs)
        }
        if (out.isNotEmpty()) {
            val byId = out.associateBy { it.id }
            for (chunk in out.map { it.id }.chunked(500)) {
                for (g in db.query(
                    "select place, article, section, tier, listing from guide where place in (" +
                        chunk.joinToString(",") { "?" } + ") order by place, rowid", *chunk.toTypedArray(),
                )) {
                    byId.getValue(g[0] as Long).guide.add(GuideListing(g[1] as String, g[2] as String, g[3] as String?, g[4] as String))
                }
            }
        }
        for (p in out) p.score = PlacesText.score(p, radiusKm, ask.price, ask.late)
        // for "restaurants", restaurants before cafes, bakeries and shops of the same tier
        out.sortWith(compareBy<Place>({ it.tier }, { if (ask.restaurant && "restaurant" !in it.kinds) 1 else 0 }, { -it.score }, { it.id }))
        val seen = HashMap<String, Int>()
        val picked = ArrayList<Place>()
        for (p in out) {
            val key = PlacesText.normKey(p.name)
            val n = seen[key] ?: 0
            if ((p.chain != 0 && n >= 1) || n >= 2) continue
            // another record of a place already listed ("British Museum, London" after "The British Museum")
            if (picked.any { PlacesText.samePlace(p, it) }) continue
            seen[key] = n + 1
            picked.add(p)
            if (picked.size >= limit) break
        }
        for (p in picked) p.why = PlacesText.reasons(p, ask)
        return Found(picked, out.size, capped)
    }

    /** places.py `_places`: the rows of [sql] within the radius that serve the diet asked for, with their tier. */
    private fun rows(
        sql: String, args: List<Any?>, ask: PlaceAsk, lat: Double, lon: Double, radiusKm: Double, subRx: Regex?,
    ): Pair<ArrayList<Place>, Int> {
        val out = ArrayList<Place>()
        val rows = db.query(sql, *args.toTypedArray())
        val cryptoOk = PlacesText.crypto(ask.question)
        for (r in rows) {
            val (kname, kpath) = kinds.getValue(r[2] as Long)
            // a bitcoin ATM is not where to withdraw cash
            if (ask.group == "money" && !cryptoOk && PlacesText.crypto(r[1] as String)) continue
            val p = Place(
                r[0] as Long, r[1] as String, kname, kpath, r[3] as String?, (r[4] as Long).toInt(), (r[5] as Long).toInt(),
                (r[6] as Long).toInt(), (r[7] as Long).toInt(), (r[8] as Long) / 1e5, (r[9] as Long) / 1e5,
                r[10] as String?, r[11] as String?, r[12] as String?, r[13] as String?, r[14] as String?, r[15] as String?,
                (r[16] as Long).toInt(), r[17] as String?,
            )
            p.km = PlacesText.distanceKm(lat, lon, p.lat, p.lon)
            if (p.km > radiusKm) continue
            var tier = 0
            if (ask.diet != null) tier = PlacesText.dietTier(p, ask.diet) ?: continue
            p.sub = ask.sub != null && PlacesText.isSub(p, ask.sub, subRx!!)
            p.tier = tier
            out.add(p)
        }
        return out to rows.size
    }

    /** places.py `lookup`: around the city the question names, or around [here] for "near me". */
    fun lookup(ask: PlaceAsk, here: Pair<Double, Double>? = null): PlacesLookup? {
        if (ask.here) {
            if (here == null) return null
            var found = Found(emptyList(), 0, false)
            var radius = HERE_RADII[0]
            for (r in HERE_RADII) {
                radius = r
                found = find(ask, here.first, here.second, r)
                if (found.total >= MIN_HERE) break
            }
            return PlacesLookup("your position", radius, found.total, found.places, found.capped, "you")
        }
        val city = locate(ask) ?: return null
        // nothing of the kind in the city: the nearest there is, up to MAX_RADIUS_KM away
        var found = Found(emptyList(), 0, false)
        var radius = city.radiusKm
        for (m in CITY_WIDEN) {
            radius = min(city.radiusKm * m, MAX_RADIUS_KM)
            found = find(ask, city.lat, city.lon, radius)
            if (found.total > 0) break
        }
        return PlacesLookup(city.label(), radius, found.total, found.places, found.capped, "the centre", city)
    }

    private fun num(v: Any?): Double = (v as Number).toDouble()

    companion object {
        val HERE_RADII = listOf(2.0, 5.0, 10.0)
        /** places.py `PRIMARY_WEIGHT`: a city whose own name is the one asked for counts as three times its size. */
        const val PRIMARY_WEIGHT = 3L
        /** places.py `CAPITAL_WEIGHT`: a national capital by its own name counts five times again. */
        const val CAPITAL_WEIGHT = 5L
        const val MIN_HERE = 5
        val CITY_WIDEN = listOf(1.0, 2.0, 4.0)
        const val MAX_RADIUS_KM = 50.0
    }
}

/**
 * What the places route shows and what the model reads for one lookup: the numbered list as
 * sources (each with its details and the travel guide's words) and the lines of the prompt.
 * Built on the corpus thread ([voyage] is read for the guide's words). The app also builds one
 * while the model is still loading, so the list is on screen before the model is ready.
 */
class PlacesAnswer(
    val ask: PlaceAsk,
    val lookup: PlacesLookup,
    val where: String,
    val sources: List<ResearchSource>,
    val modelLines: List<String>,
) {
    companion object {
        /** The model's lines are places.py `context_lines_v2`'s, for [PlacesText.PLACES_SYSTEM_V2]. */
        fun of(ask: PlaceAsk, lookup: PlacesLookup, voyage: Corpus?, wiki: Corpus? = null): PlacesAnswer {
            // the travel guide's words on each listed place, from the Wikivoyage corpus
            val guide = lookup.places.map { p ->
                p.guide.firstOrNull()?.let { g ->
                    voyage?.resolveTitle(g.article, fuzzy = false)?.let { aid ->
                        PlacesText.guideLine(voyage.article(aid).text.value, g.listing)
                    }
                }
            }
            // the start of each well-known place's own Wikipedia article
            val lead = lookup.places.map { p ->
                p.wiki?.let { t ->
                    wiki?.resolveTitle(t, fuzzy = false)?.let { aid -> PlacesText.leadText(wiki.article(aid).text.value, PlacesText.WIKI_CHARS) }
                }
            }
            val sources = lookup.places.mapIndexed { i, p ->
                ResearchSource(i + 1, p.name, PlacesText.summary(p, lookup.origin),
                    PlacesText.details(p, guide[i], lookup.origin, lead[i]), "places", p.lat, p.lon)
            }
            val lines = lookup.places.take(PlacesText.MODEL_PLACES_V2).mapIndexed { i, p ->
                PlacesText.describeV2(p, i + 1, PlacesText.clip(guide[i], PlacesText.GUIDE_CHARS), lookup.origin, lead[i])
            }
            val where = PlacesText.whereText(lookup.total, lookup.radiusKm, lookup.label, ask, lookup.capped)
            return PlacesAnswer(ask, lookup, where, sources, lines)
        }

        /**
         * The list for [question] from [corpora] (corpus thread), for the screen while the model
         * loads: null unless it is a places question about a named place with something found.
         */
        fun preview(corpora: CorpusProvider, question: String): PlacesAnswer? {
            val db = corpora.places() ?: return null
            val ask = db.parse(question)?.takeIf { !it.here } ?: return null
            val lookup = db.lookup(ask)?.takeIf { it.places.isNotEmpty() } ?: return null
            return of(ask, lookup, corpora.voyage(), corpora.wiki())
        }
    }
}

