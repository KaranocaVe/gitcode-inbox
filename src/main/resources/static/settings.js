import {element, button, boxMessage, errorText, formattedTime, notice} from "./ui.js";

const options = [
  {key: "poll_seconds", label: "自动同步间隔", min: 0, max: 3600, unit: "秒", help: "可设为 15–3600 秒。设为 0 时关闭定时同步。"},
  {key: "full_interval_seconds", label: "完整校准间隔", min: 60, max: 86400, unit: "秒", help: "可设为 60–86400 秒。达到此间隔后，同步时重新读取全部未读通知。"},
  {key: "sync_debounce_seconds", label: "同步去重窗口", min: 1, max: 300, unit: "秒", help: "可设为 1–300 秒。窗口内的重复同步请求由服务去重。"},
  {key: "upstream_requests_per_minute", label: "GitCode 请求上限", min: 10, max: 600, unit: "次/分钟", help: "上限为 10–600 次/分钟。"},
  {key: "api_requests_per_minute", label: "服务 API 请求上限", min: 10, max: 1200, unit: "次/分钟", help: "上限为 10–1200 次/分钟。"},
];

function sheet(title, className = "sheet") {
  const section = element("section", null, className);
  section.append(element("h2", title));
  return section;
}

function statusNode() {
  const status = element("p", null, "notice settings-status");
  status.setAttribute("role", "status");
  status.hidden = true;
  return status;
}

function labelAndInput(label, name, type, {required = false, maxLength} = {}) {
  const wrapper = element("label", null, "labeled-input");
  const input = element("input");
  input.name = name;
  input.type = type;
  input.required = required;
  if (maxLength) input.maxLength = maxLength;
  input.autocomplete = "off";
  input.spellcheck = false;
  wrapper.append(element("span", label), input);
  return {wrapper, input};
}

function renderOptions(container, data, api) {
  const form = element("form", null, "settings-form");
  const status = statusNode();
  const fields = element("fieldset", null, "settings-fields");
  const values = [];
  for (const field of options) {
    const row = element("div", null, "setting-row");
    const description = element("div");
    const label = element("label", field.label, "setting-label");
    label.htmlFor = "setting-" + field.key;
    const help = element("p", field.help, "setting-help");
    help.id = "hint-" + field.key;
    description.append(label, help);
    const input = element("input");
    input.id = label.htmlFor;
    input.type = "number";
    input.name = field.key;
    input.required = true;
    input.step = "1";
    input.min = String(field.min);
    input.max = String(field.max);
    input.defaultValue = String(data[field.key] ?? "");
    input.setAttribute("aria-describedby", help.id);
    if (field.key === "poll_seconds") {
      input.addEventListener("input", () => {
        const value = input.valueAsNumber;
        input.setCustomValidity(value > 0 && value < 15 ? "请输入 0 或 15–3600 秒。" : "");
      });
      form.addEventListener("reset", () => input.setCustomValidity(""));
    }
    const unit = element("span", field.unit, "setting-unit");
    unit.setAttribute("aria-hidden", "true");
    const control = element("div", null, "setting-input-area");
    control.append(input, unit);
    row.append(description, control);
    fields.append(row);
    values.push(input);
  }
  const actions = element("div", null, "form-actions");
  const reset = element("button", "还原", "button");
  reset.type = "reset";
  const save = element("button", "保存设置", "button primary");
  save.type = "submit";
  actions.append(reset, save);
  fields.append(actions);
  form.append(fields, status);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const body = Object.fromEntries(values.map((input) => [input.name, input.valueAsNumber]));
    fields.disabled = true;
    notice(status, "");
    try {
      const saved = await api("/admin/settings", {method: "PUT", body});
      for (const input of values) {
        input.defaultValue = String(saved[input.name] ?? body[input.name]);
        input.value = input.defaultValue;
      }
      notice(status, "设置已生效。");
    } catch (error) {
      notice(status, errorText(error), "error");
    } finally {
      fields.disabled = false;
    }
  });
  container.replaceChildren(form);
}

async function loadOptions(container, api) {
  container.setAttribute("aria-busy", "true");
  container.replaceChildren(boxMessage("正在读取服务设置…"));
  try {
    const data = await api("/admin/settings");
    if (container.isConnected) renderOptions(container, data, api);
  } catch (error) {
    if (container.isConnected) container.replaceChildren(boxMessage(errorText(error), () => loadOptions(container, api)));
  } finally {
    container.setAttribute("aria-busy", "false");
  }
}

