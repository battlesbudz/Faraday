# Galaxy Z Fold 6 local model benchmarks

These results were supplied from the target Galaxy Z Fold 6 using app version
1.0.16 and three runs per model.

| Model | Accelerator | Prefill | Decode | Time to first token | Initialization |
|---|---|---:|---:|---:|---:|
| Gemma-4-E2B-it | GPU | 516.1 tok/s | 31.7 tok/s | 0.529 s | 16.3 s first / 20.4 s steady |

## Architecture decision

Gemma 4 E2B is the sole local intelligence model. It emits native structured
tool calls for phone actions, which Kotlin validates before execution.

These measurements prove model feasibility on the target phone; the complete
assistant loop still requires end-to-end validation once the runtime adapters
are added.

## Voice pipeline observations

See the [October 2 screenshot baseline](benchmarks/2026-10-02/README.md) for seven phone-observed reply timings, original screenshots, estimates and missing provenance. See [pipeline benchmark definitions and export](verification/pipeline-benchmarks.md) for the reproducible report format.
