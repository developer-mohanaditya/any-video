package io.anyvideo.downloader.data

data class CaptionGroup(
    val title: String,
    val captions: List<String>,
)

data class HashtagGroup(
    val title: String,
    val tags: List<String>,
)

/**
 * The original pulls hashtags from `get_hashtag.php?cat=` on its own server and
 * ships captions locally. The rebuild keeps a bundled, editable catalog so the
 * tools work offline and without a third-party service.
 */
object Catalog {

    val captions = listOf(
        CaptionGroup(
            "Aesthetic",
            listOf(
                "Soft light, slow mornings, and a mind that finally rests.",
                "Collecting quiet moments like they are rare coins.",
                "Some days are for chasing light, others for letting it find you.",
                "A little chaos, a little calm, and a lot of coffee.",
                "Making peace with the in-between.",
            ),
        ),
        CaptionGroup(
            "Travel",
            listOf(
                "New streets, same curiosity.",
                "Passport full of stamps, head full of stories.",
                "Took the long way and found the better view.",
                "Every city teaches you a different version of yourself.",
                "Wander far enough and home starts to follow you.",
            ),
        ),
        CaptionGroup(
            "Friends",
            listOf(
                "Same chaos, different day, still my favourite people.",
                "The kind of friends who make ordinary evenings feel like events.",
                "Good company turns any place into a memory.",
                "Group photos always miss the joke, never the feeling.",
                "We laugh in the same language.",
            ),
        ),
        CaptionGroup(
            "Fitness",
            listOf(
                "Progress is quiet, but it shows up.",
                "Showed up for myself today.",
                "Strong body, softer mind.",
                "Consistency beats intensity, every single week.",
                "Earning the rest I am about to take.",
            ),
        ),
        CaptionGroup(
            "Business",
            listOf(
                "Building in public, learning in private.",
                "Small steps compound faster than big promises.",
                "The work is the marketing.",
                "Shipping beats perfecting.",
                "Today's boring task is tomorrow's unfair advantage.",
            ),
        ),
        CaptionGroup(
            "Short",
            listOf(
                "Here for it.",
                "Mood.",
                "Unbothered.",
                "Currently recharging.",
                "Main character energy.",
            ),
        ),
    )

    val hashtags = listOf(
        HashtagGroup(
            "Reels",
            listOf("#reels", "#reelsindia", "#reelsinstagram", "#trendingreels", "#explorepage", "#viralreels"),
        ),
        HashtagGroup(
            "Travel",
            listOf("#travel", "#wanderlust", "#travelgram", "#explore", "#roadtrip", "#travelphotography"),
        ),
        HashtagGroup(
            "Photography",
            listOf("#photooftheday", "#photography", "#picoftheday", "#portrait", "#goldenhour", "#aesthetic"),
        ),
        HashtagGroup(
            "Fitness",
            listOf("#fitness", "#gym", "#workout", "#fitfam", "#training", "#healthylifestyle"),
        ),
        HashtagGroup(
            "Business",
            listOf("#entrepreneur", "#startup", "#buildinpublic", "#smallbusiness", "#marketing", "#hustle"),
        ),
        HashtagGroup(
            "Growth",
            listOf("#motivation", "#mindset", "#selfgrowth", "#discipline", "#goals", "#dailyinspiration"),
        ),
    )
}
