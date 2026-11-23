import {byId, element, button, boxMessage, clock, dateTime, formattedTime, gitcodeLink, notice, errorText, number} from "./ui.js";
import {loadDashboard, closeDashboard} from "./dashboard.js";
import {loadSettings} from "./settings.js";

const PAGE_SIZE = 20;
const DETAIL_SIZE = 20;
const actions = {
  open: "打开", close: "关闭", reopen: "重新打开", merge: "合入", comment: "评论",
  mention: "提及", review: "评审", invite: "邀请", update: "更新",
};
const names = {inbox: "收件箱", dashboard: "运行概览", settings: "设置"};
const mobile = matchMedia("(max-width: 735px)");
const state = {
  session: null, status: null, route: null, filter: "all", query: "",
  items: [], total: 0, offset: 0, version: null, listed: false,
  active: null, selected: new Set(), pending: false,
  listController: null, detailController: null, detailOffset: 0, detailTotal: 0,
  detailLoaded: false, searchTimer: null, polling: false, rateUntil: 0, rateTimer: null,
};

async function api(path, {method = "GET", body, signal} = {}) {
  const headers = {Accept: "application/json"};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && state.session?.csrf_token) headers["X-CSRF-Token"] = state.session.csrf_token;
  const response = await fetch("/api/v1" + path, {
    method, headers, signal, cache: "no-store", credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = response.status === 204 ? {} : await response.json().catch(() => null);
  if (response.ok && data != null) return data;
  const header = response.headers.get("Retry-After");
  const retryAfter = header && /^\d+$/.test(header) ? Number(header) : Math.max(0, (Date.parse(header) - Date.now()) / 1000);
  if (response.status === 401 && state.session?.authenticated && path !== "/session") {
    setSignedOut("会话已过期，请重新登录。");
  }
  if (response.status === 429 && state.session?.authenticated) {
    state.rateUntil = Math.max(state.rateUntil, Date.now() + (retryAfter || 15) * 1000);
    clearTimeout(state.rateTimer);
    state.rateTimer = setTimeout(paintStatus, state.rateUntil - Date.now());
    paintStatus();
  }
  throw Object.assign(new Error(data?.message || "暂时无法完成请求。"), {
    status: response.status, data, retryAfter,
  });
}

function report(error, node = byId("feedback")) {
  if (error.name === "AbortError" || !state.session?.authenticated) return;
  notice(node, errorText(error), "error");
}

function loginUrl(value) {
  try {
    const url = new URL(value || "/oauth/login", window.location.origin);
    if (url.origin === window.location.origin) return url.pathname + url.search;
  } catch {
    // Use the service's local OAuth entry point.
  }
  return "/oauth/login";
}

function setSignedOut(message, mode = "login", url) {
  state.listController?.abort();
  state.detailController?.abort();
  state.session = null;
  state.status = null;
  state.route = null;
  state.items = [];
  state.active = null;
  state.selected.clear();
  state.total = state.offset = state.detailOffset = 0;
  state.listed = false;
  state.version = null;
  state.rateUntil = 0;
  clearTimeout(state.rateTimer);
  byId("settings-content").replaceChildren();
  closeDashboard(byId("dashboard-content"));
  byId("rows").replaceChildren();
  byId("detail-content").replaceChildren();
  byId("read-confirm").close("cancel");
  byId("startup").hidden = false;
  byId("startup-copy").textContent = message;
  byId("workspace").hidden = true;
  byId("account-area").hidden = true;
  byId("navigation").hidden = true;
  byId("login").href = loginUrl(url);
  byId("login").hidden = mode !== "login";
  byId("retry").hidden = mode !== "retry";
}

function linked() {
  return state.session?.demo || state.session?.linked;
}

async function discoverSession() {
  byId("retry").disabled = true;
  try {
    const session = await api("/session");
    if (!session.authenticated) {
      const feedback = session.error || (new URL(location.href).searchParams.has("error") ? "登录未完成，请重新登录。" : "");
      setSignedOut(feedback || "登录后查看 GitCode 的未读通知。", "login", session.login_url);
      return;
    }
    state.session = session;
    byId("startup").hidden = true;
    byId("workspace").hidden = false;
    byId("navigation").hidden = false;
    byId("account-area").hidden = false;
    const username = typeof session.account === "string" ? session.account : session.account?.username;
    byId("account-name").textContent = username ? "@" + username : "";
    byId("logout").hidden = !!session.demo;
    byId("navigation").querySelector('[href="#dashboard"]').hidden = !session.admin;
    renderRoute();
  } catch (error) {
    setSignedOut(errorText(error), "retry");
  } finally {
    byId("retry").disabled = false;
  }
}

function setPageHeading() {
  const view = state.route;
  byId("page-title").textContent = names[view];
  document.title = names[view] + " · GitCode";
  const summary = byId("page-summary");
  summary.textContent = {
    inbox: linked() && state.status ? `共 ${number.format(state.status.unread_total ?? 0)} 条未读通知` : "",
    dashboard: "同步、限流与已读操作",
    settings: "",
  }[view];
  summary.hidden = !summary.textContent;
  byId("sync-controls").hidden = view !== "inbox" || !linked();
}

function renderRoute(focus = false) {
  if (!state.session) return;
  const requested = location.hash.slice(1);
  const view = requested === "settings" || (requested === "dashboard" && state.session.admin) ? requested : "inbox";
  if (view === state.route) return;
  state.route = view;
  state.listController?.abort();
  state.detailController?.abort();
  state.detailLoaded = false;
  notice(byId("feedback"), "");
  for (const name of Object.keys(names)) byId(name + "-view").hidden = name !== view;
  for (const item of byId("navigation").querySelectorAll("a")) {
    if (item.hash === "#" + view) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
  if (view !== "settings") byId("settings-content").replaceChildren();
  if (view !== "dashboard") closeDashboard(byId("dashboard-content"));
  setPageHeading();
  paintStatus();
  if (view === "inbox") {
    if (linked()) loadList();
    else paintUnlinked();
  } else if (view === "dashboard") {
    loadDashboard(byId("dashboard-content"), api);
  } else {
    loadSettings(byId("settings-content"), api, state.session, refreshLink);
  }
  if (focus) {
    window.scrollTo(0, 0);
    byId("page-title").focus({preventScroll: true});
  }
}

function refreshLink(isLinked) {
  if (!state.session) return;
  state.session = {...state.session, linked: isLinked};
  state.status = null;
  state.version = null;
  state.items = [];
  state.active = null;
  state.selected.clear();
  state.total = state.offset = 0;
  state.listed = false;
  paintStatus();
  if (state.route === "inbox") {
    if (isLinked) loadList();
    else paintUnlinked();
  }
}

function paintUnlinked() {
  byId("inbox-board").classList.add("is-unassigned");
  closeMobileDetail();
  byId("list-toolbar").hidden = true;
  byId("search-area").hidden = true;
  byId("filters").hidden = true;
  byId("pager").hidden = true;
  byId("detail").hidden = true;
  const target = byId("rows");
  const box = boxMessage("关联 GitCode 网页会话以同步通知。");
  const link = element("a", "关联网页会话", "button primary");
  link.href = "#settings";
  box.append(link);
  target.replaceChildren(box);
  target.setAttribute("aria-busy", "false");
}

function paintStatus() {
  if (!state.route) return;
  const status = state.status;
  const sync = byId("sync-status");
  const rateLimited = Date.now() < state.rateUntil;
  const busy = state.pending || status?.busy;
  let label = "等待首次同步";
  let tone = "idle";
  if (busy) label = "正在处理";
  else if (rateLimited) {
    label = "暂缓请求";
    tone = "error";
  } else if (status?.last_error) {
    label = "最近同步失败";
    tone = "error";
  } else if (status?.last_success) {
    label = "同步于 " + formattedTime(status.last_success, clock);
    tone = "ready";
  }
  if (sync.textContent !== label) sync.textContent = label;
  sync.dataset.tone = tone;
  byId("sync").disabled = byId("full-sync").disabled = !!busy || rateLimited;
  const count = status?.unassigned_count;
  byId("unassigned-count").textContent = count ? number.format(count) : "";
  if (state.route === "inbox") setPageHeading();
  const banner = byId("service-notice");
  if (state.route !== "inbox" || !linked()) notice(banner, "");
  else if (status?.last_error) notice(banner, status.last_error, "error");
  else if (status?.dry_run) notice(banner, "标记已读为预演。");
  else if (state.session?.demo) notice(banner, "演示数据，可试用同步和标记已读。");
  else notice(banner, "");
  paintSelection();
}

function paintSelection() {
  const count = state.items.filter(({id}) => state.selected.has(id)).length;
  const all = byId("select-all");
  all.checked = count > 0 && count === state.items.length;
  all.indeterminate = count > 0 && count !== state.items.length;
  all.disabled = !state.items.length || state.pending;
  const mark = byId("mark-selected");
  mark.textContent = state.selected.size ? `标记已读 (${state.selected.size})` : "标记已读";
  const writeDisabled = !!(state.pending || state.status?.busy || Date.now() < state.rateUntil);
  mark.disabled = !state.selected.size || writeDisabled;
  const read = byId("detail-read");
  if (read) read.disabled = writeDisabled;
  for (const check of byId("rows").querySelectorAll(".row-check input")) {
    check.checked = state.selected.has(check.value);
    check.disabled = state.pending;
  }
}

function timeNode(value) {
  const node = element("time", formattedTime(value, dateTime));
  if (node.textContent) node.dateTime = value;
  return node;
}

function messageNode(item) {
  const article = element("article", null, "message");
  const byline = element("div", null, "message-byline");
  const author = element("span");
  const action = actions[item.action] || item.action || item.target_type;
  author.append(element("span", item.sender || "GitCode", "message-sender"));
  if (action) author.append(" · " + action);
  byline.append(author, timeNode(item.created_at));
  article.append(byline, element("p", item.content || "通知无正文。"));
  const link = gitcodeLink("原始通知 ↗", item.url);
  if (link) article.append(link);
  return article;
}

function rowTitle(row) {
  return row.title || (row.kind === "pr" ? "Pull Request" : "Issue") + " #" + row.number;
}

function rowNode(row) {
  const wrapper = element("div", null, "row");
  wrapper.classList.toggle("is-active", row.id === state.active);
  const checkLabel = element("label", null, "row-check");
  const check = element("input");
  check.type = "checkbox";
  check.value = row.id;
  check.setAttribute("aria-label", "选择 " + row.repository + " #" + row.number);
  check.addEventListener("change", () => {
    if (check.checked) state.selected.add(row.id);
    else state.selected.delete(row.id);
    paintSelection();
  });
  checkLabel.append(check);
  const open = button(null, () => openGroup(row.id), "row-open");
  open.dataset.group = row.id;
  open.setAttribute("aria-pressed", String(row.id === state.active));
  const title = element("span", rowTitle(row), "row-title");
  title.title = title.textContent;
  const meta = element("span", null, "row-meta");
  meta.append(element("span", (row.kind === "pr" ? "PR" : "Issue") + " #" + row.number, "row-kind"), row.repository);
  const count = element("span", number.format(row.unread_count), "count");
  count.title = count.textContent + " 条未读";
  count.setAttribute("aria-label", count.title);
  const time = timeNode(row.latest_at);
  time.className = "row-time";
  open.append(title, count, meta, time);
  wrapper.append(checkLabel, open);
  return wrapper;
}

function paintRows() {
  const other = state.filter === "unassigned";
  byId("list-toolbar").hidden = false;
  byId("select-page").hidden = byId("mark-selected").hidden = other;
  byId("filters").hidden = false;
  byId("search-area").hidden = other;
  byId("detail").hidden = other;
  byId("inbox-board").classList.toggle("is-unassigned", other);
  const count = state.total;
  byId("list-count").textContent = state.listed ? number.format(count) + (other ? " 条通知" : " 个分组") : "";
  const target = byId("rows");
  const focus = target.contains(document.activeElement) ? document.activeElement : null;
  const focusGroup = focus?.dataset.group;
  const focusCheck = focus?.matches(".row-check input") ? focus.value : null;
  if (state.items.length) target.replaceChildren(...state.items.map(other ? messageNode : rowNode));
  else {
    let message = "正在加载通知…";
    if (state.status?.last_error) message = "同步失败后没有可用的通知。";
    else if (state.status?.last_success) message = other ? "没有其他通知。" : state.query ? "未找到匹配的通知。" : "没有未读通知。";
    else if (state.listed) message = "首次同步完成后会显示通知。";
    target.replaceChildren(boxMessage(message));
  }
  if (focusGroup || focusCheck) {
    const candidates = focusCheck ? target.querySelectorAll(".row-check input") : target.querySelectorAll(".row-open");
    [...candidates].find((candidate) => focusCheck ? candidate.value === focusCheck : candidate.dataset.group === focusGroup)?.focus({preventScroll: true});
  }
  byId("pager").hidden = count <= PAGE_SIZE;
  byId("page-number").textContent = `${Math.floor(state.offset / PAGE_SIZE) + 1} / ${Math.ceil(count / PAGE_SIZE)}`;
  byId("next").disabled = state.offset + PAGE_SIZE >= count;
  byId("prev").disabled = !state.offset;
  paintSelection();
}

function paintDetail(row) {
  const heading = element("h2", rowTitle(row));
  heading.tabIndex = -1;
  heading.id = "detail-title";
  const meta = element("p", null, "detail-meta");
  meta.append(element("strong", row.kind === "pr" ? "PR" : "Issue"), row.repository + " #" + row.number);
  const controls = element("div", null, "detail-actions");
  const read = button("标记本组已读", () => confirmRead([row.id]));
  read.id = "detail-read";
  controls.append(read);
  const link = gitcodeLink("在 GitCode 查看 ↗", row.url);
  if (link) controls.append(link);
  const head = element("div", null, "detail-head");
  head.append(meta, heading, controls);
  const messages = element("div");
  messages.id = "detail-messages";
  const label = element("p", number.format(row.unread_count) + " 条未读通知", "detail-label");
  const panel = byId("detail-content");
  panel.replaceChildren(head, label, messages);
  byId("detail").scrollTop = 0;
  paintSelection();
}

function closeMobileDetail(focus = false) {
  byId("inbox-board").removeAttribute("data-mobile-detail");
  if (focus) {
    const row = [...byId("rows").querySelectorAll(".row-open")].find((item) => item.dataset.group === state.active);
    (row || byId("search")).focus();
  }
}

function openGroup(id) {
  state.active = id;
  for (const item of byId("rows").querySelectorAll(".row-open")) {
    const active = item.dataset.group === id;
    item.setAttribute("aria-pressed", String(active));
    item.parentElement.classList.toggle("is-active", active);
  }
  byId("inbox-board").dataset.mobileDetail = "true";
  loadDetail();
  if (mobile.matches) {
    window.scrollTo(0, 0);
    byId("detail-title")?.focus({preventScroll: true});
  }
}

async function loadDetail(more = false) {
  state.detailController?.abort();
  const row = state.items.find((item) => item.id === state.active);
  if (state.filter === "unassigned" || !row) {
    closeMobileDetail();
    byId("detail-content").replaceChildren(boxMessage(state.listed ? "选择一个分组查看通知。" : "正在加载通知…"));
    return;
  }
  const controller = new AbortController();
  state.detailController = controller;
  if (!more) {
    state.detailOffset = 0;
    state.detailTotal = row.unread_count;
    state.detailLoaded = false;
    paintDetail(row);
  }
  const target = byId("detail-messages");
  target.setAttribute("aria-busy", "true");
  target.querySelector(".more")?.remove();
  const loading = element("p", "正在加载通知…", "empty");
  target.append(loading);
  try {
    const path = "/groups/" + row.id.split("/").map(encodeURIComponent).join("/") + "/messages";
    const query = new URLSearchParams({limit: DETAIL_SIZE, offset: state.detailOffset});
    if (state.version != null) query.set("version", state.version);
    const data = await api(path + "?" + query, {signal: controller.signal});
    if (controller.signal.aborted) return;
    for (const item of data.items) target.append(messageNode(item));
    state.detailOffset += data.items.length;
    state.detailTotal = data.total;
    state.detailLoaded = true;
    if (!state.detailOffset) target.append(boxMessage("本组当前没有未读通知。"));
    if (state.detailOffset < state.detailTotal && data.items.length) {
      const next = button("加载更多通知", () => loadDetail(true), "button more");
      target.append(next);
      if (more) next.focus({preventScroll: true});
    }
  } catch (error) {
    if (error.name === "AbortError") return;
    if (error.data?.error === "snapshot_changed") {
      state.detailLoaded = false;
      await loadList();
    } else if (state.session?.authenticated) {
      target.append(boxMessage(errorText(error), () => loadDetail(more)));
    }
  } finally {
    loading.remove();
    target.setAttribute("aria-busy", "false");
  }
}

async function loadList({withStatus = true} = {}) {
  if (state.route !== "inbox" || !linked()) return;
  state.listController?.abort();
  const controller = new AbortController();
  state.listController = controller;
  const target = byId("rows");
  target.setAttribute("aria-busy", "true");
  if (!state.listed) {
    paintRows();
    byId("detail-content").replaceChildren(boxMessage("正在加载通知…"));
  }
  const options = {signal: controller.signal};
  try {
    const other = state.filter === "unassigned";
    const query = new URLSearchParams({limit: PAGE_SIZE, offset: state.offset});
    if (!other && state.filter !== "all") query.set("kind", state.filter);
    if (!other && state.query) query.set("q", state.query);
    const [data, status] = await Promise.all([
      api((other ? "/unassigned" : "/groups") + "?" + query, options),
      withStatus ? api("/status", options) : Promise.resolve(state.status),
    ]);
    if (controller.signal.aborted) return;
    if (state.offset && state.offset >= data.total) {
      state.offset = Math.max(0, Math.floor((data.total - 1) / PAGE_SIZE) * PAGE_SIZE);
      await loadList();
      return;
    }
    const previousVersion = state.version;
    const previousActive = state.active;
    state.version = other ? data.version : data.status?.version ?? status?.version;
    state.status = {...data.status, ...status};
    state.items = data.items;
    state.total = data.total;
    state.listed = true;
    const visible = new Set(data.items.map(({id}) => id));
    state.selected = new Set([...state.selected].filter((id) => visible.has(id)));
    if (other || !visible.has(state.active)) {
      state.active = other ? null : data.items[0]?.id;
      closeMobileDetail();
    }
    paintStatus();
    paintRows();
    if (!state.active || previousActive !== state.active || previousVersion !== state.version || !state.detailLoaded) {
      await loadDetail();
    }
  } catch (error) {
    if (error.name === "AbortError" || !state.session?.authenticated) return;
    if (!state.listed) {
      target.replaceChildren(boxMessage(errorText(error), () => loadList()));
      byId("detail-content").replaceChildren();
    }
    report(error);
  } finally {
    if (state.listController === controller) target.setAttribute("aria-busy", "false");
  }
}

async function refreshStatus() {
  if (state.route !== "inbox" || !linked() || document.hidden || state.polling || state.pending || Date.now() < state.rateUntil) return;
  state.polling = true;
  const session = state.session;
  try {
    const status = await api("/status");
    if (state.session !== session || state.route !== "inbox") return;
    state.status = status;
    const versionChanged = status.version !== state.version;
    paintStatus();
    if (versionChanged && byId("rows").getAttribute("aria-busy") !== "true" && !state.searchTimer) {
      await loadList({withStatus: false});
    }
  } catch (error) {
    if (state.session === session && state.route === "inbox") report(error);
  } finally {
    state.polling = false;
  }
}

async function mutate(callback) {
  if (state.pending || !linked()) return;
  state.pending = true;
  const session = state.session;
  paintStatus();
  notice(byId("feedback"), "");
  try {
    const result = await callback();
    if (state.session === session && state.route === "inbox") notice(byId("feedback"), result);
  } catch (error) {
    const partial = error.data?.result;
    if (partial && state.session === session && state.route === "inbox") {
      const uncertain = error.data.uncertain_ids?.length || 0;
      const copy = `已确认 ${partial.confirmed ?? 0} 条通知` + (uncertain ? `，另有 ${uncertain} 条待核对。 ` : "。 ");
      notice(byId("feedback"), copy + errorText(error), "error");
    } else if (state.session === session && state.route === "inbox") {
      report(error);
    }
  } finally {
    state.pending = false;
    if (state.session === session && state.route === "inbox") {
      paintStatus();
      if (Date.now() >= state.rateUntil) await loadList();
    }
  }
}

function startSync(mode) {
  byId("sync-more").open = false;
  mutate(async () => {
    const result = await api("/sync", {method: "POST", body: {mode}});
    if (result.mode === "debounced") return "刚同步过，请稍后再试。";
    return (result.mode === "full" ? "完整校准完成" : "同步完成") + `，共 ${number.format(result.unread_total ?? 0)} 条未读通知。`;
  });
}

function confirmRead(ids) {
  if (!ids.length || state.pending) return;
  const dialog = byId("read-confirm");
  const dryRun = state.status?.dry_run;
  dialog.returnValue = "";
  byId("read-copy").textContent = dryRun ?
    `预演 ${ids.length} 个分组，查看涉及的通知数量。` :
    `在 GitCode 中将这 ${ids.length} 个分组的全部未读通知标记为已读？`;
  byId("confirm-read").textContent = dryRun ? "预演" : "标记已读";
  dialog.addEventListener("close", () => {
    if (dialog.returnValue !== "read") return;
    mutate(async () => {
      const result = await api("/groups/read-state", {method: "PUT", body: {group_ids: ids, read: true}});
      if (result.dry_run) return `预演命中 ${number.format(result.matched)} 条通知。`;
      for (const id of ids) state.selected.delete(id);
      return `已标记 ${number.format(result.confirmed)} 条通知为已读。`;
    });
  }, {once: true});
  dialog.showModal();
}

function changeFilter(filter) {
  clearTimeout(state.searchTimer);
  state.searchTimer = null;
  state.offset = 0;
  state.filter = filter;
  state.query = byId("search").value.trim();
  state.selected.clear();
  state.active = null;
  state.items = [];
  state.total = 0;
  state.listed = false;
  state.detailLoaded = false;
  state.detailController?.abort();
  closeMobileDetail();
  for (const item of byId("filters").querySelectorAll(".segment")) {
    item.setAttribute("aria-pressed", String(item.dataset.filter === filter));
  }
  loadList();
}

document.querySelector(".skip").addEventListener("click", (event) => {
  event.preventDefault();
  byId("main").focus();
});
byId("retry").addEventListener("click", discoverSession);
byId("logout").addEventListener("click", async () => {
  byId("logout").disabled = true;
  try {
    await api("/session/logout", {method: "POST"});
    setSignedOut("已退出 GitCode 收件箱。");
  } catch (error) {
    report(error);
  } finally {
    byId("logout").disabled = false;
  }
});
byId("sync").addEventListener("click", () => startSync("incremental"));
byId("full-sync").addEventListener("click", () => startSync("full"));
document.addEventListener("click", (event) => {
  if (!byId("sync-more").contains(event.target)) byId("sync-more").open = false;
});
document.addEventListener("keydown", (event) => {
  const menu = byId("sync-more");
  if (event.key === "Escape" && menu.open) {
    menu.open = false;
    menu.querySelector("summary").focus();
  }
});
byId("select-all").addEventListener("change", (event) => {
  state.selected = new Set(event.target.checked ? state.items.map(({id}) => id) : []);
  paintSelection();
});
byId("mark-selected").addEventListener("click", () => confirmRead([...state.selected]));
byId("detail-back").addEventListener("click", () => closeMobileDetail(true));
byId("filters").addEventListener("click", (event) => {
  const item = event.target.closest(".segment");
  if (item && item.dataset.filter !== state.filter) changeFilter(item.dataset.filter);
});
byId("search").addEventListener("input", (event) => {
  clearTimeout(state.searchTimer);
  if (event.isComposing) return;
  state.searchTimer = setTimeout(() => changeFilter(state.filter), 240);
});
byId("search").addEventListener("compositionend", () => {
  clearTimeout(state.searchTimer);
  state.searchTimer = setTimeout(() => changeFilter(state.filter), 240);
});
for (const [id, delta] of [["prev", -PAGE_SIZE], ["next", PAGE_SIZE]]) {
  byId(id).addEventListener("click", async () => {
    state.offset = Math.max(0, state.offset + delta);
    state.selected.clear();
    closeMobileDetail();
    await loadList();
    const first = byId("rows").querySelector(".row-open");
    if (first) first.focus();
  });
}
window.addEventListener("hashchange", () => renderRoute(location.hash !== "#main"));
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refreshStatus();
});
setInterval(refreshStatus, 15000);
discoverSession();
