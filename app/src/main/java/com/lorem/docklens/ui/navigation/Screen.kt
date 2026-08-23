package com.lorem.docklens.ui.navigation

sealed class Screen(val route: String) {
    object OnboardingWelcome : Screen("onboarding_welcome")
    object OnboardingPrivacy : Screen("onboarding_privacy")
    object OnboardingAskAnything : Screen("onboarding_ask_anything")
    object AiSetup : Screen("ai_setup")
    object Home : Screen("home")
    object CameraScanner : Screen("camera_scanner")
    
    object Chat : Screen("chat/{documentId}") {
        fun createRoute(documentId: Long?) = "chat/${documentId ?: -1L}"
    }
    
    object SpecializedAnalysis : Screen("specialized/{type}/{documentId}") {
        fun createRoute(type: String, documentId: Long) = "specialized/$type/$documentId"
    }
    
    object CompareDocuments : Screen("compare/{docId1}/{docId2}") {
        fun createRoute(docId1: Long, docId2: Long) = "compare/$docId1/$docId2"
    }
}
