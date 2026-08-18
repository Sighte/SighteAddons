package sighteaddons

/**
 * Port of the legacy dungeon score calculator from the upstream mod.
 *
 * The original implementation was a static Java scorer that took the dungeon tab and sidebar state and
 * derived a per-floor score out of time, exploration, skill and bonus totals. This Kotlin version keeps
 * the same formula and rounding rules, but exposes the important steps as pure functions so they can be
 * tested without a live Minecraft client.
 */
object DungeonScore {
    enum class FloorRequirement(val percentage: Int, val timeLimit: Int) {
        E(30, 1200),
        F1(30, 600),
        F2(40, 600),
        F3(50, 600),
        F4(60, 720),
        F5(70, 600),
        F6(85, 720),
        F7(100, 840),
        M1(100, 480),
        M2(100, 480),
        M3(100, 480),
        M4(100, 480),
        M5(100, 480),
        M6(100, 600),
        M7(100, 840),
        NONE(0, 0);
    }

    fun requirementFor(floor: String?): FloorRequirement {
        if (floor == null) return FloorRequirement.NONE
        val normalized = floor.replace("Entrance", "E")
        return FloorRequirement.values().firstOrNull { it.name == normalized } ?: FloorRequirement.NONE
    }

    fun calculateTotal(
        timeScore: Int,
        exploreScore: Int,
        skillScore: Int,
        bonusScore: Int,
        isEntrance: Boolean,
    ): Int {
        if (isEntrance) {
            return Math.round(timeScore * 0.7f) +
                Math.round(exploreScore * 0.7f) +
                Math.round(skillScore * 0.7f) +
                Math.round(bonusScore * 0.7f)
        }
        return timeScore + exploreScore + skillScore + bonusScore
    }

    fun calculateSkillScore(
        totalRooms: Int,
        completedRooms: Int,
        extraCompletedRooms: Int,
        puzzleCount: Int,
        completedPuzzleCount: Int,
    ): Int {
        val completedRoomScore = if (totalRooms != 0) {
            (80.0 * (completedRooms + extraCompletedRooms) / totalRooms).toInt()
        } else 0
        val bounded = completedRoomScore.coerceIn(0, 80)
        val penalty = ((puzzleCount - completedPuzzleCount).coerceAtLeast(0)) * 10
        return 20 + (bounded - penalty).coerceIn(0, 80)
    }

    fun calculateExploreScore(
        totalRooms: Int,
        completedRooms: Int,
        extraCompletedRooms: Int,
        secretsPercent: Double,
        requiredPercent: Int,
    ): Int {
        val completedRoomScore = if (totalRooms != 0) {
            (60.0 * (completedRooms + extraCompletedRooms) / totalRooms).toInt()
        } else 0
        val boundedRooms = completedRoomScore.coerceIn(0, 60)
        val secretsScore = (40 * minOf(requiredPercent.toDouble(), secretsPercent) /
            maxOf(1, requiredPercent).toDouble()).toInt().coerceIn(0, 40)
        return boundedRooms + secretsScore
    }

    fun calculateTimeScore(
        requirement: FloorRequirement,
        startedAtMs: Long,
        nowMs: Long,
    ): Int {
        if (requirement == FloorRequirement.NONE || startedAtMs == 0L) return 100

        val timeSpent = ((nowMs - startedAtMs) / 1000).toInt()
        if (timeSpent < requirement.timeLimit) return 100

        val timePastReq = (((timeSpent - requirement.timeLimit).toDouble() / requirement.timeLimit) * 100.0)

        var score = 100
        when {
            timePastReq < 20 -> score -= (timePastReq / 2.0).toInt()
            timePastReq < 40 -> score -= (10 + (timePastReq - 20) / 4.0).toInt()
            timePastReq < 50 -> score -= (15 + (timePastReq - 40) / 5.0).toInt()
            timePastReq < 60 -> score -= (17 + (timePastReq - 50) / 6.0).toInt()
            else -> score -= (18.67 + (timePastReq - 60) / 7.0).toInt()
        }
        return score.coerceAtLeast(0)
    }

    fun calculateBonusScore(
        crypts: Int,
        mimicKilled: Boolean,
        princeKilled: Boolean,
        quizCompleted: Boolean,
    ): Int {
        var bonus = 0
        bonus += crypts.coerceAtMost(5)
        if (mimicKilled) bonus += 2
        if (princeKilled) bonus += 1
        if (quizCompleted) bonus += 5
        return bonus
    }
}
