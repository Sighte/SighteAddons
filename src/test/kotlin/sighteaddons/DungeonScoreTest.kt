package sighteaddons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DungeonScoreTest {
    @Test
    fun `floor requirements match the original mod`() {
        assertEquals(100, DungeonScore.requirementFor("F7").percentage)
        assertEquals(840, DungeonScore.requirementFor("F7").timeLimit)
        assertEquals(100, DungeonScore.requirementFor("M7").percentage)
        assertEquals(840, DungeonScore.requirementFor("M7").timeLimit)
        assertEquals(DungeonScore.FloorRequirement.NONE, DungeonScore.requirementFor("unknown"))
    }

    @Test
    fun `calculateScore keeps the original composition and clamping`() {
        val timeScore = DungeonScore.calculateTimeScore(DungeonScore.requirementFor("F5"), startedAtMs = 0L, nowMs = 1_000L)
        val skillScore = DungeonScore.calculateSkillScore(
            totalRooms = 20,
            completedRooms = 18,
            extraCompletedRooms = 0,
            puzzleCount = 1,
            completedPuzzleCount = 1,
        )
        val exploreScore = DungeonScore.calculateExploreScore(
            totalRooms = 20,
            completedRooms = 18,
            extraCompletedRooms = 0,
            secretsPercent = 50.0,
            requiredPercent = 70,
        )
        val bonusScore = DungeonScore.calculateBonusScore(crypts = 5, mimicKilled = false, princeKilled = false, quizCompleted = false)

        assertEquals(100, timeScore, "F5 still gets full time score when the run is within the limit")
        assertEquals(92, skillScore, "80% completion with no puzzle penalty stays capped at 92")
        assertEquals(82, exploreScore, "room clear and 50% secret progress match the original formula")
        assertEquals(5, bonusScore)

        val total = DungeonScore.calculateTotal(
            timeScore = timeScore,
            exploreScore = exploreScore,
            skillScore = skillScore,
            bonusScore = bonusScore,
            isEntrance = false,
        )
        assertEquals(279, total)
        assertTrue(total > 200)
    }
}
