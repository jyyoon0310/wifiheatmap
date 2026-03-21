Review the current branch changes before creating a PR.

Run these checks in order:

1. Show git diff summary: `git diff main...HEAD --stat`
2. Show full diff: `git diff main...HEAD`
3. Check for common issues:
   - No hardcoded paths or debug print statements left in
   - Engine logic not mixed into UI files
   - New model fields are serialized (Jackson annotations if needed)
   - No blocking operations on JavaFX Application Thread
4. Summarize: what changed, what was the intent, any risks
5. Suggest a PR title and description

Use Bash tool for git commands.
