# Video to Study PDF

**Video to Study PDF** is an educational Android application that transforms public YouTube lectures and local device videos into structured, print-ready study PDFs featuring native mathematical typography, vector diagrams, section-by-section timestamped notes, comparison tables, and revision questions.

### User Review & Critical Decisions

> [!IMPORTANT]
> Because the clarifying questions were skipped, we have selected the following production-minded defaults so you can get a complete, working system right away:
> - **AI & Backend Security**: We will provision **Firebase Authentication (Google Sign-In via Credential Manager)**, **Cloud Firestore**, and **Firebase AI Logic with App Check**, alongside a configurable **Trusted Backend Endpoint adapter** so no private Gemini API keys or server secrets are embedded in the APK, and all user data/job states are isolated under strict Firestore security rules.
> - **YouTube & Local Video Extraction**: Public YouTube URLs will be validated and inspected via YouTube oEmbed metadata and public caption track extraction (with transparent error reporting and a supplementary transcript/notes fallback when a video disables captions or restricts third-party access). Local device videos selected via the zero-permission Android Photo Picker will undergo real metadata extraction (`MediaMetadataRetriever` for duration, resolution, title, and representative keyframes at educational intervals) and chunked multimodal Gemini analysis.
> - **PDF Aesthetic & Native Rendering**: Generated PDFs will default to an **Academic Textbook & Engineering Notebook** aesthetic rendered natively via Android `PdfDocument` and `Canvas`—including custom-parsed mathematical equation rendering (proper superscripts, subscripts, fractions, radicals, Greek symbols, and summation/integral notation without raw LaTeX or `^` characters), native vector flowcharts/diagrams from structured node-edge data, automatic pagination, a dynamic Table of Contents for multi-page documents, and page numbering.

---

### 1. Overview & Core Concept

- **What It Does**: Accepts either a validated public YouTube URL or a supported local video file from the user's Android device, inspects its metadata and duration, lets the learner choose a study detail level (*Quick Summary*, *Standard Notes*, or *Detailed Study Notes*), processes the lecture in logical timestamped sections with real stage-by-stage progress, and generates a richly formatted PDF study guide ready for in-app preview, local download, and native sharing.
- **Target Audience / Persona**: University students, engineering learners, self-taught developers, and researchers who want to convert dense video lectures into searchable, printable reference notes without manually transcribing formulas and diagrams.
- **Key Value**: Eliminates the tedious process of pausing video lectures to copy equations and flowcharts while strictly preserving source accuracy, timestamps, variable definitions, and conceptual comparisons.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Welcome & Sign-In**: Clean onboarding screen explaining the study workflow and credit allowance, gated by **Sign in with Google** (via Jetpack Credential Manager) with silent auto-sign-in on returning sessions and an interactive button fallback.
  2. **Home & Video Input**: Features a YouTube URL input card with instant format validation and metadata lookup, paired with a Local Video Picker card using the zero-permission Android Photo Picker (`PickVisualMedia`). Displays a live Credit Balance pill in the top bar.
  3. **Pre-Flight Inspection & Options**: Once a valid video is selected, displays the resolved video title, source type, formatted duration, thumbnail/frame preview, credit cost breakdown, and a 3-tier selector for **Detail Level** (*Quick Summary*, *Standard Notes*, *Detailed Study Notes*) plus optional focus areas (e.g., emphasize derivations, include comparison tables).
  4. **Staged Processing Status**: Real-time job tracker showing genuine execution stages (*Validating Source & Credits* → *Extracting Transcript / Keyframes* → *Analyzing Section 1..N* → *Synthesizing Formulas, Diagrams & Glossary* → *Rendering Native Vector PDF*), with elapsed time, current section progress, and a safe **Cancel Job** action that halts processing and refunds qualifying non-consumed credits.
  5. **PDF Preview & Results**: Interactive in-app multi-page PDF viewer (`PdfRenderer` bitmap pager with pinch-to-zoom) alongside structured tabs for quick inspection (*Summary*, *Sections*, *Formulas & Diagrams*, *Quiz & Glossary*), plus primary action buttons for **Download to Device**, **Share PDF**, and **Regenerate**.
  6. **PDF Library & Processing History**: Searchable, filterable list of all previously generated PDFs and past job runs (including failed or cancelled jobs with clear diagnostic reasons and one-tap retry).
  7. **Usage & Free-Credit Balance**: Detailed breakdown of the user's credit ledger: 3 one-time welcome bonus credits, 1 daily free reset credit, rewarded-ad bonus credits (with daily cap tracker), and subscription allowance status, plus a clear explanation whenever a generation is blocked (e.g., insufficient credits or video exceeding maximum duration).
  8. **Upgrade / Subscription & Monetization**: Modular screen presenting free vs. Pro Student tiers, wired to a clean **Google Play Billing** integration interface and a **Google Mobile Ads Rewarded Ad** trigger clearly labeled in **Verified Development Test Mode** until production console IDs are supplied.
  9. **Settings, Privacy & Help**: Configurable processing preferences, cache management, account sign-out, data deletion instructions, and setup guide for external console configurations.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: **Academic Editorial & Precision Engineering**—inspired by classic university press textbooks and modern technical documentation.
  - *Color Palette & Mood*: Deep Oxford Navy (`#0F172A`) and Warm Ivory Paper (`#FAF8F5`) in Light Mode (with Deep Slate `#0B0F19` in Dark Mode), accented by Academic Cobalt (`#1D4ED8`) for primary actions, Emerald (`#047857`) for verified credits/completed jobs, and Amber (`#B45309`) for formula callouts and warnings.
  - *Typography & Hierarchy*: Bundled local Google Fonts via `font-util`—**Newsreader** (or **Playfair Display**) for authoritative academic headings and PDF titles, **Plus Jakarta Sans** for crisp UI controls and body notes, and **JetBrains Mono** for timestamps, units, and technical identifiers.
  - *Component Styling & Layout*: Material Design 3 adaptive layout supporting `Compact` handheld screens with bottom navigation and `Expanded` tablet screens with side navigation rails. Full edge-to-edge insets (`enableEdgeToEdge()`) and minimum `48.dp` touch targets.

