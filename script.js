const IP="strenght.mcsh.oi";const API="https://api.mcsrvstat.us/3/"+encodeURIComponent(IP);
const $=id=>document.getElementById(id);
function setStatus(online,players,max,version){
  $("statusText").innerHTML=online?"ONLINE":"OFFLINE";
  $("statusIcon").textContent=online?"●":"○";$("statusIcon").style.color=online?"#34d399":"#fb7185";
  $("players").textContent=online?players+" / "+max:"0 / —";
  $("heroPlayers").textContent=online?players:"0";$("heroStatus").textContent=online?"ONLINE":"OFFLINE";
  $("visualStatus").textContent=online?"Server is online":"Server is offline";
  $("visualPlayers").textContent=online?(players+" players online"):"Check back soon";
}
async function updateServer(){try{const r=await fetch(API,{cache:"no-store"});if(!r.ok)throw Error();const d=await r.json();setStatus(!!d.online,d.players?.online??0,d.players?.max??0,d.version||"")}catch(e){setStatus(false,0,0,"")}}
function copy(){navigator.clipboard?.writeText(IP);$("toast").classList.add("show");setTimeout(()=>$("toast").classList.remove("show"),1800)}
["copyHero","copyIp","copyBottom"].forEach(id=>$(id).addEventListener("click",copy));
updateServer();setInterval(updateServer,30000);
const obs=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting)e.target.classList.add("seen")}),{threshold:.12});
document.querySelectorAll("section,article").forEach(e=>{e.style.opacity="0";e.style.transform="translateY(18px)";e.style.transition="opacity .7s ease,transform .7s ease";obs.observe(e)});
const css=document.createElement("style");css.textContent=".seen{opacity:1!important;transform:none!important}";document.head.appendChild(css);
