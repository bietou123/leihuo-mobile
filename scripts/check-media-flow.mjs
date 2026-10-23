// Offline UI integration check. Native responses are fixtures; no model API is called.
// JSDOM cannot decode or play media. These checks cover task handling and DOM sources only.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {JSDOM}=require(path.join(process.env.LOCALAPPDATA,'leihuo-web-check/node_modules/jsdom/lib/api.js'));
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const assets=path.join(root,'app/src/main/assets');
const html=(await fs.readFile(path.join(assets,'index.html'),'utf8')).replace(/<script\b[^>]*>[\s\S]*?<\/script>/g,'');
const catalog=JSON.parse(await fs.readFile(path.join(assets,'model-catalog.json'),'utf8'));
catalog.data.push({id:'fixture-standard-video-model',supported_endpoint_types:['openai-video-gen']});
const routes=JSON.parse(await fs.readFile(path.join(assets,'gateway-endpoints.json'),'utf8'));
const dom=new JSDOM(html,{url:'https://leihuo.local/index.html',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,$=id=>w.document.getElementById(id);
let saved=[],synced={},videoCount=0,finishPaused=false,lastTask='',calls=[];
const scriptedResponses=new Map();
const failures=[];
w.matchMedia=()=>({matches:false,addEventListener(){}});
w.HTMLElement.prototype.scrollTo=function({top}){this.scrollTop=top;};
w.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
w.HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
const timer=w.setTimeout.bind(w);w.setTimeout=(fn,ms,...args)=>timer(fn,[2000,5000,10000].includes(ms)?5:ms,...args);
w.fetch=async name=>({json:async()=>String(name).includes('model-catalog')?catalog:routes});
const localMedia=(mime)=>({__localMedia:true,mime,url:`https://leihuo.local/generated-media/00000000-0000-0000-0000-000000000000.${mime.includes('video')?'mp4':'wav'}`});
function response(options){
  const body=options.body?JSON.parse(options.body):null;
  if(options.endpoint==='/v1/models')return catalog;
  if(options.endpoint==='/v1/images/generations'){assert.equal(body.model,'gpt-image-2');assert.equal(body.prompt,'生成一只橘猫');return{data:[{b64_json:'AQ=='}]};}
  if(options.endpoint==='/v1/audio/speech'){assert.equal(body.model,'speech-2.8-hd');assert.equal(body.input,'欢迎使用雷火');assert.equal(body.response_format,'hex');assert.equal(body.metadata.audio_setting.format,'mp3');assert.equal(options.responseAsMedia,true);return localMedia('audio/mpeg');}
  if(options.endpoint==='/v1/chat/completions'){
    if(body.model==='mimo-v2.5-asr'){assert.match(body.messages[0].content[0].input_audio.data,/^data:audio\/wav;base64,/);assert.equal(body.asr_options.language,'auto');return{choices:[{message:{content:'这是转写结果。'}}]};}
    assert.equal(body.model,'mimo-v2.5-tts');assert.equal(body.messages.at(-1).role,'assistant');assert.equal(body.messages.at(-1).content,'你好，测试声音');assert.equal(body.audio.voice,'mimo_default');return{...localMedia('audio/wav'),choices:[{message:{content:''}}]};
  }
  if(options.endpoint==='/fixture/seed-tts'){
    assert.equal(options.audioChunkStream,true);assert.equal(options.audioMime,'audio/mpeg');assert.equal(body.req_params.text,'同步后的语音');assert.equal(options.headers['X-Api-Resource-Id'],'seed-tts-2.0');return localMedia('audio/mpeg');
  }
  if(options.endpoint==='/v1/video/generations'){
    assert.equal(body.model,'veo-3.1-fast-generate-preview');assert.equal(body.duration,8);assert.equal(body.metadata.aspectRatio,'16:9');
    videoCount++;lastTask=`task-${videoCount}`;return{id:lastTask,status:'queued'};
  }
  if(options.endpoint==='/v1/video/generations/task-1')return{code:'success',data:{task_id:'task-1',status:'SUCCESS',url:'https://fixtures.invalid/video-without-extension'}};
  if(options.endpoint==='/v1/video/generations/task-2')return{code:'success',data:{task_id:'task-2',status:finishPaused?'SUCCESS':'IN_PROGRESS',...(finishPaused?{url:'https://fixtures.invalid/resumed-video'}:{progress:25})}};
  if(options.endpoint==='/v1/video/generations/task-3')return{code:'success',data:{task_id:'task-3',status:'SUCCESS'}};
  if(options.endpoint==='/v1/videos/task-3/content'){assert.equal(options.responseAsMedia,true);return localMedia('video/mp4');}
  throw new Error(`Unexpected fixture request: ${options.endpoint}`);
}
w.Native={
  getConfig:()=>JSON.stringify({baseUrl:'https://ai.leihuo.netease.com',hasKey:true}),
  loadChats:()=>JSON.stringify(saved),saveChats:json=>{saved=JSON.parse(json);return'{"ok":true}';},
  setTheme(){},copy(){},cancel(){},loadInterfaces:()=>JSON.stringify(synced),
  // Fixtures contain no configured credentials. Production redaction remains native.
  sanitizeMemoryText:text=>text,
  syncInterfaces(){synced={endpoints:{openspeech_unidirectional:{path:'/fixture/seed-tts',method:'POST'}},syncedAt:Date.now()};w.__nativeEvent({id:'interfaces',type:'data',data:JSON.stringify(synced)});},
  requestWithOptions(id,json){
    const options=JSON.parse(json);calls.push(options);
    timer(()=>{try{
      const queue=scriptedResponses.get(options.endpoint);
      const fixture=queue?.length?queue.shift():null;
      if(fixture?.assertOptions)fixture.assertOptions(options);
      if(fixture?.error){w.__nativeEvent({id,type:'error',data:JSON.stringify(fixture.error),status:fixture.status || 0});return;}
      const result=fixture?fixture.body:response(options),status=fixture?.status || 200;
      w.__nativeEvent({id,type:'data',data:JSON.stringify(result),status});w.__nativeEvent({id,type:'done',status});
    }catch(error){w.__nativeEvent({id,type:'error',data:error.message,status:500});}},1);
  }
};
for(const file of ['audio-profiles.js','video-profiles.js','memory.js','error-details.js','app.js'])w.eval(await fs.readFile(path.join(assets,file),'utf8'));
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(test,label){for(let i=0;i<200;i++){if(test())return;await pause(5);}throw new Error(`Timeout: ${label}`);}
await until(()=>$('modelList').children.length>0,'catalog loaded');
function selectModel(id){$('modelButton').click();const button=w.document.querySelector(`[data-model="${id}"]`);assert.ok(button,id);button.click();}
function send(text){$('messageInput').value=text;$('messageInput').dispatchEvent(new w.Event('input'));$('composerForm').dispatchEvent(new w.Event('submit',{cancelable:true}));}
const assistant=()=>saved[0]?.messages.at(-1);
async function finished(label){await until(()=>assistant()?.role==='assistant'&&!assistant().streaming,label);assert.ok(!assistant().error,assistant().error);}
selectModel('veo-3.1-fast-generate-preview');send('生成一个挖掘机施工视频');await finished('Veo video');
assert.equal(videoCount,1);assert.equal(assistant().media[0].type,'video');assert.ok($('messages').querySelector('video'));
assert.equal(assistant().task.status,'completed');
$('newChatTopButton').click();selectModel('mimo-v2.5-tts');send('你好，测试声音');await finished('MiMo TTS');assert.equal(assistant().media[0].type,'audio');assert.ok($('messages').querySelector('audio'));
$('newChatTopButton').click();selectModel('speech-2.8-hd');send('欢迎使用雷火');await finished('MiniMax TTS');assert.equal(assistant().media[0].type,'audio');
$('newChatTopButton').click();selectModel('gpt-image-2');send('生成一只橘猫');await finished('image');assert.equal(assistant().media[0].type,'image');
$('newChatTopButton').click();selectModel('mimo-v2.5-asr');
const wav=new Uint8Array(48);wav.set(Buffer.from('RIFF'),0);wav.set(Buffer.from('WAVE'),8);
Object.defineProperty($('audioInput'),'files',{configurable:true,value:[new w.File([wav],'record.wav',{type:'audio/wav'})]});$('audioInput').onchange();
await until(()=>$('attachmentPreview').children.length===1,'audio attachment');send('转写音频');await finished('ASR');assert.equal(assistant().content,'这是转写结果。');
$('newChatTopButton').click();selectModel('seed-tts-2.0');const before=calls.length;send('同步后的语音');await pause(20);
assert.equal(calls.length,before,'Seed must not invent an endpoint');assert.equal($('messageInput').value,'同步后的语音');
$('syncInterfacesButton').click();send('同步后的语音');await finished('Seed after interface sync');assert.equal(assistant().media[0].type,'audio');
$('newChatTopButton').click();selectModel('veo-3.1-fast-generate-preview');send('停止后继续查询');
await until(()=>assistant()?.task?.id==='task-2'&&assistant()?.streaming,'submitted task persisted');$('stopButton').click();
await until(()=>assistant()?.task?.status==='paused'&&!assistant().streaming,'paused task');assert.equal($('stopButton').hidden,true);
finishPaused=true;w.document.querySelector('[data-resume-task]').click();await finished('resumed task');assert.equal(videoCount,2,'Resume must not POST again');
$('newChatTopButton').click();selectModel('veo-3.1-fast-generate-preview');send('获取无地址的已完成视频');await finished('video content');assert.equal(assistant().media[0].type,'video');
const postCount=calls.filter(call=>call.endpoint==='/v1/video/generations').length;assert.equal(postCount,3);
console.log('PASS: existing offline task/media rendering checks (no playback or live model requests).');

const seedance='doubao-seedance-2-5-260628';
const standard='/v1/video/generations';
const nativeSeedance='/api/v3/contents/generations/tasks';
const signedVideo='https://fixtures.invalid/results/seedance.mp4?signature=fixture-only&expires=9999999999';
const taskDto=(id,extra={})=>({code:'success',data:{task_id:id,status:'SUCCESS',...extra}});
const readOptions=options=>{assert.equal(options.method,'GET');assert.equal(options.body,'');assert.ok(options.readTimeoutMs>=60000&&options.readTimeoutMs<=90000);};
function enqueue(endpoint,...responses){scriptedResponses.set(endpoint,responses);}
function data(body,assertOptions){return{body,status:200,assertOptions};}
function nativeError(code,source,status=0){return{error:{error:{message:`fixture ${code}`,code,source}},status,assertOptions:readOptions};}
function useInterfaces(endpoints){synced={endpoints,syncedAt:Date.now()};w.__nativeEvent({id:'interfaces',type:'data',data:JSON.stringify(synced)});}
function beginSeedance(id,route=standard){
  $('newChatTopButton').click();selectModel(seedance);
  enqueue(route,data({code:'success',data:{task_id:id,status:'QUEUED'}},options=>{assert.equal(options.method,'POST');assert.equal(JSON.parse(options.body).model,seedance);}));
  send(`fixture ${id}`);
}
async function check(label,body){
  try{await body();console.log(`PASS: ${label}`);}
  catch(error){failures.push({label,error:error.message});console.error(`FAIL: ${label}: ${error.message}`);}
}
async function settled(label){await until(()=>assistant()?.role==='assistant'&&!assistant().streaming,label);return assistant();}
function submissions(){return calls.filter(call=>call.method==='POST'&&[standard,nativeSeedance].includes(call.endpoint)).length;}
async function expectVideo(id,url=signedVideo){
  await finished(id);assert.equal(assistant().task.id,id);assert.equal(assistant().task.status,'completed');
  assert.equal(assistant().media[0].type,'video');assert.equal(assistant().media[0].url,url);
  assert.equal($('messages').querySelector('video')?.getAttribute('src'),url);
  assert.equal(assistant().diagnostic.httpStatus,200);
}

await check('Seedance standard TaskDto result_url and data.data.content.video_url',async()=>{
  const id='fixture-seedance-taskdto';
  enqueue(`${standard}/${id}`,data(taskDto(id,{result_url:signedVideo,data:{content:{video_url:signedVideo}}}),readOptions));
  beginSeedance(id);await expectVideo(id);
});
await check('Seedance standard nested content without result_url',async()=>{
  const id='fixture-seedance-nested';
  enqueue(`${standard}/${id}`,data(taskDto(id,{data:{content:{video_url:signedVideo}}}),readOptions));
  beginSeedance(id);await expectVideo(id);
});
await check('Seedance pause and resume rereads original task without POST',async()=>{
  const id='fixture-seedance-paused';
  beginSeedance(id);await until(()=>assistant()?.task?.id===id&&assistant().streaming,'Seedance persisted');
  $('stopButton').click();await until(()=>assistant()?.task?.status==='paused'&&!assistant().streaming,'Seedance paused');
  const before=submissions();enqueue(`${standard}/${id}`,data(taskDto(id,{result_url:signedVideo}),readOptions));
  w.document.querySelector('[data-resume-task]').click();await expectVideo(id);assert.equal(submissions(),before);
});
await check('Seedance DNS read retry keeps task ID and does not repeat POST',async()=>{
  const id='fixture-seedance-dns-retry',endpoint=`${standard}/${id}`,before=submissions();
  enqueue(endpoint,nativeError('native_dns','network_dns'),data(taskDto(id,{result_url:signedVideo}),options=>{readOptions(options);assert.equal(options.readTimeoutMs,75000);}));
  beginSeedance(id);await expectVideo(id);
  assert.equal(calls.filter(call=>call.endpoint===endpoint).length,2);assert.equal(submissions(),before+1);
});
await check('Seedance TLS failure pauses immediately without network retry',async()=>{
  const id='fixture-seedance-tls',endpoint=`${standard}/${id}`,before=submissions();
  enqueue(endpoint,nativeError('native_tls','network_tls'));
  beginSeedance(id);const answer=await settled('TLS paused');
  assert.equal(answer.task.status,'paused');assert.equal(answer.diagnostic.providerCode,'native_tls');assert.equal(answer.diagnostic.errorSource,'network_tls');
  assert.equal(calls.filter(call=>call.endpoint===endpoint).length,1);assert.equal(submissions(),before+1);assert.equal(answer.media.length,0);
});
await check('Seedance HTTP 503 read retries and preserves real HTTP status',async()=>{
  const id='fixture-seedance-http-retry',endpoint=`${standard}/${id}`;
  enqueue(endpoint,nativeError('upstream_busy','http',503),data(taskDto(id,{result_url:signedVideo}),readOptions));
  beginSeedance(id);await expectVideo(id);assert.equal(calls.filter(call=>call.endpoint===endpoint).length,2);
});
await check('Seedance exhausted DNS reads pause after four GETs',async()=>{
  const id='fixture-seedance-dns-exhausted',endpoint=`${standard}/${id}`,before=submissions();
  enqueue(endpoint,...Array.from({length:4},()=>nativeError('native_dns','network_dns')));
  beginSeedance(id);const answer=await settled('DNS exhausted');
  assert.equal(answer.task.status,'paused');assert.equal(answer.diagnostic.providerCode,'native_dns');assert.equal(answer.diagnostic.retryCount,3);
  assert.equal(calls.filter(call=>call.endpoint===endpoint).length,4);assert.equal(submissions(),before+1);
  enqueue(endpoint,data(taskDto(id,{result_url:signedVideo}),readOptions));
  w.document.querySelector('[data-resume-task]').click();await expectVideo(id);
  assert.equal(calls.filter(call=>call.endpoint===endpoint).length,5);assert.equal(submissions(),before+1);
  assert.ok(!assistant().diagnostic.providerCode,'successful resume must not retain the prior network error code');
});
await check('Seedance failed task never renders an input image as video',async()=>{
  const id='fixture-seedance-failed';
  enqueue(`${standard}/${id}`,data({code:'success',data:{task_id:id,status:'FAILED',fail_reason:'fixture rejected',data:{content:{image_url:'https://fixtures.invalid/input.png'}}}},readOptions));
  beginSeedance(id);const answer=await settled('task failed');
  assert.equal(answer.task.status,'failed');assert.equal(answer.media.length,0);assert.ok(!$('messages').querySelector('video'));assert.equal(answer.diagnostic.httpStatus,200);
});
await check('Seedance copyright rejection shows Chinese reason without retrying generation',async()=>{
  const id='fixture-seedance-copyright',before=submissions();
  enqueue(`${standard}/${id}`,data(taskDto(id,{status:'FAILED',fail_reason:'The request failed because the output video may be related to copyright restrictions. Request id: fixture-request'}),readOptions));
  beginSeedance(id);const answer=await settled('copyright rejected');
  assert.equal(answer.task.status,'failed');assert.match(answer.error,/版权限制/);
  assert.equal(answer.media.length,0);assert.equal(submissions(),before+1);
  assert.match(answer.diagnostic.safeMessage,/copyright restrictions/);
});
await check('Video error event retains completed task; reload only GETs',async()=>{
  const id='fixture-seedance-playback-event',endpoint=`${standard}/${id}`;
  enqueue(endpoint,data(taskDto(id,{result_url:signedVideo}),readOptions));beginSeedance(id);await expectVideo(id);
  const video=$('messages').querySelector('video');Object.defineProperty(video,'error',{value:{code:2},configurable:true});video.dispatchEvent(new w.Event('error'));
  assert.equal(assistant().playbackError.code,2);assert.equal(assistant().task.status,'completed');
  const before=submissions();enqueue(endpoint,data(taskDto(id,{result_url:signedVideo}),readOptions));
  w.document.querySelector('[data-resume-task]').click();await expectVideo(id);assert.equal(submissions(),before);assert.ok(!assistant().playbackError);
});
await check('Native Seedance TaskDto can read wrapped result_url',async()=>{
  useInterfaces({seedance:{path:nativeSeedance,method:'POST'}});
  const id='fixture-seedance-native-result';
  enqueue(`${nativeSeedance}/${id}`,data(taskDto(id,{result_url:signedVideo}),readOptions));
  beginSeedance(id,nativeSeedance);await expectVideo(id);
});
await check('Native Seedance TaskDto can read double wrapped content',async()=>{
  const id='fixture-seedance-native-nested';
  enqueue(`${nativeSeedance}/${id}`,data(taskDto(id,{data:{content:{video_url:signedVideo}}}),readOptions));
  beginSeedance(id,nativeSeedance);await expectVideo(id);
});
await check('Previously saved native Seedance task gains wrapped media paths on resume',async()=>{
  const id='fixture-seedance-legacy-native',chatId='fixture-legacy-native-chat';
  const chat={id:chatId,title:'legacy native task',model:seedance,createdAt:Date.now(),updatedAt:Date.now(),messages:[
    {id:'fixture-legacy-user',role:'user',content:'fixture original request',model:seedance},
    {id:'fixture-legacy-assistant',role:'assistant',content:'task paused',model:seedance,media:[],streaming:false,
      task:{id,model:seedance,status:'paused',baseUrl:'https://ai.leihuo.netease.com',headers:{},query:{
        path:`${nativeSeedance}/$TASK_ID`,method:'GET',idPaths:['id','data.id'],statusPaths:['status','data.status'],
        mediaPaths:['content.video_url','data.content.video_url']
      }}}
  ]};
  w.__nativeEvent({id:'import',type:'data',data:JSON.stringify({chats:[chat]})});
  w.document.querySelector(`[data-chat="${chatId}"]`).click();
  const before=submissions();enqueue(`${nativeSeedance}/${id}`,data(taskDto(id,{data:{content:{video_url:signedVideo}}}),readOptions));
  w.document.querySelector('[data-resume-task]').click();
  const answer=()=>saved.find(item=>item.id===chatId)?.messages.at(-1);
  await until(()=>answer()?.task?.status==='completed'&&!answer().streaming,'legacy native task resumed');
  assert.ok(!answer().error,answer().error);assert.equal(answer().media[0].url,signedVideo);
  assert.equal(submissions(),before);assert.ok(answer().task.query.mediaPaths.includes('data.data.content.video_url'));
});
await check('Synced Seedance task mapping is preserved for its confirmed custom route',async()=>{
  const route='/fixture/seedance-create',query='/fixture/seedance-tasks/$TASK_ID',id='fixture-seedance-synced-map';
  useInterfaces({seedance:{path:route,method:'POST',task:{path:query,method:'GET',synced:true,body:{fixture:'must-not-send'},headers:{'X-Fixture':'must-not-send'}}}});
  enqueue(query.replace('$TASK_ID',id),data({id,status:'succeeded',content:{video_url:signedVideo}},options=>{readOptions(options);assert.ok(!options.headers?.['X-Fixture']);}));
  beginSeedance(id,route);await pause(30);
  assert.ok(calls.some(call=>call.endpoint===route),'No request was made: applyInterfaces discarded the synced task mapping');
  await expectVideo(id);
});
await check('Unsafe or unconfirmed synced task mappings do not submit a generation',async()=>{
  const route='/fixture/seedance-create';
  const invalidTasks=[
    {path:'https://fixtures.invalid/$TASK_ID',method:'GET',synced:true},
    {path:'//fixtures.invalid/$TASK_ID',method:'GET',synced:true},
    {path:'/fixture/%2e%2e/tasks/$TASK_ID',method:'GET',synced:true},
    {path:'/fixture/%252e%252e/tasks/$TASK_ID',method:'GET',synced:true},
    {path:'/fixture/tasks/$TASK_ID',method:'POST',synced:true},
    {path:'/fixture/tasks/$TASK_ID',method:'GET',synced:false},
    {path:'/fixture/tasks/missing-placeholder',method:'GET',synced:true}
  ];
  for(const task of invalidTasks){
    useInterfaces({seedance:{path:route,method:'POST',task}});
    $('newChatTopButton').click();selectModel(seedance);
    const before=calls.length;send('fixture invalid query');await pause(20);
    assert.equal(calls.length,before);assert.equal($('messageInput').value,'fixture invalid query');
  }
});
await check('Syncing an unchanged standard video route preserves its verified default query',async()=>{
  const id='fixture-standard-video-task';
  useInterfaces({'openai-video-gen':{path:standard,method:'POST'}});
  $('newChatTopButton').click();selectModel('fixture-standard-video-model');
  enqueue(standard,data({id,status:'queued'},options=>assert.equal(JSON.parse(options.body).model,'fixture-standard-video-model')));
  enqueue(`${standard}/${id}`,data(taskDto(id,{result_url:signedVideo}),readOptions));
  send('fixture standard video');await expectVideo(id);
});
dom.window.close();
if(failures.length){console.error(JSON.stringify({offlineFailures:failures},null,2));process.exitCode=1;}
else console.log('PASS: all offline fixtures. No media decoding, Android runtime, live generation, or actual CDN access was exercised.');
