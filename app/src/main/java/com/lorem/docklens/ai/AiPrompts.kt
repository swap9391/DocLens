package com.lorem.docklens.ai

object AiPrompts {
    fun getMedicalReportPrompt(content: String) = """
        You are a medical document assistant. Analyze the following medical report text and provide:
        1. Key Findings: Summary of important observations.
        2. Simple Explanation: Explain findings in simple terms for a non-medical professional.
        3. Actionable Advice: What questions should the user ask their doctor?
        
        Report Content:
        $content
    """.trimIndent()

    fun getXRayAnalysisPrompt(ocrContent: String) = """
        You are a radiologist's assistant. Based on the OCR text from an X-ray report, provide:
        1. Area Imaged: What part of the body was scanned?
        2. Observations: What are the main findings?
        3. Impression: What is the overall conclusion?
        
        OCR Text:
        $ocrContent
    """.trimIndent()

    fun getComparisonPrompt(doc1Name: String, doc1Content: String, doc2Name: String, doc2Content: String) = """
        Compare the following two documents:
        Document 1: $doc1Name
        Content: $doc1Content
        
        Document 2: $doc2Name
        Content: $doc2Content
        
        Provide:
        1. Key Similarities: What do both documents agree on?
        2. Major Differences: What has changed or is different?
        3. Overall Comparison: A brief summary of how they relate to each other.
    """.trimIndent()

    fun getChatPrompt(history: String, userMessage: String, documentContext: String?) = """
        You are DocLens, a private document intelligence assistant. 
        Context from attached document: ${documentContext ?: "None"}
        
        Conversation History:
        $history
        
        User: $userMessage
        Assistant:
    """.trimIndent()
}
