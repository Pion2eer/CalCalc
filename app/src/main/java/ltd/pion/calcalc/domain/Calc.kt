package ltd.pion.calcalc.domain

import ltd.pion.calcalc.data.Profile
import java.time.LocalDate
import kotlin.math.roundToInt

object Norm {
    /**
     * Mifflin–St Jeor basal rate × 1.2 everyday activity, shifted by the goal. Workouts are added per day, not here.
     * To lose weight it takes away 20 % but at most 500 kcal, and never goes below 1200 kcal for women and 1500 for
     * men: a smaller, older person would otherwise get a norm too low to be safe.
     */
    fun daily(profile: Profile, weightKg: Double, today: LocalDate = LocalDate.now()): Int {
        val age = today.year - profile.birthYear
        val bmr = 10 * weightKg + 6.25 * profile.heightCm - 5 * age + if (profile.male) 5 else -161
        val tdee = bmr * 1.2
        val norm = when (profile.goal) {
            -1 -> tdee - minOf(500.0, tdee * 0.2)
            1 -> tdee + 300
            else -> tdee
        }
        return norm.roundToInt().coerceAtLeast(if (profile.male) 1500 else 1200)
    }
}

data class Activity(val name: String, val met: Double, val words: List<String>)

object Activities {
    // Approximate MET values after the Compendium of Physical Activities
    val all = listOf(
        Activity("Падел", 6.0, listOf("падел", "padel")),
        Activity("Теннис", 7.3, listOf("теннис")),
        Activity("Ходьба", 3.5, listOf("ходьба", "прогулка", "гулял", "шёл", "шел", "пешком")),
        Activity("Бег", 9.8, listOf("бег", "бегал", "пробежка")),
        Activity("Велосипед", 6.8, listOf("велосипед", "вело", "велик")),
        Activity("Плавание", 7.0, listOf("плавание", "плавал", "бассейн")),
        Activity("Тренажёрный зал", 5.0, listOf("зал", "тренажер", "тренажёр", "силовая", "качалка", "тренировк")),
        Activity("Футбол", 7.0, listOf("футбол")),
        Activity("Йога", 2.5, listOf("йога")),
        Activity("Танцы", 5.0, listOf("танцы", "танцевал")),
        Activity("Лыжи", 7.0, listOf("лыжи")),
        Activity("Уборка", 3.3, listOf("уборка", "убирался")),
    )

    /** Net burn: what the workout added on top of resting, so the basal rate is not counted twice. */
    fun netKcal(met: Double, weightKg: Double, minutes: Int, intensity: Double = 1.0): Int =
        ((met * intensity - 1.0) * weightKg * minutes / 60.0).roundToInt().coerceAtLeast(0)

    /** Net burn of walking by steps: about 0.0005 kcal per step and kilogram above resting (≈ 0.04 kcal a step at 80 kg). */
    fun stepsKcal(steps: Long, weightKg: Double): Int = (steps * weightKg * 0.0005).roundToInt()
}

sealed interface Parsed {
    /** "+350", "350 ккал", "-200" — a number of calories typed by hand. */
    data class Manual(val kcal: Int, val title: String) : Parsed
    data class Workout(val activity: Activity, val minutes: Int, val intensity: Double) : Parsed
    /** Anything else is food text for the AI. */
    data class Food(val text: String) : Parsed
}

object InputParser {
    private val manual = Regex("""^\s*([+-]?)\s*(\d{1,5})\s*(ккал|кал|kcal)?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val hours = Regex("""(\d+(?:[.,]\d+)?)\s*(ч|час|часа|часов|h)(?![а-яёa-z])""", RegexOption.IGNORE_CASE)
    private val minutes = Regex("""(\d+)\s*(м|мин|минут|минуты|min)(?![а-яёa-z])""", RegexOption.IGNORE_CASE)
    private val halfHour = Regex("""полчаса""", RegexOption.IGNORE_CASE)
    private val hourWord = Regex("""(^|\s)час(\s|$)""", RegexOption.IGNORE_CASE)

    fun parse(input: String): Parsed {
        val text = input.trim()
        manual.matchEntire(text)?.let { m ->
            val rest = m.groupValues[4].trim()
            val unit = m.groupValues[3].isNotEmpty()
            val sign = m.groupValues[1]
            // a bare number or "+/-N" or "N ккал ..." is a manual entry; "200 г овсянки" is food
            if (sign.isNotEmpty() || unit || rest.isEmpty()) {
                val n = m.groupValues[2].toInt()
                val kcal = if (sign == "-") -n else n
                val title = rest.ifEmpty { if (kcal < 0) "Расход вручную" else "Калории вручную" }
                return Parsed.Manual(kcal, title)
            }
        }
        val lower = text.lowercase()
        val activity = Activities.all.firstOrNull { a -> a.words.any { w -> Regex("""(^|[^а-яёa-z])$w""").containsMatchIn(lower) } }
        if (activity != null) {
            // "тренировка в зале" without a time is a usual session: an hour, half an hour of walking; the entry can be
            // edited. A longer text without a time may be food that only mentions the activity, so it goes to the AI.
            val min = durationMinutes(lower)
                ?: if (lower.split(Regex("""\s+""")).size <= 3) (if (activity.name == "Ходьба") 30 else 60) else null
            if (min != null) {
                val intensity = when {
                    listOf("легко", "спокойно", "медленно").any { it in lower } -> 0.8
                    listOf("тяжело", "интенсивно", "быстро").any { it in lower } -> 1.2
                    else -> 1.0
                }
                return Parsed.Workout(activity, min, intensity)
            }
        }
        return Parsed.Food(text)
    }

    private fun durationMinutes(lower: String): Int? {
        var total = 0.0
        var found = false
        hours.find(lower)?.let { total += it.groupValues[1].replace(',', '.').toDouble() * 60; found = true }
        minutes.find(lower)?.let { total += it.groupValues[1].toDouble(); found = true }
        if (!found && halfHour.containsMatchIn(lower)) { total = 30.0; found = true }
        if (!found && hourWord.containsMatchIn(lower)) { total = 60.0; found = true }
        return if (found && total > 0) total.roundToInt() else null
    }
}