- **Interactive Feedback & Motion**:
  - Smooth animated progress transitions between pipeline chunks, skeleton shimmers during metadata lookup, clear diagnostic banners when a YouTube video lacks public captions or exceeds duration caps, and instant ripple feedback on all interactive cards.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Native Vector & Math PDF Engine vs. HTML/WebView or AI Image Generation**
  - *Chosen Approach*: Build a dedicated native Android `PdfDocument` + `Canvas` typesetting engine with a custom mathematical expression parser/renderer and structured vector diagram drawer.
  - *Why*: Guarantees crisp vector text, true mathematical notation (stacked fractions, radical symbols, superscripts/subscripts, Greek letters, and variable unit tables), and clean node-edge flowcharts at zero extra AI image cost and with deterministic pagination.
  - *Alternatives Considered*: Generating diagrams via image-generation models (rejected due to high cost and misspelled labels) or dumping raw markdown text (rejected because raw `^`, `/`, and LaTeX commands violate readability requirements).

- **Decision 2: Staged Chunked Video & Transcript Pipeline for Cost Control**
  - *Chosen Approach*: Prefer caption/transcript segmentation first; for local videos (or visual segments), extract sparse representative keyframes using `MediaMetadataRetriever` at configurable intervals, segment long videos into time-bounded chunks, cache intermediate chunk summaries by content hash, and merge them into a unified structured study document.
  - *Why*: Prevents token limit overflows on long lectures, keeps AI latency and cost predictable, preserves chronological timestamps and formulas, and allows resuming or inspecting partial progress.

- **Decision 3: Server-Controlled Credit & Entitlement Architecture**
  - *Chosen Approach*: Store user credit state, daily reset timestamps, rewarded-ad counters, and job ledgers in Firestore under strict security rules, paired with a server-side credit transaction coordinator (supporting Firebase AI Logic + configurable Cloud Functions/Run verification endpoint).
  - *Why*: Prevents duplicate deductions via idempotency keys (`jobId`), enforces daily caps and duration limits from a central `UsagePolicyConfig`, and automatically refunds credits when a job fails due to upstream service errors.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        JETPACK COMPOSE UI LAYER (M3)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ Auth &       │  │ Home & Video │  │ Live Job     │  │ PDF Preview &   │  │
│  │ Welcome Gate │  │ Input Screen │  │ Status View  │  │ Library Screen  │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
│         │                 │                 │                   │           │
│  ┌──────┴─────────────────┴─────────────────┴───────────────────┴────────┐  │
│  │         Usage / Credits, Upgrade (Billing/Ads), Settings Screens      │  │
│  └──────────────────────────────────┬────────────────────────────────────┘  │
└─────────────────────────────────────┼───────────────────────────────────────┘
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     VIEWMODEL & DOMAIN ORCHESTRATION                        │
│  ┌────────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐  │
│  │ Auth & Profile VM  │  │ Video Processing VM  │  │ Credits & Billing   │  │
│  │ (Credential Mgr)   │  │ (Staged Pipeline)    │  │ ViewModel           │  │
│  └─────────┬──────────┘  └──────────┬───────────┘  └──────────┬──────────┘  │
└────────────┼────────────────────────┼─────────────────────────┼─────────────┘
             ▼                        ▼                         ▼
