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
        "You check a draft answer against numbered sources from an offline copy of Wikipedia. " +
        "Read all the sources before writing. Then write a short source check, at most 120 " +
        "words, with two parts. Corrections: each statement in the draft that a source " +
        "contradicts, with the correct fact and its citation like [2]. A statement is not wrong " +
        "merely because the sources do not mention it. Additions: up to three important " +
        "specifics that answer the question, that the sources provide and the draft lacks, with " +
        "citations. If there is nothing to correct, write 'No corrections' and cite the sources " +
        "that support the draft. Each source is about the subject named in its title; do not " +
        "attach its facts to another subject. Ignore off-topic sources. Do not repeat the draft."

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

    /** rag.py ASPECT_HEADINGS: porter stem -> heading words that answer that aspect. */
    val ASPECT_HEADINGS: Map<String, List<String>> = mapOf(
        "treat" to listOf("treatment", "management", "therapy"),
        "aid" to listOf("treatment", "management", "first", "aid"),
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
        "safeti" to listOf("stay", "safe", "safety"),
        "safe" to listOf("stay", "safe", "safety"),
        "hike" to listOf("do", "hiking", "trekking"),
        "region" to listOf("regions"),
        "sleep" to listOf("sleep", "accommodation"),
        "transport" to listOf("get", "around", "get", "in"),
    )
}