function webSession(api, session, refresh) {
  const section = sheet("GitCode 网页会话");
  const body = element("div");
  section.append(body);

  function render(linked, result) {
    const description = element("p", linked ?
      "已关联同一 GitCode 账户的网页会话。会话过期时可在此更新。" :
      "关联同一 GitCode 账户的网页会话后，可以读取并更新通知。", "settings-note");
    const form = element("form", null, "settings-form credential-form");
    const fields = element("fieldset", null, "settings-fields");
    const access = labelAndInput("网页访问令牌", "access_token", "password", {required: true, maxLength: 16384});
    const refreshInput = labelAndInput("网页刷新令牌（可选）", "refresh_token", "password", {maxLength: 16384});
    const controls = element("div", null, "form-actions");
    const submit = element("button", linked ? "更新会话" : "关联会话", "button primary");
    submit.type = "submit";
    if (linked) {
      controls.append(button("断开会话", () => {
        if (window.confirm("断开网页会话并停止同步通知？")) update("DELETE");
      }));
    }
    controls.append(submit);
    fields.append(access.wrapper, refreshInput.wrapper, controls);
    const status = statusNode();
    form.append(fields, status);

    async function update(method, credentials) {
      fields.disabled = true;
      notice(status, "");
      try {
        await api("/session/web", {method, body: credentials});
        form.reset();
        refresh(method === "PUT");
        if (body.isConnected) render(method === "PUT", method === "PUT" ? "网页会话已关联。" : "网页会话已断开。");
      } catch (error) {
        notice(status, errorText(error), "error");
      } finally {
        fields.disabled = false;
      }
    }

    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const accessToken = access.input.value.trim();
      if (!accessToken) {
        access.input.value = "";
        access.input.reportValidity();
        return;
      }
      const refreshToken = refreshInput.input.value.trim();
      const body = {access_token: accessToken};
      if (refreshToken) body.refresh_token = refreshToken;
      update("PUT", body);
    });
    body.replaceChildren(description, form);
    if (result) notice(status, result);
  }

  render(session.linked);
  return section;
}

function newKeyCard(token) {
  const box = element("div", null, "key-result");
  const field = labelAndInput("新令牌仅在此显示一次", "api_token", "text");
  field.input.readOnly = true;
  field.input.value = token;
  const feedback = statusNode();
  const copy = button("复制令牌", async () => {
    try {
      await navigator.clipboard.writeText(field.input.value);
      notice(feedback, "令牌已复制。");
    } catch {
      field.input.focus();
      field.input.select();
      notice(feedback, "令牌已选择，可以手动复制。");
    }
  });
  const clear = button("关闭", () => {
    field.input.value = "";
    box.remove();
  }, "plain-button");
  const actions = element("div", null, "key-result-actions");
  actions.append(copy, clear);
  box.append(field.wrapper, actions, feedback);
  return box;
}

function apiKeys(api, isAdmin) {
  const section = sheet("API 令牌");
  const form = element("form", null, "quick-form");
  const name = labelAndInput("令牌名称", "name", "text", {required: true, maxLength: 100});
  name.input.placeholder = "例如 CI 同步";
  const submit = element("button", "创建令牌", "button primary");
  submit.type = "submit";
  form.append(name.wrapper);
  const adminScope = element("input");
  adminScope.type = "checkbox";
  if (isAdmin) {
    const scopeLabel = element("label", null, "select-page");
    scopeLabel.append(adminScope, element("span", "允许 MCP 管理设置和查看指标"));
    form.append(scopeLabel);
  }
  form.append(submit);
  const keyOutput = element("div");
  const list = element("div", null, "key-list");
  const status = statusNode();
  section.append(form, keyOutput, list, status);

  async function load() {
    list.setAttribute("aria-busy", "true");
    try {
      const data = await api("/session/keys");
      if (!section.isConnected) return;
      const items = data.items || [];
      if (!items.length) {
        list.replaceChildren(element("p", "尚未创建 API 令牌。", "settings-note"));
        return;
      }
      const rows = element("ul");
      for (const item of items) {
        const row = element("li", null, "key-row");
        const text = element("div");
        const createdAt = formattedTime(item.created_at);
        text.append(element("strong", item.name));
        if (createdAt) text.append(element("p", "创建于 " + createdAt, "settings-note"));
        if (item.scope === "mcp_admin") text.append(element("p", "MCP 管理权限", "settings-note"));
        const revoke = button("撤销", async () => {
          if (!window.confirm(`撤销「${item.name}」的 API 令牌？`)) return;
          revoke.disabled = true;
          notice(status, "");
          try {
            await api("/session/keys/" + encodeURIComponent(item.id), {method: "DELETE"});
            await load();
            notice(status, "令牌已撤销。");
          } catch (error) {
            notice(status, errorText(error), "error");
          } finally {
            revoke.disabled = false;
          }
        });
        revoke.setAttribute("aria-label", "撤销 " + item.name);
        row.append(text, revoke);
        rows.append(row);
      }
      list.replaceChildren(rows);
    } catch (error) {
      if (section.isConnected) list.replaceChildren(boxMessage(errorText(error), load));
    } finally {
      list.setAttribute("aria-busy", "false");
    }
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const value = name.input.value.trim();
    if (!value) {
      name.input.value = "";
      name.input.reportValidity();
      return;
    }
    submit.disabled = true;
    notice(status, "");
    try {
      const item = await api("/session/keys", {method: "POST", body: {name: value, scope: adminScope.checked ? "mcp_admin" : "inbox"}});
      form.reset();
      if (section.isConnected) keyOutput.replaceChildren(newKeyCard(item.token));
      await load();
    } catch (error) {
      notice(status, errorText(error), "error");
    } finally {
      submit.disabled = false;
    }
  });
  return {section, load};
}

export function loadSettings(host, api, session, refresh) {
  host.replaceChildren();
  if (session.admin) {
    const section = sheet("同步与限流");
    const holder = element("div");
    section.append(holder);
    host.append(section);
    loadOptions(holder, api);
  }
  if (!session.demo) host.append(webSession(api, session, refresh));
  const keys = apiKeys(api, session.admin);
  host.append(keys.section);
  keys.load();
}
