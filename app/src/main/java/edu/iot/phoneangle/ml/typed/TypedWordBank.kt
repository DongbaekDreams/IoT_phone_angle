package edu.iot.phoneangle.ml.typed

import kotlin.random.Random

/**
 * Search-bar prompts for Play collection.
 * Draw: 20% google, 20% yahoo (a third of those as `.com`), 15% lookalikes, 45% ordinary searches.
 * Every query trains the tap-position model (all letters count), so variety beats repetition.
 */
object TypedWordBank {

    const val GOOGLE = "google"
    const val YAHOO = "yahoo"

    /** Words the spotter must not confuse with a target ([WordSpotter.COMPETITORS] plus more). */
    val HARD_NEGATIVES: List<String> = WordSpotter.COMPETITORS + listOf(
        "goal", "gobble", "gold", "yahtzee", "yoyo", "yahoot", "yellow", "yoohoo",
    ).filter { it !in WordSpotter.COMPETITORS }

    /** Typical short search queries — the realistic `other` class. */
    val SEARCHES: List<String> = listOf(
        "weather", "news", "maps", "amazon", "netflix", "reddit", "twitch", "spotify", "discord",
        "facebook", "instagram", "tiktok", "wikipedia", "translate", "calculator", "recipes",
        "pizza near me", "movie times", "nba scores", "bus schedule", "flights", "hotels",
        "homework help", "math games", "minecraft", "roblox", "fortnite", "pokemon", "lego",
        "cats", "dogs", "dinosaurs", "space facts", "volcano", "ocean animals", "music",
        "piano lessons", "drawing ideas", "science fair", "library", "school lunch", "bing",
        "duckduckgo", "github", "outlook", "zoom", "canvas", "khan academy", "quizlet", "kahoot",
    )

    data class Prompt(
        /** Exact string the user should type. */
        val word: String,
        /** Trial label stored with the recording (maps to TypedLabel). */
        val label: String,
    )

    fun next(rng: Random = Random.Default): Prompt {
        val roll = rng.nextFloat()
        fun target(w: String) = if (rng.nextFloat() < 1f / 3f) "$w.com" else w
        val word = when {
            roll < 0.20f -> target(GOOGLE)
            roll < 0.40f -> target(YAHOO)
            roll < 0.55f -> HARD_NEGATIVES[rng.nextInt(HARD_NEGATIVES.size)]
            else -> SEARCHES[rng.nextInt(SEARCHES.size)]
        }
        return Prompt(word, word)
    }

    fun matches(typed: CharSequence, target: String): Boolean =
        typed.toString().trim().equals(target, ignoreCase = true)
}
