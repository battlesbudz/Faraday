# Downloaded AI model management

The AI model control in setup and Voice Call settings is a single scrollable dropdown. It lists the catalog with Selected, Installed, or Not installed status. Selecting an installed model reuses its files.

Select a model and use **Delete downloaded model** to remove its app-owned model file, interrupted download/import fragments, and per-model runtime cache. The confirmation displays the selected model; the button reports its stored size. Original files in Downloads are preserved. Verification fingerprints and smoke-test flags are cleared so reinstalling requires validation again.

Deletion is blocked during calls, an armed session, tests, imports, and setup. It shares the existing model-operation lock and closes the idle native engine first. Deleting the selected model returns to setup; select another installed model or explicitly download/import again. Nothing is automatically downloaded by deletion.

Validation: ModelRemovalTest checks removal of the selected files and cache, preservation of other models and original Downloads files, and safe repeated removal. Release compilation and unit tests run in existing PR #6 CI. Phone verification should cover deleting a selected model, switching to another installed model, reinstalling, and disabled management during a call.
