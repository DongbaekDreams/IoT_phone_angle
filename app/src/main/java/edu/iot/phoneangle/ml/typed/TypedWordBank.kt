package edu.iot.phoneangle.ml.typed

import kotlin.random.Random

/**
 * Prompt bank for gamified typing collection.
 * Draw: 25% google, 25% yahoo, 50% distractors (half of those are hard negatives).
 */
object TypedWordBank {

    const val GOOGLE = "google"
    const val YAHOO = "yahoo"

    /**
     * Hard negatives: similar length / site-like / overlapping prefixes with the targets.
     * These teach the model “other typing that is not google/yahoo”.
     */
    val HARD_NEGATIVES: List<String> = listOf(
        "gmail", "goggle", "goose", "gorgeous", "gospel", "gossip", "garage", "garden",
        "youtube", "yaho", "yummy", "yellow", "year", "young", "yoga",
        "bing", "baidu", "duck", "reddit", "twitch", "tiktok", "instagram", "facebook",
        "amazon", "netflix", "spotify", "discord", "github", "gitlab", "stackoverflow",
        "chrome", "safari", "firefox", "edge", "brave",
        "search", "browse", "login", "signup", "password", "username",
        "oggle", "ahoo", "googl", "yahooo",
    )

    /** Soft distractors (everyday words). */
    val OTHER_WORDS: List<String> = listOf(
        "apple", "orange", "banana", "grape", "lemon", "mango", "peach", "berry", "melon", "cherry",
        "river", "ocean", "lake", "storm", "cloud", "rain", "wind", "snow", "sunny", "foggy",
        "house", "table", "chair", "door", "window", "floor", "light", "clock", "phone", "paper",
        "green", "blue", "red", "black", "white", "yellow", "purple", "silver", "gold", "brown",
        "happy", "quiet", "loud", "quick", "slow", "bright", "dark", "soft", "hard", "smooth",
        "music", "dance", "sing", "paint", "draw", "write", "read", "learn", "teach", "study",
        "bread", "butter", "cheese", "pizza", "pasta", "salad", "soup", "coffee", "water", "juice",
        "tiger", "lion", "eagle", "horse", "dog", "cat", "bird", "fish", "shark", "whale",
        "north", "south", "east", "west", "city", "town", "road", "bridge", "park", "field",
        "summer", "winter", "spring", "autumn", "monday", "friday", "night", "morning", "today", "tomorrow",
    )

    data class Prompt(
        /** Exact string the user should type. */
        val word: String,
        /** Trial label stored with the recording (maps to TypedLabel). */
        val label: String,
    )

    fun next(rng: Random = Random.Default): Prompt {
        val roll = rng.nextFloat()
        return when {
            roll < 0.25f -> Prompt(GOOGLE, GOOGLE)
            roll < 0.50f -> Prompt(YAHOO, YAHOO)
            else -> {
                // Half of distractors are hard negatives (site-like / similar length).
                val word = if (rng.nextFloat() < 0.5f) {
                    HARD_NEGATIVES[rng.nextInt(HARD_NEGATIVES.size)]
                } else {
                    OTHER_WORDS[rng.nextInt(OTHER_WORDS.size)]
                }
                Prompt(word, word)
            }
        }
    }

    fun matches(typed: CharSequence, target: String): Boolean =
        typed.toString().trim().equals(target, ignoreCase = true)
}
