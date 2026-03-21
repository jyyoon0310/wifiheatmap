Guide for adding a new feature to the Wi-Fi Heatmap project.

Follow this checklist for the feature: $ARGUMENTS

1. **Model** (if new data needed): Add to `src/main/java/app/model/`
   - Update `WifiEnvironment.java` if it's environment-level data
   - Update `AppState.java` if it's UI state

2. **Engine** (if computation needed): Add to `src/main/java/app/engine/`
   - Pure logic, no JavaFX UI code here
   - Use JavaFX Task for async operations

3. **Controller**: Wire up in appropriate controller
   - `MainController.java` for orchestration
   - `ApController.java` for AP-related interactions
   - `ToolsController.java` for tool-mode interactions
   - `ViewportController.java` for pan/zoom

4. **UI**: Update in `src/main/java/app/ui/`
   - Canvas rendering → `CanvasView.java`
   - Side panel controls → `LeftPanel.java`
   - Toolbar buttons → `TopToolbar.java`
   - Modal dialogs → `src/main/java/app/dialog/`

5. **Test**: `./gradlew run` to verify visually (no automated tests)

Always read the relevant existing files before modifying them.
Keep Korean comments if modifying files that already use Korean.
