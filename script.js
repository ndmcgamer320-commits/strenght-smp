const IP="strenght.mcsh.oi";

// Use two independent status relays. We never invent a player count.
// If either live check confirms the server is online, show ONLINE.
const STATUS_APIS=[
  "https://mc.fizzystudio.xyz/status/"+encodeURIComponent(IP),
  "https://api.mcstatus.io/v2/status/java/"+encodeURIComponent(IP)+"?query=false&timeout=5"
];

const $=id=>document.getElementById(id);

function setStatus(state,players,max){
  const online=state==="ONLINE";
  const checking=state==="CHECKING";

  $("statusText").textContent=checking?"CHECKING":online?"ONLINE":"OFFLINE";
  $("statusIcon").textContent=checking?"◌":online?"●":"○";
  $("statusIcon").style.color=checking?"#a78bfa":online?"#34d399":"#fb7185";

  $("players").textContent=checking?"— / —":online
    ? ((players ?? "—")+" / "+(max ?? "—"))
    : "— / —";

  $("heroPlayers").textContent=checking?"—":online?(players ?? "—"):"—";
  $("heroStatus").textContent=state;
  $("visualStatus").textContent=checking
    ?"Checking server..."
    :online
      ?"Server is online"
      :"Server is offline";
  $("visualPlayers").textContent=checking
    ?"Verifying live server data"
    :online
      ?((players ?? "—")+" players online")
      :"No live data available";
}

async function fetchStatus(url){
  const response=await fetch(url,{
    cache:"no-store",
    headers:{"Accept":"application/json"}
  });

  if(!response.ok) throw new Error("Status request failed: "+response.status);

  const data=await response.json();

  return {
    online:data.online===true,
    players:data.players?.online ?? null,
    max:data.players?.max ?? null
  };
}

async function updateServer(){
  setStatus("CHECKING",null,null);

  const results=await Promise.allSettled(
    STATUS_APIS.map(fetchStatus)
  );

  const successful=results
    .filter(result=>result.status==="fulfilled")
    .map(result=>result.value);

  // If any independent status provider confirms ONLINE, trust that
  // confirmation instead of allowing a stale OFFLINE result to win.
  const online=successful.find(result=>result.online===true);

  if(online){
    setStatus("ONLINE",online.players,online.max);
    return;
  }

  // Only show OFFLINE when every successful provider says OFFLINE.
  if(successful.length===STATUS_APIS.length && successful.every(result=>result.online===false)){
    setStatus("OFFLINE",null,null);
    return;
  }

  // A failed/unreachable status service is not proof that the Minecraft
  // server is offline.
  $("statusText").textContent="UNKNOWN";
  $("statusIcon").textContent="?";
  $("statusIcon").style.color="#f59e0b";
  $("players").textContent="— / —";
  $("heroPlayers").textContent="—";
  $("heroStatus").textContent="UNKNOWN";
  $("visualStatus").textContent="Live status unavailable";
  $("visualPlayers").textContent="No data received";
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
  if(e.isIntersecting)e.target.classList.add("seen")
}),{threshold:.12});

document.querySelectorAll("section,article").forEach(e=>{
  e.style.opacity="0";
  e.style.transform="translateY(18px)";
  e.style.transition="opacity .7s ease,transform .7s ease";
  obs.observe(e)
});

const css=document.createElement("style");
css.textContent=".seen{opacity:1!important;transform:none!important}";
document.head.appendChild(css);
