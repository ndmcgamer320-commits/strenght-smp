const IP="strenght.mcsh.oi";
const API="https://api.mcstatus.io/v2/status/java/"+encodeURIComponent(IP)+"?query=true&timeout=5";

const $=id=>document.getElementById(id);

function setStatus(state,players,max){
  const online=state==="ONLINE";
  const checking=state==="CHECKING";

  $("statusText").innerHTML=checking?"CHECKING":online?"ONLINE":"OFFLINE";
  $("statusIcon").textContent=checking?"◌":online?"●":"○";
  $("statusIcon").style.color=checking?"#a78bfa":online?"#34d399":"#fb7185";

  $("players").textContent=checking?"— / —":online
    ? ((players ?? 0)+" / "+(max ?? "—"))
    : "— / —";

  $("heroPlayers").textContent=checking?"—":online?(players ?? 0):"—";
  $("heroStatus").textContent=state;
  $("visualStatus").textContent=checking?"Checking server...":online?"Server is online":"Server is offline";
  $("visualPlayers").textContent=checking?"Verifying live server data":online
    ? ((players ?? 0)+" players online")
    :"No live data available";
}

async function updateServer(){
  setStatus("CHECKING",null,null);

  try{
    const response=await fetch(API,{cache:"no-store"});
    if(!response.ok) throw new Error("Status request failed");

    const data=await response.json();

    // Never invent player counts. Only display values actually returned by
    // the Minecraft status protocol.
    if(data.online===true){
      setStatus("ONLINE",data.players?.online ?? 0,data.players?.max ?? null);
    }else{
      setStatus("OFFLINE",null,null);
    }
  }catch(error){
    // A failed status request is UNKNOWN, not automatically OFFLINE.
    $("statusText").innerHTML="UNKNOWN";
    $("statusIcon").textContent="?";
    $("statusIcon").style.color="#f59e0b";
    $("players").textContent="— / —";
    $("heroPlayers").textContent="—";
    $("heroStatus").textContent="UNKNOWN";
    $("visualStatus").textContent="Live status unavailable";
    $("visualPlayers").textContent="No data received";
  }
}

async function copy(){
  try{await navigator.clipboard.writeText(IP)}catch{}
  $("toast").classList.add("show");
  setTimeout(()=>$("toast").classList.remove("show"),1800);
}

["copyHero","copyIp","copyBottom"].forEach(id=>$(id).addEventListener("click",copy));

updateServer();
setInterval(updateServer,30000);

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
