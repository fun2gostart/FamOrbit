package com.familycontrol.lab

import android.content.Context

data class CategoryDefinition(
    val categoryName: String,
    val description: String,
    val icon: String,
    val samplePackages: List<String>
)

object FamilyAgreement {

    val categories = listOf(
        CategoryDefinition("Learning & School", "Educational & school communication apps", "🎓", listOf("com.google.android.apps.docs", "com.duolingo")),
        CategoryDefinition("Entertainment & Games", "Social media, streaming, & gaming", "🎮", listOf("com.instagram.android", "com.jio.jioPlay.tv", "com.netflix.mediaclient")),
        CategoryDefinition("Safety & Essential", "Phone calls, SMS, & emergency services", "🛡️", listOf("com.android.dialer", "com.google.android.apps.messaging"))
    )

    val agreementRules = listOf(
        "1. Daily screen time limits are agreed together by parent and child.",
        "2. Bedtime routine (9:30 PM - 7:00 AM) keeps non-essential apps restricted for restful sleep.",
        "3. Completing daily homework & reading earns +15 extra reward minutes.",
        "4. Transparent, non-covert digital parenting: no keylogging or secret screenshots."
    )
}
