package org.dicio.skill.standard

import org.dicio.skill.skill.Skill
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillGrammar
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.Specificity

abstract class StandardRecognizerSkill<T>(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<T>,
    // do not manually specify a specificity here, but rather let it be taken from `data`,
    // unless you want to override the one in `data` for some specific reason
    specificity: Specificity = data.specificity,
) : Skill<T>(correspondingSkillInfo, specificity) {

    /**
     * The grammar of this skill's own generated sentences. Subclasses that also match words their
     * sentences don't spell out override [grammar] and add those on top of this.
     */
    protected val sentencesGrammar: SkillGrammar
        get() = data.grammar

    // the words of the generated sentences are exactly what this skill can understand
    override val grammar: SkillGrammar
        get() = sentencesGrammar

    override fun score(ctx: SkillContext, input: String): Pair<Score, T> {
        return data.score(ctx, input)
    }
}
