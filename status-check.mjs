import dns from "node:dns/promises";
import net from "node:net";
import fs from "node:fs/promises";

const ADDRESS = process.env.STATUS_HOST || "strenght.mcsh.oi";
const TIMEOUT = 8000;
const PROTOCOL = 767;

function varInt(value){
  const out=[];
  let n=value>>>0;
  do{
    let byte=n&0x7f;
    n>>>=7;
    if(n!==0) byte|=0x80;
    out.push(byte);
  }while(n!==0);
  return Buffer.from(out);
}

function minecraftString(value){
  const data=Buffer.from(value,"utf8");
  return Buffer.concat([varInt(data.length),data]);
}

async function resolveTarget(address){
  let host=address;
  let port=25565;

  const match=address.match(/^(.*):(\d+)$/);
  if(match && !address.includes("]")){
    host=match[1];
    port=Number(match[2]);
    return {host,port,handshakeHost:host};
  }

  try{
    const records=await dns.resolveSrv("_minecraft._tcp."+host);
    if(records.length){
      records.sort((a,b)=>a.priority-b.priority || b.weight-a.weight);
      return {
        host:records[0].name.replace(/\.$/,""),
        port:records[0].port,
        handshakeHost:host
      };
    }
  }catch{}

  return {host,port,handshakeHost:host};
}

function createReader(socket){
  let buffer=Buffer.alloc(0);
  let ended=null;
  const waiters=[];

  function flush(){
    while(waiters.length){
      const waiter=waiters[0];
      if(buffer.length<waiter.count) break;
      const value=buffer.subarray(0,waiter.count);
      buffer=buffer.subarray(waiter.count);
      waiters.shift().resolve(value);
    }
    if(ended){
      while(waiters.length){
        waiters.shift().reject(ended);
      }
    }
  }

  socket.on("data",chunk=>{
    buffer=Buffer.concat([buffer,chunk]);
    flush();
  });
  socket.on("end",()=>{
    ended=new Error("Server closed the connection");
    flush();
  });
  socket.on("error",error=>{
    ended=error;
    flush();
  });

  return {
    read(count){
      if(buffer.length>=count){
        const value=buffer.subarray(0,count);
        buffer=buffer.subarray(count);
        return Promise.resolve(value);
      }
      if(ended) return Promise.reject(ended);
      return new Promise((resolve,reject)=>waiters.push({count,resolve,reject}));
    }
  };
}

async function readVarInt(reader){
  let value=0;
  let shift=0;

  for(let i=0;i<5;i++){
    const byte=(await reader.read(1))[0];
    value |= (byte & 0x7f) << shift;
    if((byte & 0x80)===0) return value;
    shift+=7;
  }

  throw new Error("Invalid VarInt");
}

function packet(payload){
  return Buffer.concat([varInt(payload.length),payload]);
}

async function pingMinecraft(){
  const target=await resolveTarget(ADDRESS);

  return await new Promise((resolve,reject)=>{
    const socket=net.createConnection({
      host:target.host,
      port:target.port
    });

    let timer=setTimeout(()=>{
      socket.destroy();
      reject(new Error("Connection timed out"));
    },TIMEOUT);

    socket.once("connect",async()=>{
      try{
        const reader=createReader(socket);

        const handshake=Buffer.concat([
          Buffer.from([0x00]),
          varInt(PROTOCOL),
          minecraftString(target.handshakeHost),
          Buffer.from([(target.port>>8)&0xff,target.port&0xff]),
          Buffer.from([0x01])
        ]);

        socket.write(packet(handshake));
        socket.write(packet(Buffer.from([0x00])));

        await readVarInt(reader);
        const responseLength=await readVarInt(reader);
        const response=await reader.read(responseLength);

        let offset=0;
        const packetId=response[offset++];
        if(packetId!==0x00) throw new Error("Unexpected status packet");

        let shift=0;
        let jsonLength=0;
        for(let i=0;i<5;i++){
          const byte=response[offset++];
          jsonLength |= (byte&0x7f)<<shift;
          if((byte&0x80)===0) break;
          shift+=7;
        }

        const json=JSON.parse(response.subarray(offset,offset+jsonLength).toString("utf8"));

        clearTimeout(timer);
        socket.destroy();
        resolve({
          online:true,
          players:json.players?.online ?? null,
          max:json.players?.max ?? null,
          version:json.version?.name ?? null,
          motd:json.description?.text ?? json.description?.extra?.map(x=>x.text||"").join("") ?? "",
          checkedAt:new Date().toISOString(),
          source:"direct-java-status-ping"
        });
      }catch(error){
        clearTimeout(timer);
        socket.destroy();
        reject(error);
      }
    });

    socket.once("error",error=>{
      clearTimeout(timer);
      reject(error);
    });
  });
}

try{
  const status=await pingMinecraft();
  await fs.writeFile("status.json",JSON.stringify(status,null,2)+"\n");
  console.log(JSON.stringify(status));
}catch(error){
  const status={
    online:null,
    state:"UNKNOWN",
    players:null,
    max:null,
    version:null,
    motd:"",
    checkedAt:new Date().toISOString(),
    source:"direct-java-status-ping",
    error:error?.message || "Unable to reach server"
  };
  await fs.writeFile("status.json",JSON.stringify(status,null,2)+"\n");
  console.log(JSON.stringify(status));
}
