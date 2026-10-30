package ltd.pion.calcalc.domain

import ltd.pion.calcalc.data.Health
import ltd.pion.calcalc.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalcTest {
    @Test fun padelHourIsWorkout() {
        val p = InputParser.parse("падел 1 час") as Parsed.Workout
        assertEquals("Падел", p.activity.name)
        assertEquals(60, p.minutes)
        assertEquals(400, Activities.netKcal(p.activity.met, 80.0, p.minutes))
    }

    @Test fun minutesAndIntensity() {
        val p = InputParser.parse("Бег 45 мин тяжело") as Parsed.Workout
        assertEquals(45, p.minutes)
        assertEquals(1.2, p.intensity, 0.0)
        assertEquals(30, (InputParser.parse("полчаса ходьба") as Parsed.Workout).minutes)
    }

    @Test fun manualNumbers() {
        assertEquals(350, (InputParser.parse("350") as Parsed.Manual).kcal)
        assertEquals(-200, (InputParser.parse("-200") as Parsed.Manual).kcal)
        val m = InputParser.parse("+120 ккал печенье") as Parsed.Manual
        assertEquals(120, m.kcal)
        assertEquals("печенье", m.title)
    }

    @Test fun foodGoesToAi() {
        assertTrue(InputParser.parse("200 г овсянки с бананом") is Parsed.Food)
        assertTrue(InputParser.parse("борщ") is Parsed.Food)
        // a long text that only mentions an activity stays food
        assertTrue(InputParser.parse("салат и курица после зала вечером") is Parsed.Food)
    }

    @Test fun workoutWithoutTimeIsUsualSession() {
        assertEquals(60, (InputParser.parse("Тренировка в зале") as Parsed.Workout).minutes)
        assertEquals(30, (InputParser.parse("ходьба") as Parsed.Workout).minutes)
    }

    @Test fun mifflinNorm() {
        val p = Profile(male = true, birthYear = 1990, heightCm = 180, goal = 0)
        // 10*80 + 6.25*180 - 5*36 + 5 = 1750; ×1.2 = 2100
        assertEquals(2100, Norm.daily(p, 80.0, LocalDate.of(2026, 10, 28)))
    }

    @Test fun normNeverTooLow() {
        val p = Profile(male = false, birthYear = 1976, heightCm = 160, goal = -1)
        // 10*60 + 6.25*160 - 5*50 - 161 = 1189; ×1.2 = 1427; −20 % = 1141, raised to the 1200 floor
        assertEquals(1200, Norm.daily(p, 60.0, LocalDate.of(2026, 10, 28)))
    }

    @Test fun plainMacrosAreAUsualSplit() {
        val m = Norm.macros(Profile(), 80.0, 2100)
        // 1 g/kg protein = 80 g, 30 % fat = 70 g, the rest carbs = 288 g
        assertEquals(Macros(80, 70, 288), m)
    }

    @Test fun kidneysLimitProteinAndDiabetesCapsCarbs() {
        val m = Norm.macros(Profile(training = 1, health = setOf(Health.KIDNEYS, Health.DIABETES2)), 80.0, 2000)
        assertEquals(64, m.protein)
        assertEquals(200, m.carbs)
        assertTrue(kotlin.math.abs(2000 - (m.protein * 4 + m.fat * 9 + m.carbs * 4)) <= 10)
    }

    @Test fun activityRaisesTheNorm() {
        val p = Profile(male = true, birthYear = 1990, heightCm = 180, goal = 0, activity = 1)
        assertEquals(2406, Norm.daily(p, 80.0, LocalDate.of(2026, 10, 28)))
    }
}
