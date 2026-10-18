# Native Whisper QNN attribution and scope

The frontend adapts the existing Jarvis Smart Turn frontend into an independent,
30-second, right-padded, unnormalized Whisper ASR frontend. It does not change
Smart Turn. Upstream notices and complete BSD-2-Clause/Apache-2.0 license texts
remain in third_party/smart-turn/; see tests/README.md for the exact Hugging Face
reference and numeric verification contract.

The decoder's fixed tensor/cache/mask contract follows Qualcomm AI Hub Models
v0.64.0, src/qai_hub_models/models/templates/hf_whisper/app.py and model.py:
https://github.com/qualcomm/ai-hub-models/tree/v0.64.0/src/qai_hub_models/models/templates/hf_whisper

The new C++ implementation binds tensors by name, validates all metadata, forces
an English transcription/no-timestamp prefix, suppresses other special tokens,
and adds JNI lifetime ownership and terminal ORT cancellation. Model weights and
vendor runtime binaries are not included in this source directory.

Copyright 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
OF THE POSSIBILITY OF SUCH DAMAGE.