┌───────────────────────┐  ┌──────────────────────┐  ┌────────────────────────┐
│   FIREBASE & AUTH     │  │ VIDEO & AI PIPELINE  │  │  NATIVE PDF & MONETIZ. │
│ ┌───────────────────┐ │  │ ┌──────────────────┐ │  │ ┌────────────────────┐ │
│ │ Firebase Auth     │ │  │ │ YouTube Metadata │ │  │ │ Native PdfDocument │ │
│ │ (Google Sign-In)  │ │  │ │ & Caption Fetcher│ │  │ │ & Math/Diagram     │ │
│ └───────────────────┘ │  │ └──────────────────┘ │  │ │ Vector Renderer    │ │
│ ┌───────────────────┐ │  │ ┌──────────────────┐ │  │ └────────────────────┘ │
│ │ Cloud Firestore   │ │  │ │ Local Video      │ │  │ ┌────────────────────┐ │
│ │ (Users, Jobs,     │ │  │ │ Frame/Audio Insp.│ │  │ │ Modular Ad & Play  │ │
│ │  Credit Ledger,   │ │  │ └──────────────────┘ │  │ │ Billing Gateways   │ │
│ │  Cached Summaries)│ │  │ ┌──────────────────┐ │  │ │ (Verified Test/    │ │
│ └───────────────────┘ │  │ │ Gemini Structured│ │  │ │  Server-Checked)   │ │
│                       │  │ │ Study Synthesizer│ │  │ └────────────────────┘ │
└───────────────────────┘  └──────────────────────┘  └────────────────────────┘
```

- **Data Model & State**:
  - `UserAccountProfile`: `uid`, `email`, `displayName`, `bonusCreditsRemaining` (initial 3), `dailyFreeCredits` (1/day), `lastDailyResetEpochDay`, `rewardedAdCreditsToday`, `rewardedAdsWatchedToday`, `subscriptionTier` (`FREE`, `PRO_STUDENT`), `totalPdfsGenerated`, `createdAt`, `updatedAt`.
  - `UsagePolicyConfig`: Configurable rules governing `initialBonusCredits = 3`, `dailyFreeAllowance = 1`, `maxRewardedAdsPerDay = 3`, `maxVideoDurationMinutesFree = 45`, `maxVideoDurationMinutesPro = 180`, `maxLocalFileSizeMb = 250`, and `sectionChunkDurationMinutes = 10`.
  - `StudyJob`: `id` (idempotency key), `userId`, `sourceType` (`YOUTUBE`, `LOCAL_FILE`), `sourceUri`, `videoTitle`, `videoDurationSeconds`, `detailLevel` (`QUICK_SUMMARY`, `STANDARD_NOTES`, `DETAILED_STUDY_NOTES`), `status` (`VALIDATING`, `EXTRACTING`, `ANALYZING_SECTIONS`, `RENDERING_PDF`, `COMPLETED`, `FAILED`, `CANCELLED`), `progressPercent`, `currentStageDetail`, `creditDeducted`, `creditRefunded`, `errorMessage`, `localPdfPath`, `contentHash`, `createdAt`, `updatedAt`.
  - `StudyDocumentSchema` (Structured JSON output from Gemini):
    - `title`, `sourceInfo`, `executiveSummary`
    - `sections`: List of `StudySection` (`timestampRange`, `heading`, `bulletNotes`, `detailedExplanation`)
    - `concepts`: List of `ConceptDefinition` (`term`, `definition`, `context`)
    - `formulas`: List of `MathFormula` (`name`, `structuredExpression`, `units`, `variables`: List of `VariableExplanation`, `sourceVerified`: Boolean)
    - `diagrams`: List of `StructuredDiagram` (`title`, `diagramType` [`FLOWCHART`, `CYCLE`, `HIERARCHY`, `MECHANISM`], `nodes`, `edges`, `caption`)
    - `comparisonTables`: List of `ComparisonTable` (`title`, `headers`, `rows`)
    - `keyTakeaways`: List of strings
    - `revisionQuestions`: List of `RevisionQuestion` (`question`, `answerHint`, `difficulty`)
    - `glossary`: List of `GlossaryEntry` (`term`, `definition`)

- **Interactive Component & State Mapping**:
  - **Input & Validation Handler**: Validates YouTube URLs against supported patterns (`youtube.com/watch`, `youtu.be/`, `youtube.com/live`) and queries oEmbed + caption tracks; validates local video URIs via `ContentResolver` and `MediaMetadataRetriever` for MIME type (`mp4`, `webm`, `mkv`, `mov`), file size, and duration before enabling the **Start Processing** CTA.
  - **Credit Transaction Coordinator**: Before starting a job, checks the user's daily reset eligibility, verifies available balance (`dailyFreeCredits + bonusCreditsRemaining + rewardedAdCreditsToday`), records an idempotent job entry, deducts 1 credit atomically upon job acceptance, and automatically refunds that credit if the job fails due to an unrecoverable extraction or AI error.
  - **Staged Analysis & Caching**: Computes a deterministic `contentHash` (based on video ID/file signature + detail level) to reuse previously verified structured study notes when appropriate; splits long transcripts/videos into sections and updates `StudyJob.progressPercent` and `currentStageDetail` after each completed chunk.
  - **Native PDF Renderer**: Draws multi-page A4/Letter documents with a cover header, optional Table of Contents (automatically included when the document exceeds 3 pages), section-by-section timestamped notes, custom-drawn boxed mathematical formulas (converting superscripts, subscripts, fractions, square roots, and symbols into native positioned glyphs), native vector node-link flowcharts, striped comparison tables, revision Q&A, and footer page numbers (`Page X of Y`).
