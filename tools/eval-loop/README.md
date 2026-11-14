# Offline eval loop

This package replays transcript-level JSONL records against named baseline and
candidate variants. It uses only Python's standard library and has no Android,
audio, network, or model dependency. `ModelBackend` and `Judge` are the extension
points for a future local inference adapter and an independently reviewed judge.
The included CLI uses deterministic fixture outputs and a deterministic judge.

## Transcript JSONL

Each non-empty line is one object with a unique `id`, non-empty `turns`, and
optional `metadata`. Turn roles are `system`, `user`, `assistant`, or `tool`;
each turn has string `text`, optional arrays `tool_calls` and `tool_results`
(arrays of objects), and optional object `diagnostics`. Example:

```json
{"id":"weather","metadata":{"answerable":true,"input_modalities":[]},"turns":[{"role":"user","text":"What is the weather?"},{"role":"assistant","text":"I do not have current weather data."}]}
```

Metadata is transcript-level evaluation context. `answerable: true` enables the
refusal checker; `input_modalities` can contain `image` or `audio` to establish
perception input. These labels must represent actual recorded input, not a
user's text mentioning an image or sound. The synthetic fixture also supports `fake_outputs` and
`fake_judge_scores`; these are test/demo hooks, not model inputs. Tool schemas
are supplied to `CheckerSuite` by the caller as JSON-schema-like `type`,
`properties`, and `required` definitions.

## Run

From the repository root:

```sh
PYTHONPATH=tools/eval-loop python3 -m eval_loop.cli \
  tools/eval-loop/examples/synthetic.jsonl --variant clear-refusal \
  --proposed-change "Clarify direct-answer instruction" \
  --ledger /tmp/eval-ledger.json --report /tmp/eval-report.json
```

The default fake backend reads outputs from each transcript's `fake_outputs`
map. The default baseline is `base`. The CLI writes a compact replay report,
keeps its promotion history in the ledger JSON, and appends judge-only wins to
`judge-only-review.jsonl` (or `--review-file`). A comparison is a shadow win
when at least one checker failure is fixed across the corpus, no checker result
regresses, and the candidate's mean judge score is strictly higher. Three
consecutive wins for the same candidate promote it to the shadow baseline.
Any non-win resets that streak. Subsequent comparisons must name the promoted
shadow baseline. The ledger never changes Android prompts or app state.

Checker signals are explicit heuristics/contracts: refusal phrases on records
marked answerable, first-person visual/audio perception claims without a marked
modality, normalized exact repetition of the
prior assistant answer, and tool-call argument validation. They are not claims
of broad semantic understanding. A caller can inject tool schemas; the CLI's
empty schema map treats all emitted tool calls as invalid. The fixture judge is
not a quality oracle and must not be used to make product promotion decisions.

Run tests with:

```sh
PYTHONPATH=tools/eval-loop python3 -m unittest discover -s tools/eval-loop/tests -v
```
