# Project Plan

App Name: DocLens. Compose multiplatform app for private, on-device multimodal document intelligence assistant: the user gives it almost any personal document, and the app helps them understand it without uploading the document to a cloud LLM. 

Local LLM model selection 

System design - 
App Name DocLens
│
├── 00 Design System
│
├── 01 Onboarding
│   ├── Welcome
│   ├── Privacy
│   └── Ask Anything
│
├── 02 AI Setup
│   ├── Choose Mode
│   ├── Choose LLM
│   └── Download Model
│
├── 03 Core App
│   ├── Home
│   ├── Import
│   ├── Processing
│   ├── Document Overview
│   └── AI Chat
│
├── 04 Specialized
│   ├── Important Points
│   ├── Document Viewer
│   ├── Medical Report
│   ├── X-Ray
│   └── Compare Documents
│
└── 05 Components
    ├── Buttons
    ├── Cards
    ├── Chips
    ├── Inputs
    ├── Loading
    ├── Empty State
    └── Error State

## Project Brief

# DocLens Project Brief

DocLens is a private, on-device multimodal document intelligence assistant. It enables users to gain insights from sensitive personal documents—ranging from text-heavy contracts to medical reports and X-rays—without ever uploading data to the cloud.

## Features

*   **Private Local AI Processing**: Executes multimodal Large Language Models (LLMs) entirely on-device, ensuring that personal documents remain confidential and never leave the hardware.
*   **Multimodal Document Capture**: Leverages the device camera to scan physical documents or import digital files (PDFs, images) for immediate processing.
*   **Conversational Document Intelligence**: Provides an interactive chat interface where users can ask questions, request summaries, or extract specific data points from their documents.
*   **Specialized Analysis Modules**: Features dedicated pipelines for interpreting complex visual data, such as medical report text extraction and X-ray image analysis.

## High-Level Technical Stack

*   **Kotlin**: The core language for robust and expressive application logic.
*   **Jetpack Compose**: A modern, declarative UI toolkit used to implement a vibrant Material Design 3 interface.
*   **Google AI Edge / MediaPipe**: The engine for local LLM inference and multimodal vision tasks.
*   **Kotlin Coroutines & Flow**: Used for managing high-performance asynchronous tasks like model inference and document processing.
*   **CameraX**: Provides a reliable and consistent camera experience for document scanning.
*   **KSP (Kotlin Symbol Processing)**: Utilized for efficient code generation (e.g., for JSON parsing with Moshi).
*   **Retrofit & OkHttp**: Handles the secure downloading of AI models during the initial setup phase.

## Implementation Steps

### Task_1_Base_UI_and_Onboarding: Establish the Material Design 3 foundation (vibrant theme, edge-to-edge) and implement the Onboarding flow (Welcome, Privacy, Ask Anything screens) with Compose Navigation.
- **Status:** COMPLETED
- **Updates:** Established the Material Design 3 foundation with a vibrant teal and cyan palette. Enabled full edge-to-edge display. Implemented the Onboarding flow (Welcome, Privacy, Ask Anything) using Compose Navigation. Created a placeholder Home screen. Integrated an adaptive app icon. Updated project SDKs to 37 for compatibility. Verified build success.
- **Acceptance Criteria:**
  - M3 vibrant theme implemented with light/dark support
  - Full edge-to-edge display enabled
  - Onboarding navigation flow works correctly
  - UI follows Material Design 3 guidelines

### Task_2_Document_Capture_and_Persistence: Implement document acquisition using CameraX for scanning and a system file picker for PDF/Image imports. Setup Room database to store document metadata and local file URIs.
- **Status:** COMPLETED
- **Updates:** Implemented document persistence using Room (DocumentEntity, Dao, Repository). Created a dedicated CameraX scanner screen with live preview and capture functionality. Integrated a file picker for PDF and Image imports. Developed the Home screen to display a list of acquired documents with real-time updates via HomeViewModel. Handled runtime permissions for the camera. Maintained the M3 vibrant theme and edge-to-edge display. Verified build and functional navigation.
- **Acceptance Criteria:**
  - CameraX scanner captures and saves images locally
  - PDF and Image import via file picker works
  - Room database correctly stores and retrieves document entries
  - Permissions handled correctly for Camera and Storage

### Task_3_Local_AI_Inference_Core: Integrate MediaPipe/Google AI Edge for on-device LLM inference. Implement model downloading logic using Retrofit and a local model manager for setup and initialization.
- **Status:** COMPLETED
- **Updates:** Integrated MediaPipe GenAI for local LLM inference. Implemented AiModelManager for handling model downloads and initialization. Created AiSetupScreen for user-driven model downloading and verification. Configured navigation to ensure AI models are present before accessing the main app. Handled inference on background threads with proper error management. Verified build success.
- **Acceptance Criteria:**
  - MediaPipe/Google AI Edge dependencies added
  - AI models can be downloaded and stored on-device
  - Basic local LLM inference works with text input
  - Model initialization handles errors gracefully
- **Duration:** N/A

### Task_4_Chat_and_Specialized_Analysis: Develop the AI Chat interface for document interaction and specialized analysis modules for Medical Reports and X-rays (vision-based). Implement Document Overview and Compare features.
- **Status:** IN_PROGRESS
- **Acceptance Criteria:**
  - Interactive AI Chat interface functional with local LLM
  - Specialized vision/text analysis for Medical/X-ray documents works
  - Document comparison logic implemented
  - Multimodal input (image + text) processed correctly
- **StartTime:** 2026-08-21 22:39:36 IST

### Task_5_Assets_and_Verification: Create a high-quality adaptive app icon, finalize the energetic color scheme, and perform a full app verification run to ensure stability and requirement alignment.
- **Status:** PENDING
- **Acceptance Criteria:**
  - Adaptive app icon matches DocLens brand
  - App builds and runs without crashes
  - All existing tests pass
  - Critic_agent verifies stability and UI alignment

