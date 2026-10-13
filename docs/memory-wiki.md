# Personal Memory Wiki

Jarvis turns approved local memories into a personal wiki. It stores the ledger on the device and derives every page from the current approved records. There is no cloud sync, model-created page, or test-only capture control.

## What enters the wiki

A finalized Chat or Voice input may create a **Pending** proposal when it matches the conservative local capture rules, such as “Remember I prefer …” or “My favorite … is …”. A pending memory is visible in **Review** and **History**, but it is not a wiki fact: it does not appear on wiki pages, Sources, or approved-memory search until you tap **Approve**. Rejected, superseded, deleted, expired, and restricted memories are also excluded from the wiki.

The wiki groups approved records by category and topic. The suggested placement is editable when adding a memory and later from a memory detail’s **Organize** action. Pages show article facts, their recorded sources, and lineage history. A fact can link to another topic with `[[Topic name]]`; a target page shows the reciprocal backlink when that topic exists.

## User test recipe

Use a new phrase so an earlier test cannot match it, for example `Remember I prefer juniper-amber-2026 notebooks.`

1. In **Chat**, send that completed sentence and wait for its turn to finish. Open **Memory** and choose **Review**. The new proposal should be there as Pending.
2. Before approving it, open **Wiki** and search the distinctive phrase. The approved search must report no match.
3. Return to **Review** and tap **Approve**. Search the same phrase again in **Wiki**, open the result, then open **Sources**. It should show that the fact was captured from the Text conversation.
4. In the fact detail, choose **Correct**, enter a new unique value, and submit it. It returns to Review. Approve it there. The old value should no longer appear in approved search; the replacement should appear, and the page’s History identifies the correction.
5. Use **Erase** from the replacement detail. Cancel once and verify the fact remains. Confirm Erase, then search both the original and replacement values; neither should be in the wiki.
6. Add and approve one more unique memory, close and reopen Jarvis, and search it again. The approved fact should remain. **Erase all memories** also asks for confirmation; cancel keeps the records and confirm clears the local ledger.

To check navigation while a voice call is active, return to Chat or Voice from Memory and end the call only with the visible **End** action. Navigating itself does not approve, reject, or erase memory.

## Boundaries

Approved retrieval is bounded lexical matching over the local ledger. Its quoted context is historical information only: it cannot authorize a phone action, override the current request, or become a tool instruction. The release journey uses controlled finalized text and voice inputs to cover the capture boundary and the real UI; it does not represent real ASR, microphone, model inference, or cloud retrieval.
