OpenCode ACP agent package for CodeOnTheGo (branch stage, base commit c8b7aad)
=============================================================================
Contents
  files/                    25 NEW files at their repo paths (agent code, UI, icon, manifest, docs). None exists upstream,
                            so copying can never overwrite upstream code.
  register-opencode.patch   the ONLY change to existing files: 3 files, 28 added lines (DI registration + toolbar/sidebar entry)

Install (from the root of your fork; <pkg> = the unzipped opencode-agent folder)
  cp -r <pkg>/files/. .
  git apply --whitespace=nowarn <pkg>/register-opencode.patch     # if it fails: git apply --3way ... , or do the manual edits below
  git add -A && git commit -m "Add OpenCode ACP agent" && git push

Manual edits (if the patch does not apply because upstream moved)
  1) app/src/main/java/com/itsaky/androidide/di/AppModule.kt
     inside the Koin module, after:  single { PendingDeepLinkOpen() }
     add the "// OpenCode Agent" block shown in register-opencode.patch (6 definitions).
  2) .../utils/EditorActivityActions.kt
     add import:  com.itsaky.androidide.actions.opencode.OpenCodeToolbarAction
     add after ShowTooltipAction:  registry.registerAction(OpenCodeToolbarAction(context, order++))
  3) .../utils/EditorSidebarActions.kt
     add import:  com.itsaky.androidide.actions.sidebar.OpenCodeSidebarAction
     add after HelpSideBarAction:  registry.registerAction(OpenCodeSidebarAction(context, ++order))

Build (this package does NOT change the build system, so the original v7/v8 flavors stay)
  ./gradlew :app:assembleV8Debug        APK: app/build/outputs/apk/v8/debug/
  Release variants need the folders assets/release/common, v7 and v8 to exist (a git clone has them).

Status: checked statically only (imports resolve, resources exist, patch applies to c8b7aad).
It has NOT been compiled or run. First-run checklist: docs/ACP_AGENT.md (after install).
