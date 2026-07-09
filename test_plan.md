1. Add a new test case `testRefreshSettings_updatesUiState` in `app/src/test/java/com/m57/hermescontrol/ui/chat/ChatViewModelTest.kt`.
2. The test will mock `AuthManager` properties, call `refreshSettings()`, and verify that `uiState.value.typingEffectEnabled` and `uiState.value.typingEffectDelayMs` are updated accordingly.
3. Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
4. Submit the pull request with a descriptive title and description outlining the testing gap addressed, scenarios covered, and improvement in test coverage.
