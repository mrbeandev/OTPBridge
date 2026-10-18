import type {FillRequest,Keys,PairQr,PairResponse,keyPair} from '@otpbridge/protocol';
export interface Device{relay:string;channelId:string;token:string;keys:Keys;browserName:string;phoneName:string;cursor:number;accounts:Record<string,string>;seen:Record<string,number>}
export interface Pairing{qr:PairQr;own:ReturnType<typeof keyPair>;browserToken:string;cursor:number;response?:PairResponse;keys?:Keys;localConfirmed:boolean;phoneConfirmed:boolean;seen:Record<string,number>;readyEnvelope?:{id:string;expiresAt:number;payload:string}}
export interface Target{tabId:number;frameId:number;documentId:string;fieldToken:string;origin:string}
export interface Pending{request:FillRequest;target:Target;outbound:{id:string;expiresAt:number;payload:string};sent:boolean}
export interface State{device?:Device;pairing?:Pairing;pending?:Pending;status:string;error?:string}
export async function load():Promise<State>{const s=await chrome.storage.local.get('state');return (s.state as State|undefined)??{status:'Set up your relay and pair a phone.'};}
export async function save(s:State){await chrome.storage.local.set({state:s});}
export async function restrictStorage(){await chrome.storage.local.setAccessLevel({accessLevel:'TRUSTED_CONTEXTS'});}
