import {boundedJson,DEVICE_TTL,hash,ID,json,MAX_MESSAGES,MAX_PAYLOAD,PAIR_TTL} from './limits';
interface Config{browserHash:string;phoneHash:string;expiresAt:number;active:boolean;sequence:number}
interface StoredMessage{sequence:number;id:string;expiresAt:number;payload:string;role:'browser'|'phone'}
export class Mailbox implements DurableObject {
  constructor(private readonly state:DurableObjectState){}
  async fetch(request:Request):Promise<Response>{
    // Never hold the object's concurrency gate while reading an untrusted stream.
    let body:unknown;
    try{if(new URL(request.url).pathname==='/messages'&&request.method==='POST')body=await boundedJson(request);}catch{return json({error:'Invalid request'},400);}
    return this.state.blockConcurrencyWhile(async()=>{
      try{return await this.handle(request,body);}catch{return json({error:'Invalid request'},400);}
    });
  }
  private async handle(request:Request,parsedBody?:unknown):Promise<Response>{
    const path=new URL(request.url).pathname,now=Date.now(),s=this.state.storage;
    if(path==='/rate'){
      const rate=await s.get<{count:number;until:number}>('rate');
      if(rate&&rate.until>now&&rate.count>=10)return json({error:'Rate limited'},429);
      const next=rate&&rate.until>now?{...rate,count:rate.count+1}:{count:1,until:now+3600_000};
      await s.put('rate',next);await s.setAlarm(next.until);return json({ok:true});
    }
    if(path==='/init'){
      if(await s.get('config'))return json({error:'Conflict'},409);
      const body=await request.json() as {browserHash:string;phoneHash:string;expiresAt:number};
      if(!/^[a-f0-9]{64}$/.test(body.browserHash)||!/^[a-f0-9]{64}$/.test(body.phoneHash)||!Number.isSafeInteger(body.expiresAt)||body.expiresAt<=now||body.expiresAt>now+PAIR_TTL)return json({error:'Invalid'},400);
      await s.put('config',{...body,active:false,sequence:0} satisfies Config);await s.setAlarm(body.expiresAt);return json({ok:true},201);
    }
    const cfg=await s.get<Config>('config');
    if(!cfg)return json({error:'Channel unavailable'},404);
    if(cfg.expiresAt<=now){await s.deleteAll();return json({error:'Channel unavailable'},404);}
    const token=request.headers.get('Authorization')?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if(!token)return json({error:'Unauthorized'},401);
    const digest=await hash(token);
    const role=digest===cfg.browserHash?'browser':digest===cfg.phoneHash?'phone':undefined;
    if(!role)return json({error:'Unauthorized'},401);
    if(path==='/channel'&&request.method==='DELETE'){await s.deleteAll();await s.deleteAlarm();return new Response(null,{status:204});}
    if(path==='/activate'&&request.method==='POST'){
      // Browser activates only after both explicit SAS confirmations.
      if(role!=='browser')return json({error:'Forbidden'},403);
      cfg.active=true;cfg.expiresAt=now+DEVICE_TTL;await s.put('config',cfg);await s.setAlarm(now+120_000);return json({ok:true});
    }
    const messages=await s.list<StoredMessage>({prefix:'msg:'});
    for(const [key,m]of messages)if(m.expiresAt<=now){await s.delete(key);messages.delete(key);}
    if(path==='/messages'&&request.method==='POST'){
      const rateKey=`traffic:${role}`;let rate=await s.get<{until:number;count:number}>(rateKey);
      if(!rate||rate.until<=now)rate={until:now+60_000,count:0};
      if(rate.count>=30)return json({error:'Rate limited'},429);
      const b=parsedBody as Record<string,any>;
      if(!b||Object.keys(b).sort().join(',')!=='expiresAt,id,payload'||!ID.test(b.id)||!Number.isSafeInteger(b.expiresAt)||b.expiresAt<=now||b.expiresAt>Math.min(cfg.expiresAt,now+(cfg.active?120_000:PAIR_TTL))||typeof b.payload!=='string'||new TextEncoder().encode(b.payload).length>MAX_PAYLOAD)return json({error:'Invalid message'},400);
      if(await s.get(`seen:${b.id}`))return json({error:'Duplicate'},409);
      if(messages.size>=MAX_MESSAGES)return json({error:'Mailbox full'},429);
      const m:StoredMessage={id:b.id,expiresAt:b.expiresAt,payload:b.payload,sequence:++cfg.sequence,role};
      await s.put({[`msg:${String(m.sequence).padStart(16,'0')}`]:m,[`seen:${b.id}`]:b.expiresAt+PAIR_TTL,[rateKey]:{...rate,count:rate.count+1},config:cfg});
      await s.setAlarm(Math.min(now+120_000,cfg.expiresAt));return json({ok:true},201);
    }
    if(path==='/messages'&&request.method==='GET'){
      const afterRaw=new URL(request.url).searchParams.get('after')??'0';
      if(!/^\d{1,16}$/.test(afterRaw))return json({error:'Invalid cursor'},400);
      const after=Number(afterRaw);if(!Number.isSafeInteger(after)||after>cfg.sequence)return json({error:'Invalid cursor'},400);
      const visible=[...messages.values()].filter(m=>m.role!==role&&m.sequence>after).sort((a,b)=>a.sequence-b.sequence).map(({role:_,...m})=>m);
      // Refresh inactivity only for active channels; never prolong unconfirmed pairing.
      if(cfg.active&&cfg.expiresAt-now<DEVICE_TTL-3600_000){cfg.expiresAt=now+DEVICE_TTL;await s.put('config',cfg);}
      return json({messages:visible,cursor:cfg.sequence});
    }
    return json({error:'Not found'},404);
  }
  async alarm(){
    const s=this.state.storage,now=Date.now();const cfg=await s.get<Config>('config');
    if(!cfg||cfg.expiresAt<=now){await s.deleteAll();return;}
    for(const [k,m]of await s.list<StoredMessage>({prefix:'msg:'}))if(m.expiresAt<=now)await s.delete(k);
    for(const [k,expiry]of await s.list<number>({prefix:'seen:'}))if(expiry<=now)await s.delete(k);
    const remaining=await s.list({prefix:'msg:',limit:1});const tombstones=await s.list({prefix:'seen:',limit:1});
    await s.setAlarm(remaining.size||tombstones.size?Math.min(now+120_000,cfg.expiresAt):cfg.expiresAt);
  }
}
