package com.familycontrol.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsEngineTest {

    @Test
    fun categorizeApp_socialApps_classifiedAsSocial() {
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.instagram.android"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.facebook.katana"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.snapchat.android"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.whatsapp"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("org.telegram.messenger"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.twitter.android"))
        assertEquals("Social", AnalyticsEngine.categorizeApp("com.discord"))
    }

    @Test
    fun categorizeApp_gamingApps_classifiedAsGaming() {
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.roblox.client"))
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.tencent.ig")) // pubg
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.mojang.minecraftpe"))
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.supercell.clashofclans"))
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.king.candycrushsaga"))
        assertEquals("Gaming", AnalyticsEngine.categorizeApp("com.kiloo.subwaysurf"))
    }

    @Test
    fun categorizeApp_entertainmentApps_classifiedAsEntertainment() {
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("in.startv.hotstar"))
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("com.netflix.mediaclient"))
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("com.google.android.youtube"))
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("com.spotify.music"))
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("com.amazon.avod.thirdpartyclient")) // prime
        assertEquals("Entertainment", AnalyticsEngine.categorizeApp("com.jio.media.ondemand"))
    }

    @Test
    fun categorizeApp_educationApps_classifiedAsEducation() {
        assertEquals("Education", AnalyticsEngine.categorizeApp("com.duolingo"))
        assertEquals("Education", AnalyticsEngine.categorizeApp("org.khanacademy.android"))
        assertEquals("Education", AnalyticsEngine.categorizeApp("org.coursera.android"))
        assertEquals("Education", AnalyticsEngine.categorizeApp("com.byjus.thelearningapp"))
    }

    @Test
    fun categorizeApp_genericApps_classifiedAsProductivityAndOther() {
        assertEquals("Productivity & Other", AnalyticsEngine.categorizeApp("com.google.android.calculator"))
        assertEquals("Productivity & Other", AnalyticsEngine.categorizeApp("com.android.settings"))
        assertEquals("Productivity & Other", AnalyticsEngine.categorizeApp("com.android.camera"))
    }

    @Test
    fun categoryBudgetEngine_delegatesToAnalyticsEngine() {
        assertEquals("Social", CategoryBudgetEngine.getCategoryForPackage("com.instagram.android"))
        assertEquals("Gaming", CategoryBudgetEngine.getCategoryForPackage("com.roblox.client"))
        assertEquals("Entertainment", CategoryBudgetEngine.getCategoryForPackage("com.netflix.mediaclient"))
        assertEquals("Education", CategoryBudgetEngine.getCategoryForPackage("com.duolingo"))
    }
}
