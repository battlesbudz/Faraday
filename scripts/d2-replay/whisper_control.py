from common import *
import sherpa_onnx
root=args.whisper
r=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(root/'base.en-encoder.int8.onnx'),decoder=str(root/'base.en-decoder.int8.onnx'),tokens=str(root/'base.en-tokens.txt'),num_threads=2)
results=[]
for p in sorted(args.input.glob('*communication_speaker*.zip')):
 with zipfile.ZipFile(p) as z:
  n=next(n for n in z.namelist() if n.endswith('/microphone.wav'))
  with wave.open(io.BytesIO(z.read(n))) as w: pcm=w.readframes(w.getnframes())
 x=np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768
 s=r.create_stream();s.accept_waveform(16000,x);start=time.monotonic();r.decode_stream(s)
 result={'file':p.name,'text':s.result.text,'elapsed':time.monotonic()-start};print(json.dumps(result),flush=True);results.append(result)
(args.output / 'whisper-results.json').write_text(json.dumps(results,indent=2))
