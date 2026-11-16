package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.TouchLabel

/**
 * QWERTY geometry in keyboard-normalized coordinates (x: 0..1 left→right, y: 0..1 top→bottom).
 * The in-app training keyboard draws exactly this layout, and it is close to Gboard / Samsung
 * portrait layouts, so tap positions learned on one phone mean the same thing on another.
 */
object KeyboardLayout {

    const val ROWS = 4
    const val KEY_W = 0.1f
    const val KEY_H = 1f / ROWS

    data class Key(val label: String, val cx: Float, val cy: Float, val width: Float)

    private val ROW_1 = "qwertyuiop"
    private val ROW_2 = "asdfghjkl"
    private val ROW_3 = "zxcvbnm"

    val keys: List<Key> = buildList {
        ROW_1.forEachIndexed { i, c -> add(Key(c.toString(), (i + 0.5f) * KEY_W, rowY(0), KEY_W)) }
        ROW_2.forEachIndexed { i, c -> add(Key(c.toString(), (i + 1.0f) * KEY_W, rowY(1), KEY_W)) }
        // Row 3: 1.5-wide shift on the left, z..m, 1.5-wide backspace on the right.
        ROW_3.forEachIndexed { i, c -> add(Key(c.toString(), (i + 2.0f) * KEY_W, rowY(2), KEY_W)) }
        add(Key(TouchLabel.KEY_BACKSPACE, 0.925f, rowY(2), 0.15f))
        // Row 4 (Gboard-style): ?123 | , | space | . | enter/search.
        add(Key(",", 0.2f, rowY(3), 0.1f))
        add(Key(TouchLabel.KEY_SPACE, 0.5f, rowY(3), 0.5f))
        add(Key(".", 0.8f, rowY(3), 0.1f))
        add(Key(TouchLabel.KEY_ENTER, 0.925f, rowY(3), 0.15f))
    }

    private val byLabel: Map<String, Key> = keys.associateBy { it.label }

    fun rowY(row: Int): Float = (row + 0.5f) * KEY_H

    fun key(label: String): Key? = byLabel[label.lowercase()]

    /** Key centres for a typed string; unknown characters are dropped. */
    fun path(word: String): List<Key> = word.lowercase().mapNotNull { key(it.toString()) }

    /** Nearest key to a normalized position (row first, then column). */
    fun keyAt(x: Float, y: Float): Key {
        val row = (y / KEY_H).toInt().coerceIn(0, ROWS - 1)
        val rowY = rowY(row)
        return keys.filter { it.cy == rowY }.minBy { kotlin.math.abs(it.cx - x) }
    }

    /**
     * Rough English letter frequencies for the background ("any other query") model.
     * Space / enter / backspace / '.' get small fixed shares.
     */
    val backgroundPrior: Map<String, Float> by lazy {
        val freq = mapOf(
            "e" to 12.0f, "t" to 9.1f, "a" to 8.2f, "o" to 7.5f, "i" to 7.0f, "n" to 6.7f,
            "s" to 6.3f, "h" to 6.1f, "r" to 6.0f, "d" to 4.3f, "l" to 4.0f, "c" to 2.8f,
            "u" to 2.8f, "m" to 2.4f, "w" to 2.4f, "f" to 2.2f, "g" to 2.0f, "y" to 2.0f,
            "p" to 1.9f, "b" to 1.5f, "v" to 1.0f, "k" to 0.8f, "j" to 0.15f, "x" to 0.15f,
            "q" to 0.1f, "z" to 0.07f,
            TouchLabel.KEY_SPACE to 6f, TouchLabel.KEY_BACKSPACE to 2f,
            TouchLabel.KEY_ENTER to 2f, "." to 1f, "," to 0.5f,
        )
        val total = freq.values.sum()
        freq.mapValues { it.value / total }
    }
}
