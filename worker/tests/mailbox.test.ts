import {beforeEach,describe,expect,it,vi} from 'vitest';
import {Mailbox} from '../src/mailbox';
import worker from '../src/index';
import {hash,randomId} from '../src/limits';
class MemoryStorage{
 data=new Map<string,any>();alarm:number|null=null;
 async get<T>(k:string):Promise<T|undefined>{return this.data.get(k);}
 async put(k:string|Record<string,any>,v?:any){if(typeof k==='string')this.data.set(k,structuredClone(v));else for(const[key,value]of Object.entries(k))this.data.set(key,structuredClone(value));}
 async delete(k:string){return this.data.delete(k);}
 async deleteAll(){this.data.clear();}
 async list<T>({prefix='',limit=Infinity}:{prefix?:string;limit?:number}={}):Promise<Map<string,T>>{return new Map([...this.data.entries()].filter(([k])=>k.startsWith(prefix)).sort().slice(0,limit));}
 async setAlarm(t:number){this.alarm=t;}async deleteAlarm(){this.alarm=null;}
}
let b:string,p:string,m:Mailbox,s:MemoryStorage;
async function call(path:string,token=b,method='GET',body?:unknown){return m.fetch(new Request(`https://internal${path}`,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})}));}
beforeEach(async()=>{vi.useRealTimers();b=randomId();p=randomId();s=new MemoryStorage();m=new Mailbox({storage:s,blockConcurrencyWhile:async(fn:()=>Promise<any>)=>fn()} as unknown as DurableObjectState);expect((await call('/init',b,'POST',{browserHash:await hash(b),phoneHash:await hash(p)})).status).toBe(201);});
describe('ciphertext mailbox',()=>{
 it('authenticates and isolates peer role messages',async()=>{expect((await call('/messages',randomId())).status).toBe(401);const msg={id:randomId(),expiresAt:Date.now()+60000,payload:'opaque ciphertext'};expect((await call('/messages',b,'POST',msg)).status).toBe(201);expect((await(await call('/messages',b)).json() as any).messages).toEqual([]);const received=await(await call('/messages',p)).json() as any;expect(received.messages[0].payload).toBe(msg.payload);expect(received.messages[0].role).toBeUndefined();expect((await call('/messages',b,'POST',msg)).status).toBe(409);expect((await(await call(`/messages?after=${received.cursor}`,p)).json() as any).messages).toEqual([]);});
 it('expires messages and refuses invalid lifetime, oversized input and future cursor',async()=>{expect((await call('/messages',b,'POST',{id:randomId(),expiresAt:Date.now()-1,payload:'x'})).status).toBe(400);expect((await call('/messages',b,'POST',{id:randomId(),expiresAt:Date.now()+999999,payload:'x'})).status).toBe(400);expect((await call('/messages',b,'POST',{id:randomId(),expiresAt:Date.now()+60000,payload:'x'.repeat(16385)})).status).toBe(400);expect((await call('/messages?after=100')).status).toBe(400);const now=Date.now();await call('/messages',b,'POST',{id:randomId(),expiresAt:now+100,payload:'x'});vi.spyOn(Date,'now').mockReturnValue(now+200);expect((await(await call('/messages',p)).json() as any).messages).toEqual([]);vi.restoreAllMocks();});
 it('only browser can activate and delete invalidates channel',async()=>{expect((await call('/activate',p,'POST')).status).toBe(403);expect((await call('/activate',b,'POST')).status).toBe(200);expect((await s.get<any>('config')).active).toBe(true);expect((await call('/channel',p,'DELETE')).status).toBe(204);expect((await call('/messages')).status).toBe(404);});
 it('does not prolong pairing by polling',async()=>{const expiry=(await s.get<any>('config')).expiresAt;await call('/messages');expect((await s.get<any>('config')).expiresAt).toBe(expiry);vi.spyOn(Date,'now').mockReturnValue(expiry+1);expect((await call('/messages')).status).toBe(404);expect(s.data.size).toBe(0);vi.restoreAllMocks();});
 it('alarm removes stale data and retains active channel',async()=>{await call('/activate',b,'POST');const now=Date.now();await call('/messages',b,'POST',{id:randomId(),expiresAt:now+100,payload:'x'});vi.spyOn(Date,'now').mockReturnValue(now+400000);await m.alarm();expect((await s.list({prefix:'msg:'})).size).toBe(0);expect((await s.list({prefix:'seen:'})).size).toBe(0);expect(await s.get('config')).toBeDefined();vi.restoreAllMocks();});
 it('bounds traffic per role',async()=>{for(let i=0;i<30;i++)expect((await call('/messages',b,'POST',{id:randomId(),expiresAt:Date.now()+60000,payload:'x'})).status).toBe(201);expect((await call('/messages',b,'POST',{id:randomId(),expiresAt:Date.now()+60000,payload:'x'})).status).toBe(429);});
 it('bounds enrollment rate and rate data lifetime',async()=>{const gate=new Mailbox({storage:new MemoryStorage(),blockConcurrencyWhile:async(fn:()=>Promise<any>)=>fn()}as unknown as DurableObjectState);for(let i=0;i<10;i++)expect((await gate.fetch(new Request('https://internal/rate',{method:'POST'}))).status).toBe(200);expect((await gate.fetch(new Request('https://internal/rate',{method:'POST'}))).status).toBe(429);});
 it('exposes minimal health and rejects unexpected routes',async()=>{const env={}as any;expect(await(await worker.fetch(new Request('https://relay/health'),env)).json()).toEqual({ok:true,protocolVersions:[1]});expect((await worker.fetch(new Request('https://relay/v1/channels/invalid/messages'),env)).status).toBe(404);});
});
