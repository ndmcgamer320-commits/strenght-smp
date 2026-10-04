const IP="s1strength.mcsh.io";
const STATUS_URL="https://api.github.com/repos/ndmcgamer320-commits/strenght-smp/contents/status.json?ref=status-data";
const STALE_AFTER=12*60*1000;

// Set BOT_API_URL to your deployed bot service URL.
// Never put the bot API key in this file. The browser asks for it when needed.
const BOT_API_URL=window.STRENGTH_BOT_API_URL||"";

const $=id=>document.getElementById(id);

function setStatus(state,players,max,checkedAt=null){
  const online=state==="ONLINE";
  const checking=state==="CHECKING";

  $("statusText").textContent=checking?"CHECKING":online?"ONLINE":state;
  $("statusIcon").textContent=checking?"◌":online?"●":state==="UNKNOWN"?"?":"○";
  $("statusIcon").style.color=checking?"#a78bfa":online?"#34d399":state==="UNKNOWN"?"#f59e0b":"#fb7185";

  $("players").textContent=checking?"— / —":online
    ?((players??"—")+" / "+(max??"—"))
    :"— / —";

  $("heroPlayers").textContent=checking?"—":online?(players??"—"):"—";
  $("heroStatus").textContent=state;
  $("visualStatus").textContent=checking
    ?"Checking live server data..."
    :online
      ?"Server is online"
      :state==="UNKNOWN"
        ?"Live status unavailable"
        :"Server is offline";

  $("visualPlayers").textContent=checking
    ?"Contacting the server monitor"
    :online
      ?((players??"—")+" players online")
      :"No live data available";

  if(checkedAt){
    const time=new Date(checkedAt);
    $("statusMeta").textContent="Last checked "+time.toLocaleTimeString([],{
      hour:"2-digit",minute:"2-digit",second:"2-digit"
    })+" • Direct Minecraft ping";
  }
}

async function updateServer(manual=false){
  const refresh=$("refreshStatus");
  if(refresh){
    refresh.disabled=true;
    refresh.classList.add("spinning");
  }

  setStatus("CHECKING",null,null);

  try{
    const response=await fetch(STATUS_URL+"&t="+Date.now(),{
      cache:"no-store",
      headers:{Accept:"application/json"}
    });

    if(!response.ok) throw new Error("Status file unavailable");

    const wrapper=await response.json();
    if(!wrapper.content) throw new Error("No status content received");

    const data=JSON.parse(atob(wrapper.content.replace(/\\s/g,"")));
    const checkedAt=Date.parse(data.checkedAt||"");

    if(!checkedAt||Date.now()-checkedAt>STALE_AFTER){
      throw new Error("Status data is stale");
    }

    if(data.online===true){
      setStatus("ONLINE",data.players?.online??data.players,data.players?.max??data.max,data.checkedAt);
    }else if(data.online===false&&data.state!=="UNKNOWN"){
      setStatus("OFFLINE",null,null,data.checkedAt);
    }else{
      setStatus("UNKNOWN",null,null,data.checkedAt);
    }
  }catch(error){
    $("statusText").textContent="UNKNOWN";
    $("statusIcon").textContent="?";
    $("statusIcon").style.color="#f59e0b";
    $("players").textContent="— / —";
    $("heroPlayers").textContent="—";
    $("heroStatus").textContent="UNKNOWN";
    $("visualStatus").textContent="Live status unavailable";
    $("visualPlayers").textContent="No fresh status data";
    $("statusMeta").textContent=manual
      ?"Refresh failed — no fresh status received"
      :"Waiting for a fresh server check";
  }finally{
    if(refresh){
      refresh.disabled=false;
      refresh.classList.remove("spinning");
    }
  }
}

function getBotKey(){
  let key=sessionStorage.getItem("strengthBotKey");
  if(!key){
    key=prompt("Enter your private bot API key:");
    if(key) sessionStorage.setItem("strengthBotKey",key);
  }
  return key||"";
}

