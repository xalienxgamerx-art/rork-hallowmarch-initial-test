package com.rork.hollowmarch.game

import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The wits and hands of the province's fighting creatures, one tick at a
 * time: detection under this light, the heart's retreat and pursuit, the
 * shield-bearer's own judgment, the archer's craft, and the closing blow.
 * The blows themselves are resolved by CombatSystem, the shots loosed by
 * RangedCombatSystem — this system decides, it does not roll the dice.
 */
class NpcSystem(private val engine: GameEngine, private val rng: Random) {

    fun update(dt: Float) {
        engine.map.entities.forEach { entity ->
            entity.hurtFlash = (entity.hurtFlash - dt * 2.2f).coerceAtLeast(0f)
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            if (entity.resident) return@forEach
            entity.attackCooldown = (entity.attackCooldown - dt).coerceAtLeast(0f)
            val dx = engine.camera.x - entity.x
            val dy = engine.camera.y - entity.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 12f) return@forEach
            engine.updateDetection(entity, dist, dt)
            // The blind do not chase, and the searching walk soft.
            if (entity.detection == Detection.UNAWARE) return@forEach
            // the heart under the hide: the hurt may break and run, and every
            // chase lasts only as long as the temper says it does
            entity.personality?.let { heart ->
                val retreat = heart.retreatHpFraction()
                if (retreat > 0f && entity.hp < entity.maxHp * retreat) {
                    val nx = entity.x - dx / dist * entity.speed * 1.15f * dt
                    val ny = entity.y - dy / dist * entity.speed * 1.15f * dt
                    if (!engine.map.isWall(nx, entity.y)) entity.x = nx
                    if (!engine.map.isWall(entity.x, ny)) entity.y = ny
                    if (dist > heart.pursueRadius()) entity.detection = Detection.SEARCHING
                    return@forEach
                }
                // a hot temper keeps the chase alive; a calm one lets it go
                if (dist > heart.pursueRadius()) {
                    entity.detection = Detection.SEARCHING
                    return@forEach
                }
            }
            // The shield-bearer's own judgment: cover on the approach, drop the guard to strike.
            val npcGuard = entity.equipment?.let { Shields.worn(it) }
            if (npcGuard != null) {
                val handling = Shields.handling(npcGuard)
                entity.blocking = Shields.shouldBlock(
                    true, entity.detection == Detection.AWARE, dist, entity.attackCooldown, handling
                )
            }
            // The board eases up into the guard and eases down out of it.
            val wantGuard = if (npcGuard != null && entity.blocking) 1f else 0f
            entity.guardRaise += (wantGuard - entity.guardRaise) * (dt * 9f).coerceIn(0f, 1f)
            val guardPace = if (entity.blocking && npcGuard != null) Shields.moveFactor(npcGuard) else 1f
            val pace = (if (entity.detection == Detection.SEARCHING) entity.speed * 0.6f else entity.speed) * guardPace

            // The archer's craft: hold the ground and loose, so long as arrows last.
            val theirArm = entity.equipment?.bestWeapon()
            if (theirArm != null && entity.ammoCount > 0 && entity.detection == Detection.AWARE &&
                dist in 2.2f..11f && Ranged.isRanged(theirArm.archetype) &&
                Ranged.formOf(theirArm).kind != RangedKind.THROWN
            ) {
                entity.facingAngle = atan2(dy, dx)
                if (entity.attackCooldown <= 0f) {
                    engine.ranged.npcLoose(entity, theirArm, dist)
                    entity.attackCooldown = (2.0f + rng.nextFloat() * 1.6f) *
                        (entity.personality?.attackCooldownScale() ?: 1f)
                }
                return@forEach
            }

            if (dist > 1.05f) {
                val nx = entity.x + dx / dist * pace * dt
                val ny = entity.y + dy / dist * pace * dt
                if (!engine.map.isWall(nx, entity.y)) entity.x = nx
                if (!engine.map.isWall(entity.x, ny)) entity.y = ny
                // A guard that moves watches where it goes; a braced board keeps its angle.
                if (!entity.blocking) entity.facingAngle = atan2(dy, dx)
                // The chase itself is a teacher.
                engine.trainNpc(entity, Skill.ATHLETICS, dt * 2f)
            } else if (entity.detection == Detection.AWARE && entity.attackCooldown <= 0f) {
                meleeAttack(entity)
            }
        }
    }

    /** A creature in reach takes its swing at the delver, by the same rules as yours. */
    private fun meleeAttack(entity: Entity) {
        entity.facingAngle = atan2(engine.camera.y - entity.y, engine.camera.x - entity.x)
        entity.attackCooldown = (1.5f + rng.nextFloat()) * (entity.personality?.attackCooldownScale() ?: 1f)
        // Its hand asks the same questions yours does: Finesse, Melee, the family, the piece.
        val theirWeapon = entity.equipment?.bestWeapon()
        val theirCategory = theirWeapon?.let { WeaponCategory.forWeapon(it.archetype) }
            ?: WeaponCategory.UNARMED
        val theirProficiency = entity.proficiencies.value(theirCategory)
        val theirMastery = theirWeapon?.let { entity.masteries.value(it.uid) } ?: 0
        val theirStats = entity.stats ?: StatBlock.balanced()
        val attacker = engine.combat.combatantFor(entity)
        val defender = engine.combat.combatantForPlayer(null)
        val outcome = engine.combat.resolveMelee(
            rng, attacker, defender,
            theirWeapon?.archetype?.damageType ?: DamageType.SHARP,
            hitChance = Derived.meleeAccuracy(theirStats, entity.skills, theirProficiency, theirMastery),
            rawDamage = { entity.damage + rng.nextInt(4) },
            critChance = null,
            sneak = false,
            soakWhenBlocked = false
        )
        // A missed swing still teaches hand and piece a little.
        engine.trainNpc(entity, Skill.MELEE, 1f)
        engine.trainNpcWeapon(entity, 0.5f, 0.25f)
        val weaponName = theirWeapon?.let { engine.styleRoster.nameFor(it) } ?: "bare hands"
        when (outcome.outcome) {
            StrikeOutcome.MISSED -> {
                engine.emit(GameEvent.StrikeMissed(false, entity.name, "you"))
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.DODGED -> {
                // You move before the edge arrives; what you wear has no say in a blow that lands on nothing.
                engine.emit(GameEvent.StrikeDodged(false, entity.name, "you"))
                engine.learnSkill(Skill.DEFENSE, 1.5f)
                engine.trainNpc(entity, Skill.MELEE, 1.5f)
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.BLOCKED -> {
                val guardDealt = outcome.damage
                engine.vitality -= guardDealt
                // A maul through the board shakes the arm that holds it.
                if (outcome.defenderBlockedFatigue > 0f) engine.drainFatigue(outcome.defenderBlockedFatigue)
                engine.learnSkill(Skill.DEFENSE, 1.5f + outcome.rawDamage * 0.3f)
                engine.emit(GameEvent.StrikeBlocked(false, entity.name, "you", outcome.guardName, outcome.blockZone, guardDealt))
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.HIT -> {}
        }
        val warded = engine.wardTimer > 0f
        var dealt = outcome.damage
        if (warded) dealt = (dealt * 0.45f).roundToInt().coerceAtLeast(1)
        engine.vitality -= dealt
        engine.learnSkill(Skill.DEFENSE, dealt * 0.9f)
        engine.trainNpc(entity, Skill.MELEE, 3f)
        engine.trainNpcWeapon(entity, 3f, 2f)
        engine.flashHurt()
        engine.emit(GameEvent.DamageDealt(false, entity.name, "you", dealt, warded = warded))
        engine.recordCombat(
            engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome, damageOverride = dealt)
        )
    }
}
