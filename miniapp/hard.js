"use strict";
window.onerror = msg => { document.getElementById("error").textContent = "Ошибка: " + msg; };
const tg = window.Telegram && window.Telegram.WebApp;
const insideTelegram = !!(tg && tg.platform !== "unknown");
function decodeState() {
  const raw = new URLSearchParams(location.search).get("s");
  if (!raw) throw Error("Откройте свежую кнопку «Приказы на ход …» в чате бота.");
  let b64 = raw.replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4) b64 += "=";
  const value = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b64), c => c.charCodeAt(0))));
  if (!value.hard || value.v !== 2 || !value.g) throw Error("Несовместимая ссылка. Откройте /panel в боте.");
  return value;
}
const S = decodeState();
let queue = S.q.map(o => o.slice());
let ready = !!S.r;
const cityAmounts = S.ci.map(() => "100");
let ecoAmount = "100";
let transferAmount = "100";
let recipient = S.o[0]?.[0];
const count = type => queue.filter(o => o[0] === type).length;
const alive = () => S.ci.some(c => c[4] === 1);
const price = o => ({r:S.cost[0],p:S.cost[1],s:S.cost[2],b:S.cost[3],l:0,n:0}[o[0]] ?? (o[0] === "i" ? o[2] : o[1]));
const available = () => S.b - queue.reduce((s,o) => s + price(o), 0);
const freeBombs = () => S.m + count("p") - count("l");
const targets = [...S.o, [S.mi, S.c, S.ci.map(c => [c[0], c[4]])]];
const nameOf = k => targets.find(c => c[0] === k)?.[1] ?? "?";
const short = n => Number(n.toFixed(1));
const validAmount = n => Number.isSafeInteger(n) && n > 0 && n <= 1000000;
function can(o) {
  if (!alive() && o[0] !== "b") return false;
  if (available() < price(o)) return false;
  const city = S.ci[o[1]];
  switch(o[0]) {
    case "i": return !!city && city[4] === 1 && (city[1] < 100 || city[2] < 100) && validAmount(o[2]);
    case "e": return validAmount(o[1]);
    case "s": return !!city && city[4] === 1 && !city[3] && !queue.some(x => x[0] === "s" && x[1] === o[1]);
    case "b": return !!city && city[4] === 0 && !queue.some(x => x[0] === "b" && x[1] === o[1]);
    case "r": return S.tech === 0 && count("r") === 0;
    case "p": return S.tech === 2 && (S.limit[0] === 0 || count("p") < S.limit[0]);
    case "l": return freeBombs() > 0 && !S.limit[2] && (S.limit[1] < 0 || count("l") < S.limit[1]) && targets.some(c => c[0] === o[1] && c[2][o[2]]?.[1] === 1);
    case "n": return S.o.some(c => c[0] === o[1] && c[2].some(x => x[1] === 1)) && !queue.some(x => x[0] === "n" && x[1] === o[1]);
  }
  return false;
}
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for(const [k,v] of Object.entries(attrs)) {
    if(k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if(k === "disabled" || k === "checked") node[k] = !!v;
    else node.setAttribute(k,v);
  }
  children.flat(Infinity).filter(x => x != null).forEach(x => node.append(x instanceof Node ? x : document.createTextNode(String(x))));
  return node;
}
function confirmAction(text, action) {
  const dialog = el("dialog", {}, el("p", {}, text),
    el("button", {onclick: () => {dialog.close(); dialog.remove(); action();}}, "Да"),
    el("button", {onclick: () => {dialog.close(); dialog.remove();}}, "Нет"));
  dialog.addEventListener("cancel", () => dialog.remove());
  document.body.append(dialog); dialog.showModal();
}
function add(order) {
  if(!can(order)) { alert("Приказ недоступен. Проверьте баланс и условия действия."); return; }
  const accept = () => { if(!can(order)) return; queue.push(order); ready = false; render(); };
  if(order[0] === "l" && order[1] === S.mi) confirmAction("Вы уверены, что хотите себя бомбить? Цель: " + S.ci[order[2]][0], accept);
  else accept();
}
function remove(index) {
  if(queue[index][0] === "p" && freeBombs() < 1) { alert("Сначала отмените запуск, использующий эту бомбу."); return; }
  queue.splice(index,1); ready = false; render();
}
function describe(o) {
  switch(o[0]) {
    case "i": return "Инвестиции " + o[2] + "$ → " + S.ci[o[1]][0];
    case "b": return "Восстановление → " + S.ci[o[1]][0];
    case "n": return "Санкции → " + nameOf(o[1]);
    case "s": return "Щит → " + S.ci[o[1]][0];
    case "e": return "Экология: " + o[1] + "$";
    case "r": return "Исследование";
    case "p": return "Производство бомбы";
    case "l": return "Удар → " + targets.find(c => c[0] === o[1])[2][o[2]][0] + " (" + nameOf(o[1]) + ")";
    default: return "Неизвестный приказ";
  }
}
function investmentInput(value, onValue, orderFor, label) {
  const button = el("button", {disabled: !can(orderFor(Number(value))), onclick: () => add(orderFor(Number(input.value)))}, label);
  const input = el("input", {type:"number", min:"1", max:"1000000", step:"1", value, "aria-label":"Сумма в долларах", oninput: e => {onValue(e.target.value); button.disabled = !can(orderFor(Number(e.target.value)));}});
  return el("div", {class:"invest"}, input, button);
}
function send(payload) {
  const data = JSON.stringify({t:S.t,g:S.g,...payload});
  if(new TextEncoder().encode(data).length > 4096) { alert("Слишком много приказов. Уменьшите очередь и повторите."); return; }
  if(insideTelegram) tg.sendData(data);
  else alert("Предпросмотр вне Telegram. Данные:\n" + data);
}
function submit() { send({q:queue,r:ready}); }
function transferView() {
  const select = el("select", {"aria-label":"Получатель", onchange:e => {recipient = Number(e.target.value);}}, S.o.map(c => el("option",{value:c[0]},c[1])));
  select.value = String(recipient);
  const input = el("input", {type:"number",min:"1",step:"1",value:transferAmount,"aria-label":"Сумма перевода",oninput:e => {transferAmount=e.target.value;}});
  return el("div", {class:"card"},
    el("p", {class:"hint"}, "Перевод выполняется сразу. Получатель может тратить деньги в этом раунде. Перед переводом отправьте изменённую очередь."),
    el("div", {class:"transfer"}, select, input),
    el("button", {style:"margin-top:8px", onclick: () => {
      if(JSON.stringify(queue) !== JSON.stringify(S.q) || ready !== !!S.r) {alert("Сначала отправьте приказы, затем откройте мини-приложение заново для перевода."); return;}
      const amount = Number(transferAmount);
      if(!Number.isSafeInteger(amount) || amount <= 0 || amount > available()) {alert("Введите целую сумму в пределах доступного баланса."); return;}
      confirmAction("Перевести " + amount + "$ в " + nameOf(recipient) + " немедленно?", () => {
        const nonce = Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join("-");
        send({x:[recipient,amount,nonce]});
      });
    }}, "Перевести немедленно"));
}
let rulesDialog = null;
function closeRules() {
  if(!rulesDialog) return;
  rulesDialog.close(); rulesDialog.remove(); rulesDialog = null;
  if(insideTelegram) {tg.BackButton.offClick(closeRules); tg.BackButton.hide();}
  render(); document.querySelector(".help").focus();
}
function openRules() {
  if(rulesDialog) return;
  rulesDialog = el("dialog", {class:"rules-dialog","aria-label":"Правила хард-режима"},
    el("div", {class:"row rules-header"}, el("strong",{class:"grow"},"Правила хард-режима"),el("button",{onclick:closeRules},"Назад к приказам")),
    el("iframe",{src:"hard-rules.html",title:"Правила хард-режима"}));
  rulesDialog.addEventListener("cancel",e => {e.preventDefault();closeRules();});
  document.body.append(rulesDialog);rulesDialog.showModal();
  if(insideTelegram) {tg.MainButton.hide();tg.BackButton.onClick(closeRules);tg.BackButton.show();}
}
function render() {
  document.getElementById("app").replaceChildren(
    el("div",{class:"row"},el("h1",{class:"grow"},"Хард · раунд " + S.t + "/" + S.rounds + " · " + S.c),el("button",{class:"help ghost",onclick:openRules,"aria-label":"Правила хард-режима"},"?")),
    el("div",{class:"card",style:"margin-top:12px"},
      el("div",{class:"money"},available() + "$ доступно из " + S.b + "$"),
      el("div",{},"Экология " + short(S.e) + "% · свободных бомб " + freeBombs()),
      el("div",{class:"hint"},"Технология: " + (S.tech === 2 ? "есть" : "нет") + ". Прогноз дохода +" + S.income + "$ до санкций. Содержание бомбы: " + S.upkeep + "$")),
    ...(!alive() ? [el("p",{},"Нет живых городов. Восстанавливайте города, принимайте и отправляйте переводы. Помощь ООН: " + S.aid + "$ за раунд.")] : []),
    ...(S.report?.length ? [el("details",{class:"report card"},el("summary",{},"Отчёт за прошлый раунд"),S.report.map(text => el("div",{class:"line"},text)))] : []),
    el("h2",{},"Мои города"),
    ...S.ci.map((city,i) => {
      const card = el("div",{class:"card",style:"margin-bottom:8px"},el("strong",{},city[0]),el("div",{class:"hint"},city[4] ? "Жизнь " + short(city[1]) + "% · развитие " + short(city[2]) + (city[3] ? " · щит" : "") : "Уничтожен"));
      if(city[4]) card.append(investmentInput(cityAmounts[i],v => {cityAmounts[i]=v;},amount => ["i",i,amount],"Вложить"),el("button",{class:"ghost",style:"margin-top:6px",disabled:!can(["s",i]),onclick:()=>add(["s",i])},"Щит " + S.cost[2] + "$"));
      else card.append(el("button",{disabled:!can(["b",i]),onclick:()=>add(["b",i])},"Отстроить " + S.cost[3] + "$"));
      return card;
    }),
    el("h2",{},"Действия"),
    el("div",{class:"actions"},el("button",{disabled:!can(["r"]),onclick:()=>add(["r"])},"Технология " + S.cost[0] + "$"),el("button",{disabled:!can(["p"]),onclick:()=>add(["p"])},"Бомба " + S.cost[1] + "$")),
    el("h2",{},"Вложения в экологию"),investmentInput(ecoAmount,v => {ecoAmount=v;},amount => ["e",amount],"Вложить"),
    el("h2",{},"Удары"),
    el("div",{class:"card"},targets.map(c => el("div",{class:"target"},el("strong",{},c[1]),el("div",{class:"chips"},c[2].map((city,j) => city[1] ? el("button",{disabled:!can(["l",c[0],j]),onclick:()=>add(["l",c[0],j])},city[0]) : el("span",{class:"dead"},city[0])))))),
    el("h2",{},"Санкции · " + S.sanction + "% каждая, предел " + S.sanctionCap + "%"),
    el("div",{class:"chips"},S.o.map(c => el("button",{disabled:!can(["n",c[0]]),onclick:()=>add(["n",c[0]])},c[1]))),
    el("h2",{},"Мгновенный перевод"),transferView(),
    el("h2",{},"Очередь (" + queue.length + ")"),
    el("div",{class:"card"},queue.length ? queue.map((o,i) => el("div",{class:"row order"},el("div",{class:"grow"},describe(o)),el("button",{onclick:()=>remove(i),"aria-label":"Отменить приказ"},"×"))) : el("div",{class:"hint"},"Приказов пока нет")),
    el("label",{class:"ready"},el("input",{type:"checkbox",checked:ready,onchange:e=>{ready=e.target.checked;}}),"Я закончил ход"),
    el("p",{class:"hint"},"Отправка заменяет всю вашу очередь. Новые бомбы можно запустить в этом же раунде. Восстановленные города защищены от ударов в раунде восстановления.")
  );
  const label = "Отправить приказы (" + queue.length + ")";
  if(insideTelegram) {tg.MainButton.setText(label);tg.MainButton.show();}
  else {const button=document.getElementById("fallback");button.hidden=false;button.textContent=label + " — предпросмотр";button.onclick=submit;}
}
if(insideTelegram) {tg.ready();tg.expand();tg.MainButton.onClick(submit);}
render();
