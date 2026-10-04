const IP="strenght.mcsh.oi";
const STATUS_URL="https://raw.githubusercontent.com/ndmcgamer320-commits/strenght-smp/status-data/status.json";
const STALE_AFTER=12*60*1000;

const $=id=>document.getElementById(id);

function setStatus(state,players,max){
  const online=state==="ONLINE";
  const checking=state==="CHECKING";

  $("statusText").textContent=checking?"CHECKING":online?"ONLINE":state;
  $("statusIcon").textContent=checking?"◌":online?"●":state==="UNKNOWN"?"?":"○";
  $("statusIcon").style.color=checking?"#a78bfa":online?"#34d399":state==="UNKNOWN"?"#f59e0b":"#fb7185";

  $("players").textContent=checking?"— / —":online
    ? ((players ?? "—")+" / "+(max ?? "—"))
    : "— / —";

  $("heroPlayers").textContent=checking?"—":online?(players ?? "—"):"—";
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
      ?((players ?? "—")+" players online")
      :"No live data available";
}

async function updateServer(){
  setStatus("CHECKING",null,null);

  try{
    const response=await fetch(STATUS_URL+"?t="+Date.now(),{
      cache:"no-store",
      headers:{"Accept":"application/json"}
    });

    if(!response.ok) throw new Error("Status file unavailable");

    const data=await response.json();
    const checkedAt=Date.parse(data.checkedAt || "");

    // Never display old OFFLINE data as current.
    if(!checkedAt || Date.now()-checkedAt>STALE_AFTER){
      throw new Error("Status data is stale");
    }

    if(data.online===true){
      setStatus("ONLINE",data.players?.online ?? data.players,data.players?.max ?? data.max);
    }else if(data.online===false && data.state!=="UNKNOWN"){
      setStatus("OFFLINE",null,null);
    }else{
      setStatus("UNKNOWN",null,null);
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

updateServer();
setInterval(updateServer,15000);

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
