// GENERATED from scripts/rag.py constants (verbatim); tests pin each prompt by SHA-256.
package org.androidlm.research

/** The four system prompts of scripts/rag.py, verbatim, plus its two user-message layouts. */
object Prompts {
    const val PLAN_SYSTEM: String =
        "You plan lookups in an offline copy of English Wikipedia. Given a question, list the " +
        "exact titles of up to 4 Wikipedia articles most likely to contain the answer, one per " +
        "line, most important first. For comparisons or multi-part questions include an article " +
        "for each part. For travel questions name the place itself (city, region or country) " +
        "first. If the question depends on an intermediate fact you know, name the article for " +
        "the final subject too. Output only the titles, nothing else."

    const val ANSWER_SYSTEM: String =
        "You are an offline research assistant. Answer the question directly and completely, " +
        "using your own knowledge together with the numbered sources from an offline copy of " +
        "Wikipedia. Cite a source like [1] where it supports a statement. Ignore sources that " +
        "are off-topic. If something important is not covered by the sources, still answer it " +
        "from your own knowledge; never withhold well-known facts or safety advice because a " +
        "source is missing. If you are unsure of a specific name, date or number, say so instead " +
        "of guessing. Address every part of the question in the first few lines, then elaborate. " +
        "No preamble, no restating the question, no LaTeX, no visible deliberation. Be concise."

    const val CLOSED_SYSTEM: String =
        "You are an offline research assistant. Answer the question directly and completely from " +
        "your own knowledge. If you are unsure of a specific name, date or number, say so " +
        "instead of guessing. Address every part of the question in the first few lines, then " +
        "elaborate. No preamble, no restating the question, no LaTeX, no citations or reference " +
        "lists, no visible deliberation. Be concise."

    const val VERIFY_SYSTEM: String =
        "You compare a draft answer with numbered sources from an offline copy of Wikipedia. Read all " +
        "the sources before writing. Then write \"From the sources:\" and up to three short points, at " +
        "most 100 words in all: the facts from the sources that matter most for the question and that " +
        "the draft leaves out or states differently, each with its citation like [2]. State each fact " +
        "as the source gives it; do not say whether the draft was right or wrong. If the sources only " +
        "confirm the draft, write \"The sources agree with the answer\" and cite them. Each source is " +
        "about the subject named in its title; do not attach its facts to another subject. Ignore " +
        "off-topic sources. Do not repeat the draft."

    const val CHECK_FOLLOWUP: String =
        "Now compare your answer above with these numbered sources from an offline copy of Wikipedia. " +
        "Read all the sources before writing. Then write \"From the sources:\" and up to three short " +
        "points, at most 100 words in all: the facts from the sources that matter most for the " +
        "question and that your answer leaves out or states differently, each with its citation like " +
        "[2]. State each fact as the source gives it; do not say whether your answer was right or " +
        "wrong. If the sources only confirm your answer, write \"The sources agree with the answer\" " +
        "and cite them. Each source is about the subject named in its title; do not attach its facts " +
        "to another subject. Ignore off-topic sources. Do not repeat your answer."

    /**
     * A follow-up question rewritten to stand on its own (app only; rag.py takes one question at a
     * time). The earlier exchange fills in what the follow-up refers to.
     */
    const val FOLLOWUP_SYSTEM: String =
        "Rewrite the user's follow-up question as one question that can be understood on its own, " +
        "using the previous question and answer only to fill in what the follow-up refers to (a " +
        "person, a place, a thing, a list). Keep the user's wording and intent and do not answer it. " +
        "If the follow-up already stands on its own, repeat it unchanged. Output only the question."

    /** The translation of a question in another language (ResearchPipeline Translation). */
    const val TRANSLATE_SYSTEM: String =
        "Translate the user's question into English, writing the names of places and people as they " +
        "are usually written in English. Keep its meaning and do not answer it. Output only the English question."

    /** A translated question for the answering prompts: the answer is written in the language it was asked in. */
    fun replyIn(question: String, asked: String): String =
        question + "\n\nAnswer in the language of the question as it was asked: " + asked

    /** User message of the follow-up rewrite: the previous exchange (its answer cut short) and the follow-up. */
    fun followupUser(previousQuestion: String, previousAnswer: String, followUp: String): String =
        "Previous question: " + previousQuestion + "\n\nPrevious answer: " + previousAnswer + "\n\nFollow-up: " + followUp

    /** rag.py WORKED_SYSTEM: a question that needs a calculation, with a few lines of working first. */
    const val WORKED_SYSTEM: String =
        "You are an offline research assistant. This question needs a calculation. First work it out in " +
        "a few short lines: the facts and numbers you use (say which are approximate) and each step of " +
        "the arithmetic. Then give the result on a last line starting with 'Answer:'. If you are unsure " +
        "of a number, say so instead of guessing. No preamble, no LaTeX, no citations or reference " +
        "lists."

    /** rag.py WORKED_SOURCES_SYSTEM: the same with the sources in context (retrieval first). */
    const val WORKED_SOURCES_SYSTEM: String =
        "You are an offline research assistant. This question needs a calculation. Use your own " +
        "knowledge together with the numbered sources from an offline copy of Wikipedia, citing a source " +
        "like [1] where it gives a number you use. First work it out in a few short lines: the facts and " +
        "numbers (say which are approximate) and each step of the arithmetic. Then give the result on a " +
        "last line starting with 'Answer:'. Ignore sources that are off-topic. No preamble, no LaTeX."

    /** Follow-up user turn of the continued source check: rag.py `check_followup_user`. */
    fun checkFollowupUser(context: String, followup: String = CHECK_FOLLOWUP): String = followup + "\n\nSources:\n\n" + context

