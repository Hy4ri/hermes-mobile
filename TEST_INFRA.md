# Test Infrastructure - hermes-mobile

This document describes the test infrastructure, philosophy, architecture, and feature coverage for the **hermes-mobile** Jetpack Compose Android application.

## Test Philosophy
The test suite utilizes **opaque-box JVM-based integration and E2E tests** that drive the ViewModels directly, bypassing the UI rendering layer, with mocked Retrofit responses using `Mockk` for service stubs. This allows testing the application logic, optimistic UI state changes, API integration pathways, and navigation flows synchronously and reliably without requiring an Android emulator or device.

## Feature Inventory

### Feature 1: Skills Management Screen
- **Listing**: Fetches and lists available agent skills.
- **Category/Description Display**: Shows skill details (category, optional description, name).
- **Toggle Success**: Modifies a skill's state optimistically, sends API request, and confirms success state.
- **Toggle Failure Revert**: Handles API failure, displays toast, and reverts the state optimistically.
- **Refresh**: Supports reloading/refreshing the list of skills from the API.

### Feature 2: Cron Jobs Screen
- **Listing**: Fetches and lists all configured cron jobs.
- **State/Last Run/Next Run Display**: Displays status fields (state, last_run_status, next_run).
- **Pause Action**: Optimistically transitions a job's state to "paused", sends API request.
- **Resume Action**: Optimistically transitions a job's state to "active", sends API request.
- **Trigger Action**: Triggers a job immediately and displays a success toast.
- **Delete Action**: Optimistically removes a job from the list and sends a DELETE API request.
- **Refresh**: Supports reloading/refreshing the list of cron jobs.

### Feature 3: Navigation Drawer
- **Transitions**: Simulates moving between top-level destinations (Chat, Skills, Cron Jobs) via the Material3 Navigation Drawer using the Custom `NavigationController` and its `backStack`.

---

## Test Architecture

- **Test Runner**:
  - Main Test suite command: `nix develop --command ./gradlew test`
  - Style checking command: `nix develop --command ./gradlew ktlintCheck`
- **Test Case Format**:
  - JUnit 4 / JUnit Platform JVM tests implemented in Kotlin.
  - Mocking library: `Mockk` for mock objects, static mocks, and API endpoints.
- **Directory Layout**:
  - `app/src/test/java/com/m57/hermescontrol/E2eIntegrationTest.kt` contains the complete end-to-end integration test suite.

---

## Tier 4 Scenario: Complete User Journey
The Tier 4 scenario is implemented in `testFullUserSessionFlow()` which simulates a complete user session:
1. **Connect**: User enters coordinates and token, successfully authenticating/connecting.
2. **Open Drawer & Navigate**: Transitions from Chat to Skills.
3. **Toggle a Skill (Optimistic Update -> API Failure -> Toast/Revert)**:
   - User toggles a skill.
   - UI updates optimistically to the new state.
   - Retrofit API returns a simulated HTTP 500 error.
   - The state is reverted back to original, and an error Toast message is shown.
4. **Navigate to Cron Jobs**: User navigates from Skills to Cron Jobs.
5. **Trigger Cron Job**:
   - User triggers a cron job.
   - Retrofit API returns a success code.
   - Success Toast message is displayed and verified.
