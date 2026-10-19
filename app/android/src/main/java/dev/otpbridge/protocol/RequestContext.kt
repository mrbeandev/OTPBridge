package dev.otpbridge.protocol

import org.json.JSONObject

object RequestContext {
 fun validate(c:JSONObject){
  require(c.toString().length<=4096)
  keys(c,setOf("device","browser","publicIp","location"))
  if(c.has("publicIp")){val ip=text(c,"publicIp",45);require(ip.matches(Regex("(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9a-fA-F:]+)")))}
  if(c.has("device")){val d=c.get("device");require(d is JSONObject);keys(d,setOf("name","os","hardwareConcurrency","memoryGb"));text(d,"name");if(d.has("os"))text(d,"os");if(d.has("hardwareConcurrency"))number(d,"hardwareConcurrency",1.0,1024.0,true);if(d.has("memoryGb"))number(d,"memoryGb",0.25,1024.0)}
  if(c.has("browser")){val b=c.get("browser");require(b is JSONObject);keys(b,setOf("name","version","language"));text(b,"name");text(b,"version");if(b.has("language"))text(b,"language")}
  if(c.has("location")){val l=c.get("location");require(l is JSONObject);keys(l,setOf("latitude","longitude","accuracyMeters","capturedAt"));number(l,"latitude",-90.0,90.0);number(l,"longitude",-180.0,180.0);number(l,"accuracyMeters",0.0,10000000.0);number(l,"capturedAt",0.0,9007199254740991.0,true)}
 }
 private fun keys(o:JSONObject,allowed:Set<String>){require(o.keys().asSequence().all{it in allowed})}
 private fun text(o:JSONObject,k:String,max:Int=240):String{val v=o.get(k);require(v is String&&v.isNotEmpty()&&v.length<=max&&StrictJson.validUnicode(v));return v}
 private fun number(o:JSONObject,k:String,min:Double,max:Double,integer:Boolean=false){val v=o.get(k);require(v is Number);if(integer)require(v is Int||v is Long);require(v.toDouble().isFinite()&&v.toDouble() in min..max)}
 fun lines(c:JSONObject):List<String>{validate(c);val result=mutableListOf<String>();c.optJSONObject("device")?.let{d->result.add("Device: ${d.getString("name")}");if(d.has("os"))result.add("System: ${d.getString("os")}");if(d.has("hardwareConcurrency"))result.add("Logical CPUs: ${d.getInt("hardwareConcurrency")}");if(d.has("memoryGb"))result.add("Memory hint: ${d.getDouble("memoryGb")} GB")};c.optJSONObject("browser")?.let{result.add("Browser: ${it.getString("name")} ${it.getString("version")}");if(it.has("language"))result.add("Language: ${it.getString("language")}")};if(c.has("publicIp"))result.add("Public IP: ${c.getString("publicIp")}");c.optJSONObject("location")?.let{result.add("Location: ${it.getDouble("latitude")}, ${it.getDouble("longitude")}");result.add("Accuracy: ±${it.getDouble("accuracyMeters").toInt()} m · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.getLong("capturedAt")))}")};return result}
}
