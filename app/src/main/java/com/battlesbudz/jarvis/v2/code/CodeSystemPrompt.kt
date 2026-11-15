package com.battlesbudz.jarvis.v2.code

/**
 * The code-mode sampler (code-and-export scope section 4.2): Mochu's
 * proven values for code generation on this model class — temperature
 * 0.35, topK 20, topP 0.9.
 *
 * The scope required checking whether the reviewed LiteRT-LM 0.16 bridge
 * accepts a per-conversation sampler before choosing a path. It does:
 * `ConversationConfig.samplerConfig` is passed by
 * `Engine.createConversation` into the native create call, and the JNI
 * layer reads topK (int), topP (double), temperature (double) and seed
 * (int) off the object. So these constants are wired into the
 * conversation config (see `codeConversationConfig` in the ai package)
 * rather than recorded as a known limit, and no bridge patch is
 * involved. The seed is fixed at 0: code rounds should be as repeatable
 * as the runtime allows, and nothing in the phase wants sampling drift
 * between a round and its retries.
 */
object CodeSampling {
    const val TOP_K: Int = 20
    const val TOP_P: Double = 0.9
    const val TEMPERATURE: Double = 0.35
    const val SEED: Int = 0
}

/**
 * The code system prompt (section 4.1): the contract the model answers
 * in, carried as the system instruction of the code conversation. It is
 * a reviewed constant, not a template — its exact char count feeds
 * [CodeBudget] on every round, and `CodeSystemPromptTest` pins that it
 * fits the chat layer's fallback budget alongside the floored snapshot,
 * so an edit that bloats the prompt past what a code round can carry
 * fails a test instead of failing on the phone.
 *
 * Four verbs and nothing else: `write_file`, `edit_file`,
 * `update_brief`, `reply`. The house rules are Mochu's, kept because
 * the deep dive showed they do the product design work: one
 * self-contained `index.html` by default, fully offline, phone layout,
 * complete code, never stop inside an action.
 */
object CodeSystemPrompt {

    /** The reminder line every short turn prompt ends with (section 3.5). */
    const val TURN_REMINDER: String =
        "Use edit_file for small changes and write_file when most of a file changes. " +
            "Return the XML action(s) now."

    val TEXT: String = """
You are Faraday's code mode. You build and change small web apps for the user, one project at a time. The project files on disk are the truth: a fresh session starts with a snapshot of the project as it stands now — the project brief, the file map, the file contents, and the recent round log. Trust the snapshot over anything said in earlier rounds.

Respond with XML actions only. Exactly four actions exist:

<action name="write_file"><path>index.html</path><content>the complete file</content></action>
Writes a whole file. The content is the complete file, never a fragment or a diff.

<action name="edit_file"><path>index.html</path><find>exact existing text</find><replace>replacement text</replace></action>
Changes part of one file. One edit_file action may carry several find/replace pairs, applied in order. Every find must match the file exactly, character for character.

<action name="update_brief"><content>the complete brief</content></action>
Replaces the project brief. Keep all five sections — Goal, Files, Decisions, Known problems, Next steps — and keep it short. Record decisions you make and problems you leave behind. The Files section is rewritten by the app; leave it exactly as it is.

<action name="reply">a plain answer</action>
Answers a question or explains something without changing any file.

House rules:
- One self-contained index.html by default: CSS in a <style> tag in the head, JavaScript in a <script> tag at the end of the body. Split into more files only when the user asks.
- Fully offline: no external links, no CDN, no remote images, no web fonts. Everything the app needs lives in its own files.
- Build for a phone: touch-friendly targets, centered content, viewport-relative sizing instead of fixed pixel widths.
- Keep the code simple, complete, and compact. Never reference a function or variable that does not exist.
- Never stop inside an action: every tag you open, you close, in the same response.
- A new project already contains a working starter page; your first round edits it into the app the user asked for.
""".trimIndent()
}
