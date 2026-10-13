# Pinned-model license facts and packaging checklist

Checked 2026-10-06. Factual source inspection only; no legal clearance, license
acceptance, account login, upload, or redistribution was performed.

## Applicable license

The [exact pinned LiteRT model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/README.md)
declares Apache-2.0. Google's [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)
agrees. The [older Gemma Terms page](https://ai.google.dev/gemma/terms)
expressly routes Gemma 4 to its [Apache-2.0 license](https://ai.google.dev/gemma/apache_2).
Do not apply the older custom Gemma Notice/use-policy obligations to Gemma 4 merely
because the model family has the same name.

The [pinned file page](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm)
publicly exposes the exact source SHA-256 used by this recipe. No model-license
clickthrough is evidenced on that page. The current APK path can reuse the user's
existing local bundle without a new download or login flow. This does not assert
anything about future hosting/account terms or accept an agreement for the user.

## Conservative redistribution checklist

For packaged graph material, follow Apache-2.0 redistribution requirements:

- Include a full license copy, not just a web link.
- Add prominent modification notices; identify the changed graph and structural
  assets by exact filenames/hashes.
- Retain applicable copyright, patent, trademark, and attribution notices.
- Carry forward relevant upstream NOTICE attribution when the upstream work
  includes such a file. Do not imply Google endorsement.

Source: [Apache-2.0 sections 4 and 6](https://www.apache.org/licenses/LICENSE-2.0).

No LICENSE or NOTICE file is listed in the [exact pinned model repository tree](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f).
That observation does not remove the license-copy obligation or settle notices
from LiteRT-LM, LiteRT, MiniAudio, KissFFT, or other APK dependencies.

Treat the structural graph recipe conservatively as modified model material;
removing parameter bytes does not by itself decide derivative-work status. The
local recipe writes the reconstructed model to the user's private app storage
and makes no outbound transfer. Any later sharing needs its own distribution
and notice review. A binary-adjacent modification notice is prepared here;
its sufficiency for the eventual packaged form is not established by this check.

## Included preparation

`Apache-2.0.txt` is a full standard license copy from the already available official
LiteRT-LM checkout. `MODEL-MODIFICATIONS.txt` records provenance and modifications.
These additions are documentation only and do not change either runtime asset or
the exact reconstructed model hash. They do not authorize distributing weights.
