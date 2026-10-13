from common import *
import onnxruntime as ort
results=[]
for p in sorted(root.glob('*communication_speaker*.zip')):
 with zipfile.ZipFile(p) as z:
  name=next(n for n in z.namelist() if n.endswith('/microphone.wav'))
  with wave.open(io.BytesIO(z.read(name))) as w: x=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 options=ort.SessionOptions();options.intra_op_num_threads=1
 vad=ort.InferenceSession(str(pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/assets/voice/silero_vad.onnx'),options)
 h=np.zeros((2,1,64),np.float32);c=h.copy();pending=np.array([],np.float32);consecutive=0;noise=[];floor=0.;pre=np.array([],np.float32);phrase_started=False;qualified=[];lines={}
 t=Transcriber(str(args.moonshine),ModelArch.SMALL_STREAMING,update_interval=.25,options={'vad_threshold':'0.0','identify_speakers':'false','return_audio_data':'false','transcription_interval':'0.25'})
 def event(e):
  if hasattr(e,'line') and e.line is not None:lines[e.line.line_id]=e.line.text
 t.add_listener(event);t.start();stream=t.get_default_stream()
 for offset in range(0,len(x),1600):
  chunk=x[offset:offset+1600];pending=np.concatenate((pending,chunk));speech=False;prob=0.
  while len(pending)>=512:
   v,h,c=vad.run(None,{'x':pending[:512].reshape(1,512),'h':h,'c':c});pending=pending[512:]
   f=float(v[0,0]);prob=max(prob,f);consecutive=min(consecutive+1,3) if f>=.5 else 0;speech|=consecutive>=3
  at=(offset+len(chunk))/16;rms=float(np.sqrt(np.mean(chunk.astype(float)**2)))*32768
  noise=[n for n in noise if at-n[0]<=3000]
  if prob<.15:noise=(noise+[(at,rms)])[-30:]
  if len(noise)>=3 and noise[-1][0]-noise[0][0]>=200:floor=sorted(n[1] for n in noise)[(len(noise)-1)//5]
  if floor>0 and prob<.15:floor=min(floor,rms)
  if rms==0 or floor>0 and rms<=max(floor,1)*(1.1 if prob>=.8 else 1.8):speech=False;prob=0.
  if speech:
   phrase_started=True;q=np.concatenate((pre,chunk));pre=np.array([],np.float32)
  elif phrase_started:
   q=chunk
  else:
   q=np.array([],np.float32);pre=np.concatenate((pre,chunk))[-19200:]
  qualified.append(q)
  stream._update_interval=.25 if prob>=.15 else sys.float_info.max
  if len(q):t.add_audio(q,16000)
 assert t._lib.moonshine_stop_stream(t._handle,stream._handle)==0
 final=stream.update_transcription(1)
 row={'file':p.name,'mode':'complete_phrase_v1_no_endpoint_or_live_backlog','decoderSamples':sum(map(len,qualified)),'text':' '.join(v for v in lines.values() if v),'final':str(final)}
 print(json.dumps(row),flush=True);results.append(row);t.close()
(args.output / 'call-gates-results.json').write_text(json.dumps(results,indent=2))
