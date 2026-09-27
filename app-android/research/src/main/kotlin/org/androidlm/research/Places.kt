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
        "cafe" to listOf("cafe", "coffee_shop", "coffee_roastery", "non_alcoholic_beverage_venue", "tea_room",
            "bubble_tea_shop", "breakfast_and_brunch_restaurant"),
        "drink" to listOf("alcoholic_beverage_venue"),
        "sweet" to listOf("bakery", "patisserie", "dessert_shop", "ice_cream_shop", "donut_shop", "cupcake_shop",
            "candy_store", "chocolatier", "frozen_yoghurt_shop", "gelato"),
        "stay" to listOf("lodging"),
    )

    private val KIND_WORDS = listOf(
        "places to eat" to "eat", "place to eat" to "eat", "where to eat" to "eat", "food" to "eat",
        "restaurant" to "eat", "eatery" to "eat", "eateries" to "eat", "dinner" to "eat", "lunch" to "eat",
        "meal" to "eat", "dining" to "eat", "eat" to "eat", "brunch" to "cafe", "breakfast" to "cafe",
        "cafe" to "cafe", "café" to "cafe", "coffee" to "cafe", "tea house" to "cafe",
        "bar" to "drink", "pub" to "drink", "beer" to "drink", "brewery" to "drink", "breweries" to "drink",
        "cocktail" to "drink", "wine" to "drink", "nightlife" to "drink", "drinks" to "drink",
        "bakery" to "sweet", "bakeries" to "sweet", "pastry" to "sweet", "pastries" to "sweet",
        "dessert" to "sweet", "ice cream" to "sweet", "gelato" to "sweet", "donut" to "sweet",
        "hotel" to "stay", "hostel" to "stay", "guesthouse" to "stay", "guest house" to "stay",
        "accommodation" to "stay", "place to stay" to "stay", "places to stay" to "stay",
        "where to stay" to "stay", "where should i stay" to "stay", "where can i stay" to "stay",
        "where to sleep" to "stay", "bed and breakfast" to "stay", "b&b" to "stay",
    )
    private val DIET_WORDS = listOf(
        "plant based" to "vegan", "plant-based" to "vegan", "vegan" to "vegan",
        "vegetarian" to "vegetarian", "veggie" to "vegetarian",
        "gluten free" to "gluten_free", "gluten-free" to "gluten_free", "celiac" to "gluten_free",
        "coeliac" to "gluten_free", "halal" to "halal", "kosher" to "kosher",
    )
    private val SUB_WORDS = listOf(
        "hostel" to "hostel", "bed and breakfast" to "bed_and_breakfast", "b&b" to "bed_and_breakfast",
        "guest house" to "guest_house", "guesthouse" to "guest_house", "campsite" to "campground",
        "camping" to "campground", "hotel" to "hotel", "bakery" to "bakery", "bakeries" to "bakery",
        "ice cream" to "ice_cream_shop", "gelato" to "ice_cream_shop", "donut" to "donut_shop",
        "dessert" to "dessert_shop", "brewery" to "brewery", "breweries" to "brewery", "pub" to "pub",
        "brunch" to "breakfast_and_brunch_restaurant", "breakfast" to "breakfast_and_brunch_restaurant",
        "coffee" to "coffee_shop", "steak" to "steakhouse", "tapas" to "tapas_bar",
        "dim sum" to "dim_sum_restaurant", "noodle" to "noodles_restaurant", "taco" to "taco_restaurant",
        "burger" to "burger_restaurant", "dumpling" to "dumpling_restaurant", "curry" to "indian_restaurant",
        "street food" to "food_stand", "food truck" to "food_truck_stand", "juice" to "smoothie_juice_bar",
        "bubble tea" to "bubble_tea_shop", "wine bar" to "wine_bar", "cocktail bar" to "cocktail_bar",
    )
    private val RECOMMEND = Regex(U + "\\b(best|good|great|top|recommend\\w*|suggest\\w*|where|find|any|list|options?|" +
        "places?|spots?|cheap|affordable|nice|popular|famous|must|should i|can i|could i|" +
        "favou?rite|near|nearby)\\b")
    private val PLURAL_PLACES = Regex(U + "\\b(restaurants|cafes|cafés|bars|pubs|hotels|hostels|bakeries)\\b")
    private val GENERAL = Regex(U + "\\b(what('s| is| are) (the )?\\w+( \\w+)? (like|scene)|typical|traditional|cuisine of|dishes|known for|famous for|specialit(y|ies)|must[- ]try)\\b")
    private val KNOWLEDGE = Regex(U + "\\b(history|origins?|invented|why|who (founded|owns|opened)|when (was|did)|how (did|do|does|is|are) \\w+ (made|cooked|prepared))\\b")
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
    fun has(text: String, phrase: String): Boolean =
        Regex("(?<![a-z0-9])" + Regex.escape(phrase) + "s?(?![a-z0-9])").containsMatchIn(text)

    /** places.py `parse`. [cuisines]: places.db's category names, in id order. */
    fun parse(question: String, cuisines: List<String>): PlaceAsk? {
        val q = Py.strip(question)
        val low = q.lowercase(Locale.ROOT)
        if (KNOWLEDGE.containsMatchIn(low)) return null
        val diet = DIET_WORDS.firstOrNull { has(low, it.first) }?.second
        var group = KIND_WORDS.firstOrNull { has(low, it.first) }?.second
        var sub = SUB_WORDS.firstOrNull { has(low, it.first) }?.second
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
        val asks = RECOMMEND.containsMatchIn(low) || PLURAL_PLACES.containsMatchIn(low)
        if (!asks && (GENERAL.containsMatchIn(low) || (diet == null && sub == null))) return null
        val here = HERE.containsMatchIn(low)
        if (!here && placeCandidates(q).isEmpty()) return null
        val price = if (CHEAP.containsMatchIn(low)) "Budget" else if (FANCY.containsMatchIn(low)) "Splurge" else null
        return PlaceAsk(group, diet, sub, price, here, has(low, "restaurant"), q)
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
                if (p.diet and VEGAN_ONLY != 0 || "vegan_restaurant" in names || VEGAN_NAME.containsMatchIn(p.name)) return 0
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

    /** places.py `score`. */
    fun score(p: Place, radiusKm: Double, price: String? = null): Double {
        var s = p.conf / 100.0
        if (p.src and SRC_GUIDE != 0) {
            s += 2.0
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
            val lab = DIET_LABEL[ask.diet to p.tier]
                ?: if (p.tier == 0) ask.diet.replace("_", "-") else ask.diet.replace("_", "-") + " options"
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

    /** places.py `describe`. */
    fun describe(p: Place, n: Int? = null, guideText: String? = null, origin: String = "the centre"): String {
        val head = if (n != null) "[$n] " else ""
        val bits = kindBits(p)
        if (!p.street.isNullOrEmpty()) bits.add(p.street + (if (!p.locality.isNullOrEmpty()) ", " + p.locality else ""))
        bits.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (!p.hours.isNullOrEmpty()) bits.add("hours: " + p.hours)
        var line = head + p.name + ": " + bits.joinToString("; ") + "."
        if (!guideText.isNullOrEmpty()) line += " The travel guide says: $guideText"
        return line
    }

    /** The line under a place's name in the app's list: what it is, how far, whether the guide lists it. */
    fun summary(p: Place, origin: String): String {
        val bits = kindBits(p)
        if (!p.street.isNullOrEmpty()) bits.add(p.street)
        bits.add(String.format(Locale.US, "%.1f km from %s", p.km, origin))
        if (p.guide.isNotEmpty()) bits.add("in the travel guide")
        return bits.joinToString(" · ")
    }

    /** Everything known about a place, for the app's pop-up (the model reads [describe]). */
    fun details(p: Place, guideText: String?, origin: String): String {
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
        "You are an offline travel assistant. Answer the question from the numbered list of places, " +
        "which comes from offline map data (OpenStreetMap and Overture Maps) and the Wikivoyage travel " +
        "guide, best matches first. Recommend the three to five places that best answer the question, one " +
        "short line each (under 30 words, in your own words, not copied from the list): its name and number " +
        "like [2], what kind of place it is, its street as the list gives it, and the gist of the travel " +
        "guide's words when the list quotes them. Use only facts from the list; add what you know about a " +
        "place only if it is well known and you are sure. Never invent ratings, prices, dishes, " +
        "neighbourhoods or opening hours. Close with one sentence noting that map data has no ratings and " +
        "places close, so it is worth checking before going. No preamble, no LaTeX, no visible deliberation."
    const val MODEL_PLACES = 6
    const val GUIDE_CHARS = 180

    /** places.py `what_text`. */
    fun whatText(ask: PlaceAsk): String {
        var what = when (ask.group) {
            "eat" -> "places to eat"
            "cafe" -> "cafes"
            "drink" -> "places to drink"
            "sweet" -> "bakeries and sweet shops"
            else -> "places to stay"
        }
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

    fun parse(question: String): PlaceAsk? = PlacesText.parse(question, categories)

    /** places.py `locate`. */
    fun locate(ask: PlaceAsk): City? {
        for ((text, anchored) in PlacesText.placeCandidates(ask.question)) {
            val key0 = PlacesText.normKey(text)
            val words = if (key0.isEmpty()) emptyList() else key0.split(" ")
            val capital = text.isNotEmpty() && Character.isUpperCase(text.codePointAt(0))
            for (n in min(words.size, 5) downTo 1) {
                val key = words.subList(0, n).joinToString(" ")
                if (!anchored && key in PlacesText.NOT_PLACES) continue
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
            "select id, name, kind, alt, diet, src, conf, chain, lat5, lon5, street, locality, phone, website, hours, cuisine " +
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
        for (p in out) p.score = PlacesText.score(p, radiusKm, ask.price)
        // for "restaurants", restaurants before cafes, bakeries and shops of the same tier
        out.sortWith(compareBy<Place>({ it.tier }, { if (ask.restaurant && "restaurant" !in it.kinds) 1 else 0 }, { -it.score }, { it.id }))
        val seen = HashMap<String, Int>()
        val picked = ArrayList<Place>()
        for (p in out) {
            val key = PlacesText.normKey(p.name)
            val n = seen[key] ?: 0
            if ((p.chain != 0 && n >= 1) || n >= 2) continue
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
        for (r in rows) {
            val (kname, kpath) = kinds.getValue(r[2] as Long)
            val p = Place(
                r[0] as Long, r[1] as String, kname, kpath, r[3] as String?, (r[4] as Long).toInt(), (r[5] as Long).toInt(),
                (r[6] as Long).toInt(), (r[7] as Long).toInt(), (r[8] as Long) / 1e5, (r[9] as Long) / 1e5,
                r[10] as String?, r[11] as String?, r[12] as String?, r[13] as String?, r[14] as String?, r[15] as String?,
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