    /** User message of the retrieval-first answer: rag.py `f"Sources:\n\n{context}\n\nQuestion: {question}"`. */
    fun answerUser(context: String, question: String): String =
        "Sources:\n\n" + context + "\n\nQuestion: " + question

    /** User message of the source check that follows an answer-first draft. */
    fun verifyUser(question: String, draft: String, context: String): String =
        "Question: " + question + "\n\nDraft answer:\n" + draft + "\n\nSources:\n\n" + context
}

internal object Lexicon {
    /** rag.py STOP */
    val STOP: Set<String> = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "but", "is", "are",
        "was", "were", "be", "been", "by", "with", "from", "as", "that", "this", "these", "those",
        "which", "what", "who", "whom", "whose", "how", "why", "when", "where", "did", "does",
        "do", "it", "its", "their", "his", "her", "he", "she", "they", "them", "i", "me", "my",
        "we", "our", "you", "your", "can", "could", "should", "would", "will", "may", "might",
        "about", "into", "than", "then", "there", "here", "not", "no", "know", "before", "after",
        "tell", "me", "explain", "describe", "summarize", "main", "roughly", "show", "reasoning",
    )

    /**
     * rag.py ASPECT_HEADINGS: porter stem -> the heading words or PHRASES that answer that aspect
     * (Python keeps each value as one ", "-separated string). A phrase is matched as a whole, with
     * word boundaries, so "get around" does not match the "Get in" section.
     */
    val ASPECT_HEADINGS: Map<String, List<String>> = mapOf(
        "treat" to listOf("treatment", "management", "therapy"),
        "aid" to listOf("treatment", "management", "first aid"),
        "prevent" to listOf("prevention", "prophylaxis"),
        "avoid" to listOf("prevention"),
        "symptom" to listOf("signs", "symptoms", "presentation"),
        "sign" to listOf("signs", "symptoms", "presentation"),
        "caus" to listOf("cause", "causes", "etiology"),
        "see" to listOf("see", "sights", "attractions", "landmarks", "tourism"),
        "visit" to listOf("see", "sights", "attractions", "landmarks"),
        "site" to listOf("see", "sights", "attractions", "landmarks"),
        "priorit" to listOf("see", "sights", "districts"),
        "district" to listOf("districts", "understand"),
        "laid" to listOf("understand", "orientation", "districts"),
        "food" to listOf("eat", "cuisine"),
        "eat" to listOf("eat", "cuisine"),
        "try" to listOf("eat", "cuisine", "drink"),
        "etiquett" to listOf("respect", "etiquette", "customs"),
        "custom" to listOf("respect", "etiquette", "customs"),
        "weather" to listOf("climate"),
        "season" to listOf("climate"),
        "safeti" to listOf("stay safe", "safety"),
        "safe" to listOf("stay safe", "safety"),
        "hike" to listOf("do", "hiking", "trekking"),
        "region" to listOf("regions"),
        "sleep" to listOf("sleep", "accommodation"),
        "transport" to listOf("get around", "get in"),
        "scam" to listOf("stay safe", "cope"),
        "hassl" to listOf("stay safe", "cope"),
        "danger" to listOf("stay safe"),
        "fee" to listOf("understand", "get in", "fees", "permits"),
        "permit" to listOf("understand", "get in", "fees", "permits"),
        "visa" to listOf("get in"),
        "guid" to listOf("understand", "get in"),
        "cold" to listOf("climate"),
        "car" to listOf("get around"),
        "around" to listOf("get around"),
        "move" to listOf("get around", "get in"),
        "train" to listOf("get in", "get around"),
        "base" to listOf("sleep", "districts", "cities"),
        "neighbourhood" to listOf("districts", "sleep", "understand"),
        "dress" to listOf("respect"),
        "behaviour" to listOf("respect"),
        "behavior" to listOf("respect"),
        "trip" to listOf("go next"),
        "nearbi" to listOf("go next"),
        "accommod" to listOf("sleep"),
        "hostel" to listOf("sleep"),
    )

    /** rag.py TRAVEL_STEMS: question stems that signal a travel question (the optional travel route). */
    val TRAVEL_STEMS: Set<String> = setOf(
        "accommod", "base", "behaviour", "custom", "dress", "eat", "etiquett", "fee", "food",
        "hassl", "hike", "hostel", "itinerari", "layov", "scam", "see", "sleep", "stay", "travel",
        "trek", "trip", "try", "visit",
    )
}

/** rag.py `needs_working`: a question whose answer is a calculation (WORKED_SYSTEM). */
object Worked {
    private val WORDS = Regex("\\b(how many times|times (larger|bigger|smaller|more|heavier|longer|farther)|times as (big|large|heavy|long|far|many)|how old (was|is|were|will)|by (roughly |about |approximately )?how (many|much)|percent|per ?cent|compound|interest rate|average speed)\\b|%", RegexOption.IGNORE_CASE)
    private val WITH_NUMBER = Regex("\\b(how (long|far|much|many|fast)|what time|when (do|will|would|should) (i|we|you)|arrive)\\b", RegexOption.IGNORE_CASE)
    private val IF = Regex("(^|\\b)if (the|i|we|you|a|an)\\b.*\\bhow (far|long|big|much|many|fast|heavy)\\b", RegexOption.IGNORE_CASE)
    private val DIGIT = Regex("\\d")

    fun needs(question: String): Boolean =
        WORDS.containsMatchIn(question) || IF.containsMatchIn(question) ||
            (DIGIT.containsMatchIn(question) && WITH_NUMBER.containsMatchIn(question))
}

