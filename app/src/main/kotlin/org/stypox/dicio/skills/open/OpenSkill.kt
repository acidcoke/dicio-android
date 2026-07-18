package org.stypox.dicio.skills.open

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.dicio.numbers.unit.Number
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Open
import org.stypox.dicio.util.StringUtils

class OpenSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Open>)
    : StandardRecognizerSkill<Open>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Open): SkillOutput {
        val userAppName = when (inputData) {
            is Open.Query -> inputData.what?.trim { it <= ' ' }
        }
        val packageManager: PackageManager = ctx.android.packageManager
        val forms = userAppName?.takeIf { it.isNotEmpty() }?.let { phoneticForms(ctx, it) }
        val applicationInfo = forms?.let { getMostSimilarApp(packageManager, it) }

        if (applicationInfo != null) {
            val launchIntent: Intent =
                packageManager.getLaunchIntentForPackage(applicationInfo.packageName)!!
            launchIntent.action = Intent.ACTION_MAIN
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.android.startActivity(launchIntent)
        }

        return OpenOutput(
            appName = applicationInfo?.loadLabel(packageManager)?.toString() ?: userAppName,
            packageName = applicationInfo?.packageName,
        )
    }

    companion object {
        /**
         * The collapsed forms of [appName] to try when matching against app labels. Brand/alphanumeric
         * app names are out-of-vocabulary for Vosk, which spells them phonetically: "whatsapp" becomes
         * "what's app", "c24" becomes "see twenty four". So besides the raw text we also produce a
         * variant with spoken numbers turned into digits and single letter-names turned into letters.
         */
        private fun phoneticForms(ctx: SkillContext, appName: String): List<String> {
            val withDigits = numberWordsToDigits(ctx, appName)
            val withLetters = withDigits
                .split(WHITESPACE)
                .joinToString(" ") { LETTER_HOMOPHONES[it.lowercase()] ?: it }
            return listOf(appName, withDigits, withLetters)
                .map { collapse(it) }
                .filter { it.length >= 2 }
                .distinct()
        }

        /** Uses the numbers library to turn spoken numbers into digits, e.g. "twenty four" -> "24". */
        private fun numberWordsToDigits(ctx: SkillContext, text: String): String {
            val parsed = ctx.parserFormatter?.extractNumber(text)?.parseMixedWithText() ?: return text
            return parsed.joinToString(" ") { token ->
                if (token is Number) {
                    if (token.isDecimal) token.decimalValue().toString() else token.integerValue().toString()
                } else {
                    token.toString()
                }
            }
        }

        private fun getMostSimilarApp(
            packageManager: PackageManager,
            targets: List<String>,
        ): ApplicationInfo? {
            if (targets.isEmpty()) return null
            val resolveInfosIntent = Intent(Intent.ACTION_MAIN, null)
            resolveInfosIntent.addCategory(Intent.CATEGORY_LAUNCHER)

            @SuppressLint("QueryPermissionsNeeded") // we need to query all apps
            val resolveInfos: List<ResolveInfo> =
                packageManager.queryIntentActivities(resolveInfosIntent, 0)

            // some app names are out-of-vocabulary for Vosk and decode to an unrelated real word
            // (e.g. "aegis" -> "acres"); for those, an explicit alias maps the mis-hearing straight
            // to the right package, bypassing fuzzy matching
            val aliasPackage = targets.firstNotNullOfOrNull { APP_ALIASES[it] }
            if (aliasPackage != null) {
                resolveInfos.firstOrNull { it.activityInfo.packageName == aliasPackage }?.let {
                    try {
                        return packageManager.getApplicationInfo(aliasPackage, PackageManager.GET_META_DATA)
                    } catch (ignored: PackageManager.NameNotFoundException) {
                    }
                }
            }

            var bestDistance = Int.MAX_VALUE
            var bestApplicationInfo: ApplicationInfo? = null

            for (resolveInfo in resolveInfos) {
                try {
                    val currentApplicationInfo: ApplicationInfo = packageManager.getApplicationInfo(
                        resolveInfo.activityInfo.packageName, PackageManager.GET_META_DATA
                    )
                    val label = collapse(
                        packageManager.getApplicationLabel(currentApplicationInfo).toString()
                    )
                    if (label.isEmpty()) continue

                    // customStringDistance rewards matching/subsequent chars (so a good match is very
                    // negative); take the best-scoring of the normalized spoken forms against the label
                    val distance = targets.minOf { target ->
                        StringUtils.customStringDistance(target, label)
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestApplicationInfo = currentApplicationInfo
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
            return if (bestDistance > 5) null else bestApplicationInfo
        }

        /** Lowercases and keeps only letters/digits, dropping spaces and punctuation. */
        private fun collapse(text: String): String =
            text.lowercase().replace(NON_ALNUM, "")

        private val WHITESPACE = Regex("\\s+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]")

        // explicit overrides for app names Vosk reliably mis-transcribes to an unrelated real word;
        // keys are the collapsed mis-hearing (see [collapse]/[phoneticForms]), values are packages
        private val APP_ALIASES: Map<String, String> = mapOf(
            "acres" to "com.beemdevelopment.aegis", // "aegis" decodes to "acres"
            "ages" to "com.beemdevelopment.aegis", // "aegis" also decodes to "ages"
        )

        // how Vosk tends to spell out single spoken letters, so "see"/"sea" -> "c", "you" -> "u", …
        private val LETTER_HOMOPHONES: Map<String, String> = mapOf(
            "ay" to "a", "bee" to "b", "be" to "b", "see" to "c", "sea" to "c", "cee" to "c",
            "dee" to "d", "ee" to "e", "ef" to "f", "eff" to "f", "gee" to "g", "aitch" to "h",
            "jay" to "j", "kay" to "k", "el" to "l", "ell" to "l", "em" to "m", "en" to "n",
            "oh" to "o", "pee" to "p", "pea" to "p", "cue" to "q", "queue" to "q", "are" to "r",
            "ar" to "r", "es" to "s", "ess" to "s", "tee" to "t", "tea" to "t", "you" to "u",
            "vee" to "v", "ex" to "x", "eks" to "x", "why" to "y", "wye" to "y", "zee" to "z",
            "zed" to "z",
        )
    }
}
