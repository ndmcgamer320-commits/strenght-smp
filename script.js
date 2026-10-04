const IP="s1strength.mcsh.io";

const STATUS_APIS=[
  "https://minecraftstatus.com/api/status/java/"+encodeURIComponent(IP),
  "https://minecraftstatus.com/api/status/java/"+encodeURIComponent("144.31.46.15:12565")
];

const $=id=>document.getElementById(id);
let statusRequestRunning=false;
let lastGoodStatus=null;

function renderStatusText(state,players,max){
  $("statusText").textContent=state==="ONLINE"?"ONLINE":state;
  $("statusIcon").textContent=state==="ONLINE"?"●":state==="UNKNOWN"?"?":"○";
  $("statusIcon").style.color=state==="ONLINE"?"#34d399":state==="UNKNOWN"?"#f59e0b":"#fb7185";
  $("players").textContent=state==="ONLINE"
    ?((players??"—")+" / "+(max??"—"))
    :"— / —";
  $("heroPlayers").textContent=state==="ONLINE"?(players??"—"):"—";
  $("heroStatus").textContent=state;
  $("visualStatus").textContent=state==="ONLINE"
    ?"Server is online"
    :state==="OFFLINE"
      ?"Server is offline"
      :"Live status unavailable";
  $("visualPlayers").textContent=state==="ONLINE"
    ?((players??"—")+" players online")
    :state==="OFFLINE"
      ?"No players online"
      :"No fresh verified data";
}

function setStatus(state,players,max,checkedAt=null,source="MinecraftStatus.com"){
  renderStatusText(state,players,max);

  if(checkedAt){
    const time=new Date(checkedAt);
    $("statusMeta").textContent="Last checked "+time.toLocaleTimeString([],{
      hour:"2-digit",
      minute:"2-digit",
      second:"2-digit"
    })+" • "+source;
  }
}

async function getExternalStatus(url,signal){
  const response=await fetch(url,{
    cache:"no-store",
    signal,
    headers:{Accept:"application/json"}
  });

  if(response.status===429){
    throw new Error("RATE_LIMITED");
  }

  const data=await response.json().catch(()=>({}));

  // MinecraftStatus may return 202 while a bounded observation is being
  // completed. Follow the returned poll URL instead of calling it a failure.
  if(!response.ok){
    throw new Error("Status service returned "+response.status);
  }

  return normalizeStatusObservation(data);
}

function normalizeStatusObservation(data){
  const observedAt=Date.parse(data.observedAt||"");
  const validUntil=Date.parse(data.validUntil||"");

  if(data.freshness && data.freshness!=="fresh"){
    throw new Error("Observation not fresh");
  }

  if(validUntil && Date.now()>validUntil){
    throw new Error("Observation expired");
  }

  const state=
    data.verdict==="online"
      ?"ONLINE"
      :data.verdict==="offline"
        ?"OFFLINE"
        :"UNKNOWN";

  return {
    state,
    players:data.players?.online??null,
    max:data.players?.max??null,
    checkedAt:data.observedAt||null,
    validUntil,
    source:"MinecraftStatus.com"
  };
}


async function updateServer(manual=false){
  if(statusRequestRunning) return;
  statusRequestRunning=true;

  const refresh=$("refreshStatus");
  if(refresh){
    refresh.disabled=true;
    refresh.classList.add("spinning");
  }

  renderStatusText("CHECKING",null,null);
  $("statusMeta").textContent=manual
    ?"Testing MinecraftStatus.com..."
    :"Refreshing every 5 seconds...";

  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),4500);

  try{
    const results=await Promise.allSettled(
      STATUS_APIS.map(url=>getExternalStatus(url,controller.signal))
    );

    const successful=results
      .filter(result=>result.status==="fulfilled")
      .map(result=>result.value);

    const online=successful.find(result=>result.state==="ONLINE");

    if(online){
      lastGoodStatus=online;
      setStatus("ONLINE",online.players,online.max,online.checkedAt,online.source);
      return;
    }

    const allOffline=
      successful.length>0 &&
      successful.length===STATUS_APIS.length &&
      successful.every(result=>result.state==="OFFLINE");

    if(allOffline){
      const offline=successful[0];
      lastGoodStatus=null;
      setStatus("OFFLINE",null,null,offline.checkedAt,offline.source);
      return;
    }

    if(lastGoodStatus && lastGoodStatus.validUntil && Date.now()<lastGoodStatus.validUntil){
      setStatus(
        lastGoodStatus.state,
        lastGoodStatus.players,
        lastGoodStatus.max,
        lastGoodStatus.checkedAt,
        "MinecraftStatus.com • last valid observation"
      );
      return;
    }

    throw new Error("No fresh observation");
  }catch(error){
    if(lastGoodStatus && lastGoodStatus.validUntil && Date.now()<lastGoodStatus.validUntil){
      setStatus(
        lastGoodStatus.state,
        lastGoodStatus.players,
        lastGoodStatus.max,
        lastGoodStatus.checkedAt,
        "MinecraftStatus.com • last valid observation"
      );
    }else{
      $("statusText").textContent="UNKNOWN";
      $("statusIcon").textContent="?";
      $("statusIcon").style.color="#f59e0b";
      $("players").textContent="— / —";
      $("heroPlayers").textContent="—";
      $("heroStatus").textContent="UNKNOWN";
      $("visualStatus").textContent=error?.name==="AbortError"
        ?"Status request timed out"
        :"Live status unavailable";
      $("visualPlayers").textContent=String(error?.message||"No fresh verified data").replace(/^RATE_LIMITED/,"Status service rate limited");
      $("statusMeta").textContent=manual
        ?"Refresh failed — no fresh result"
        :"Waiting for a fresh status observation";
    }
  }finally{
    clearTimeout(timeout);
    statusRequestRunning=false;

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

function getBotURL(promptUser=false){
  let url=window.STRENGTH_BOT_API_URL||sessionStorage.getItem("strengthBotURL")||"";
  if(!url&&promptUser){
    url=(prompt("Enter your deployed bot service URL:")||"").trim();
    if(url) sessionStorage.setItem("strengthBotURL",url);
  }
  return url.replace(/\/$/,"");
}

function botUnavailable(){
  $("botStatus").textContent="SERVICE NOT CONNECTED";
  $("botDetail").textContent="Deploy the bot service, then use CONNECT to add its URL.";
}

async function botRequest(path,options={}){
  const url=getBotURL(true);
  if(!url){
    botUnavailable();
    throw new Error("Bot service URL is not configured");
  }

  const key=getBotKey();
  if(!key) throw new Error("Bot API key not provided");

  const response=await fetch(url+path,{
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
  if(!getBotURL(false)){botUnavailable();return;}

  try{
    const data=await botRequest("/state");
    $("botStatus").textContent=data.connected?"ONLINE":"OFFLINE";
    $("botDetail").textContent=data.username
      ?data.username+" • "+(data.host||IP)+":"+data.port
      :"Bot service reachable";
    $("botLog").textContent=data.lastEvent||"Bot status refreshed.";
  }catch(error){
    $("botStatus").textContent="UNAVAILABLE";
    $("botDetail").textContent=error.message;
  }
}

async function connectBot(){
  try{
    const data=await botRequest("/connect",{method:"POST",body:"{}"});
    $("botStatus").textContent=data.connected?"ONLINE":"CONNECTING";
    $("botDetail").textContent=data.message||"Connection requested.";
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
    $("botLog").textContent=data.output||data.message||"Command sent.";
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
setInterval(()=>updateServer(false),5000);
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
