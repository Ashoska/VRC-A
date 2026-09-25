package com.vrca.discordbot

/**
 * The one content boundary, enforced in code: Cardinal never keeps a trait or server memory about hating a
 * group of people. The learner prompt asks for this too, but the small model can ignore it ("French hater"
 * from two people joking "cardinal hates french people"). Edgy humour in messages is untouched — this only
 * stops it becoming part of who he is or what the server "remembers".
 */
object ContentBoundary {
    private val HOSTILE = Regex(
        "(?i)(\\bhat(e|es|ed|er|ers|ing)\\b|\\banti-|\\bdespis\\w*|\\bloath\\w*|\\bdetest\\w*|\\bracis\\w*|\\bbigot\\w*|" +
            "\\bcan'?t stand\\b|\\bdisgust\\w*|\\bexterminat\\w*|\\bkill(s|ing)?\\b|\\bgenocid\\w*)")
    private val GROUP = Regex(
        "(?i)\\b(french|english|british|german|dutch|irish|scottish|welsh|spanish|italian|portuguese|russian|ukrainian|" +
            "polish|chinese|japanese|korean|indian|pakistani|arabs?|arabic|africans?|mexicans?|americans?|canadians?|" +
            "australians?|brazilians?|turkish|turks?|persians?|iranians?|israelis?|palestinians?|filipinos?|vietnamese|" +
            "thai|asians?|europeans?|latinos?|latinas?|hispanics?|black people|black folks|blacks|white people|whites|" +
            "jews?|jewish|muslims?|islam\\w*|christians?|catholics?|hindus?|sikhs?|buddhists?|atheists?|gays?|lesbians?|" +
            "trans|transgender|queer|bisexuals?|lgbt\\w*|women|woman|girls|men|immigrants?|refugees?|foreigners?|" +
            "disabled|autistic|\\w+(ish|ese|ian|ians) people)\\b")

    /** True for text that casts hate at a group of people ("French hater", "anti-muslim", "hates gay people"). */
    fun hatefulAboutGroup(text: String): Boolean = HOSTILE.containsMatchIn(text) && GROUP.containsMatchIn(text)
}
