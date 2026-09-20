package com.rork.hollowmarch.game

/**
 * What happened in the fight, told as facts rather than as words. The systems
 * report what they did; the engine alone decides what the log says. No system
 * below the engine knows how a line of prose is built.
 */
sealed interface GameEvent {
    /** A free-standing line of flavor: sparks on a wall, a shot in the dirt. */
    data class Note(val text: String) : GameEvent

    /** A swing that found only air. */
    data class StrikeMissed(val byPlayer: Boolean, val attacker: String, val defender: String) : GameEvent

    /** A body moved before the edge arrived. */
    data class StrikeDodged(val byPlayer: Boolean, val attacker: String, val defender: String) : GameEvent

    /** A raised board took the blow: the board's name, where it landed, what got through. */
    data class StrikeBlocked(
        val byPlayer: Boolean,
        val attacker: String,
        val defender: String,
        val guard: String,
        val zone: BlockZone,
        val dealt: Int
    ) : GameEvent

    /** Harm landed: the words follow whether the blow was sneaked, blessed, or warded. */
    data class DamageDealt(
        val byPlayer: Boolean,
        val attacker: String,
        val defender: String,
        val amount: Int,
        val sneak: Boolean = false,
        val critical: Boolean = false,
        val warded: Boolean = false
    ) : GameEvent

    /** A body came apart and did not rise. */
    data class EntityKilled(
        val defender: String,
        val klassName: String?,
        val level: Int,
        val lessons: Int
    ) : GameEvent

    /** A piece spoke: powder thunder or a string's snap, and whether it ran dry. */
    data class ProjectileFired(
        val weapon: String,
        val gunpowder: Boolean,
        val spent: Boolean,
        val crossbow: Boolean
    ) : GameEvent
}
