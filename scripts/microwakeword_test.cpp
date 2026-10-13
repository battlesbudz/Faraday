#include "../app/src/main/cpp/microwakeword/MicroWakeWordEngine.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
#include <algorithm>
int main(int argc, char** argv) {
    if (argc < 3 || argc > 4) { std::cerr << "Usage: microwakeword_test model.tflite audio.pcm16 [cutoff]\n"; return 2; }
    std::ifstream modelFile(argv[1], std::ios::binary), pcmFile(argv[2], std::ios::binary);
    if (!modelFile || !pcmFile) return 2;
    std::vector<uint8_t> model((std::istreambuf_iterator<char>(modelFile)), {});
    MicroWakeWordEngine engine(model.data(), model.size(), 16000, 10, argc == 4 ? std::stof(argv[3]) : .97f, 5);
    if (!engine.isInitialized()) return 3;
    int16_t samples[1600]; int detections = 0; float peak = 0; size_t frames = 0;
    while (pcmFile.read(reinterpret_cast<char*>(samples), sizeof(samples)) || pcmFile.gcount()) {
        auto count = static_cast<size_t>(pcmFile.gcount()) / sizeof(int16_t);
        if (engine.processAudio(samples, count)) ++detections;
        peak = std::max(peak, engine.probability()); frames += count;
    }
    std::cout << "{\"detections\":" << detections << ",\"peakScore\":" << peak << ",\"frames\":" << frames << "}\n";
}