function botUnavailable(){
  $("botStatus").textContent="SERVICE NOT CONNECTED";
  $("botDetail").textContent="Set STRENGTH_BOT_API_URL in the page deployment to enable the bot.";
}

async function botRequest(path,options={}){
  if(!BOT_API_URL){
    botUnavailable();
    throw new Error("Bot service URL is not configured");
  }

  const key=getBotKey();
  if(!key) throw new Error("Bot API key not provided");

  const response=await fetch(BOT_API_URL.replace(/\/$/,"")+path,{
    ...options,
    headers:{
      "Content-Type":"application/json",
      "X-API-Key":key,
      ...(options.headers||{})
    }
  });

  const data=await response.json().catch(()=>({}));
  if(!response.ok) throw new Error(data.error||"Bot request failed");
  return data;
}

async function refreshBot(){
  if(!BOT_API_URL){botUnavailable();return;}

  try{
    const data=await botRequest("/state");
    $("botStatus").textContent=data.connected?"ONLINE":"OFFLINE";
    $("botDetail").textContent=data.username
      ?data.username+" • "+(data.host||IP)
      :"Bot service reachable";
    $("botLog").textContent=data.message||"Bot status refreshed.";
  }catch(error){
    $("botStatus").textContent="UNAVAILABLE";
    $("botDetail").textContent=error.message;
  }
}

async function connectBot(){
  try{
    const data=await botRequest("/connect",{method:"POST",body:"{}"});
    $("botStatus").textContent=data.connected?"CONNECTING":"OFFLINE";
    $("botDetail").textContent=data.message||"Connect requested.";
    $("botLog").textContent=data.message||"";
  }catch(error){
    $("botLog").textContent="ERROR: "+error.message;
  }
}

async function disconnectBot(){
  try{
    const data=await botRequest("/disconnect",{method:"POST",body:"{}"});
    $("botStatus").textContent="OFFLINE";
    $("botDetail").textContent=data.message||"Disconnected.";
    $("botLog").textContent=data.message||"";
  }catch(error){
    $("botLog").textContent="ERROR: "+error.message;
  }
}

async function sendBotCommand(){
  const input=$("botCommand");
  const command=input.value.trim();
  if(!command) return;

  try{
    const data=await botRequest("/command",{
      method:"POST",
      body:JSON.stringify({command})
    });
    $("botLog").textContent=(data.output||data.message||"Command sent.");
    input.value="";
  }catch(error){
    $("botLog").textContent="ERROR: "+error.message;
  }
}

async function copy(){
  try{await navigator.clipboard.writeText(IP)}catch{}
  $("toast").classList.add("show");
  setTimeout(()=>$("toast").classList.remove("show"),1800);
}

["copyHero","copyIp","copyBottom"].forEach(id=>{
  const button=$(id);
  if(button) button.addEventListener("click",copy);
});

$("refreshStatus")?.addEventListener("click",()=>updateServer(true));
$("botConnect")?.addEventListener("click",connectBot);
$("botDisconnect")?.addEventListener("click",disconnectBot);
$("sendCommand")?.addEventListener("click",sendBotCommand);
$("botCommand")?.addEventListener("keydown",event=>{
  if(event.key==="Enter") sendBotCommand();
});

updateServer();
setInterval(updateServer,15000);
refreshBot();
setInterval(refreshBot,15000);

const obs=new IntersectionObserver(es=>es.forEach(e=>{
  if(e.isIntersecting)e.target.classList.add("seen");
}),{threshold:.12});

document.querySelectorAll("section,article").forEach(e=>{
  e.style.opacity="0";
  e.style.transform="translateY(18px)";
  e.style.transition="opacity .7s ease,transform .7s ease";
  obs.observe(e);
});

const css=document.createElement("style");
css.textContent=".seen{opacity:1!important;transform:none!important}";
document.head.appendChild(css);
