package com.lorem.docklens.data

/**
 * Curated "recommended" repositories.
 *
 * Only the *repository id* is hard-coded. The concrete file name and download URL
 * are always resolved from the Hugging Face API at runtime, because pinning a file
 * name in source is exactly what produced dead links when a repo re-published its
 * bundles under a new name.
 *
 * Entries whose repository publishes no `.task` bundle are silently skipped, so an
 * entry going stale can never break the screen.
 */
data class CuratedModel(
    val repoId: String,
    val title: String,
    val provider: String,
    val blurb: String,
    /** Gated repos need an accepted licence + access token, otherwise HTTP 401. */
    val isGated: Boolean = false,
    val isReasoning: Boolean = false,
    /**
     * Ordered preference for picking one file out of a repo. Earlier patterns win;
     * ties are broken by the smallest file, which is what a phone wants.
     */
    val preferredFilePatterns: List<String> = listOf("q4", "int4", "q8")
)

object ModelCatalog {

    val curated: List<CuratedModel> = listOf(
        CuratedModel(
            repoId = "litert-community/Qwen2.5-1.5B-Instruct",
            title = "Qwen 2.5 1.5B Instruct",
            provider = "Alibaba",
            blurb = "Well-rounded assistant with strong summarisation. Great default for document Q&A.",
            preferredFilePatterns = listOf("q8", "q4")
        ),
        CuratedModel(
            repoId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            title = "DeepSeek R1 Distill Qwen 1.5B",
            provider = "DeepSeek",
            blurb = "Reasoning model that emits <think> steps, shown separately in chat.",
            isReasoning = true,
            preferredFilePatterns = listOf("q8", "q4")
        ),
        CuratedModel(
            repoId = "litert-community/Phi-4-mini-instruct",
            title = "Phi-4 Mini Instruct",
            provider = "Microsoft",
            blurb = "Small but strong at structured extraction from invoices and forms."
        ),
        CuratedModel(
            repoId = "litert-community/TinyLlama-1.1B-Chat-v1.0",
            title = "TinyLlama 1.1B Chat",
            provider = "TinyLlama",
            blurb = "Lightest option here. Fastest replies on mid-range devices."
        ),
        CuratedModel(
            repoId = "litert-community/SmolLM-135M-Instruct",
            title = "SmolLM 135M Instruct",
            provider = "Hugging Face",
            blurb = "Tiny footprint for quick tests and low-storage devices."
        ),
        CuratedModel(
            repoId = "litert-community/Falcon-E-1B-Instruct",
            title = "Falcon-E 1B Instruct",
            provider = "TII",
            blurb = "Compact instruction model with a permissive licence."
        ),
        CuratedModel(
            repoId = "litert-community/Gemma3-1B-IT",
            title = "Gemma 3 1B IT",
            provider = "Google",
            blurb = "Google's on-device model. Gated: accept the Gemma licence and add a " +
                "Hugging Face token before downloading.",
            isGated = true
        ),
        CuratedModel(
            repoId = "litert-community/Llama-3.2-1B-Instruct",
            title = "Llama 3.2 1B Instruct",
            provider = "Meta",
            blurb = "Meta's compact instruct model. Gated: accept the Llama licence and add a " +
                "Hugging Face token before downloading.",
            isGated = true
        )
    )

    val repoIds: List<String> = curated.map { it.repoId }

    fun findFor(repoId: String): CuratedModel? =
        curated.firstOrNull { it.repoId.equals(repoId, ignoreCase = true) }

    /**
     * Turns a fetched repository into the single recommended [LlmModel] for it.
     * Returns null when the repo has no runnable `.task` bundle.
     */
    fun toRecommendedModel(curatedModel: CuratedModel, group: HFRemoteModelGroup): LlmModel? {
        val best = pickBestFile(curatedModel, group) ?: return null
        return LlmModel(
            id = LlmModel.modelId(group.id, best.fileName),
            name = curatedModel.title,
            provider = curatedModel.provider,
            size = LlmModel.readableSize(best.size),
            description = curatedModel.blurb,
            downloadUrl = best.downloadUrl(group.id),
            format = ModelFormat.fromFileName(best.fileName),
            fileName = best.fileName,
            repoId = group.id,
            sizeBytes = best.size ?: 0L,
            isGated = curatedModel.isGated || group.isGated,
            isRecommended = true,
            isReasoningModel = curatedModel.isReasoning
        )
    }

    private fun pickBestFile(curatedModel: CuratedModel, group: HFRemoteModelGroup): HFSibling? {
        if (group.versionFiles.isEmpty()) return null
        return group.versionFiles.minWithOrNull(
            compareBy<HFSibling> { file ->
                val index = curatedModel.preferredFilePatterns.indexOfFirst { pattern ->
                    file.fileName.contains(pattern, ignoreCase = true)
                }
                if (index >= 0) index else curatedModel.preferredFilePatterns.size
            }.thenBy { it.size ?: Long.MAX_VALUE }
                .thenBy { it.fileName }
        )
    }
}

